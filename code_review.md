# Code Review: Retained-Heap Circuit-Breaker Accounting for Chunked Fetch

**Branch:** main (uncommitted working-tree changes)
**Date:** 2026-07-17
**Scope:** 6 files changed — `DocumentField.java`, `SearchHit.java`, `FetchPhaseResponseStream.java`, `TransportFetchPhaseCoordinationAction.java`, and their test counterparts.

---

## Summary

This change switches circuit-breaker accounting for chunked fetch accumulation from **serialized byte length** to **retained heap estimate** (`ramBytesUsed()`). The motivation is correct: the serialized form badly undercounts actual memory when hits carry many extracted fields (e.g. `fields:[*]`). The approach — adding `ramBytesUsed()` to `DocumentField` and `SearchHit` and reordering the charge/deserialize steps in `writeChunk` — is sound. There are no critical correctness bugs, but the review found one latent structural fragility, one design inconsistency between two parallel code paths, and several systematic miscounts in the estimation logic.

---

## Findings

### 1. Latent structural bug: hits in `pending` are not released if `consumeHits` lambda throws an unchecked exception
**File:** `FetchPhaseResponseStream.java` · lines 103–115  
**Severity:** Medium (currently non-triggerable; structurally fragile)  
**Status:** PLAUSIBLE

**What changed.** The old `writeChunk` added hits directly to the queue inside the `consumeHits` lambda. If the lambda threw, already-queued hits were still reachable by `closeInternal()` and would be properly `decRef`'d on stream close.

The new code materialises all hits into a local `pending` list first, then does the CB check, then promotes `pending` into the queue. The `CircuitBreakingException` catch correctly iterates and `decRef`'s `pending`. However, the `catch(IOException)` at line 131 only wraps and re-throws — it never drains `pending`. If any unchecked exception escapes the lambda (currently only possible via `OutOfMemoryError` from `ArrayList.add`), those hits are not in the queue and not released.

```java
// NEW — structural gap
chunk.consumeHits((position, hit) -> {
    estimatedRetainedBytes[0] += hit.ramBytesUsed();
    pending.add(new SequencedHit(hit, position));   // OOM here → hit leaks
});
// ...
} catch (IOException e) {
    throw new RuntimeException("Failed to deserialize hits from chunk", e); // pending never drained
```

**Why it is not triggerable today.** `drainDeserializedHits` nulls each slot after handing the hit to the lambda. A throw from inside the lambda before that null-assignment would leave the hit in the chunk's `deserializedHits[]`, where `chunk.close()` would still reach it. The only way to reach hits in `pending` that escape both `closeInternal()` and `chunk.close()` is a throw that occurs after the slot is nulled but before `pending.add()` succeeds — currently the lambda cannot produce a checked exception, and OOM is the only realistic unchecked path.

**Recommendation.** Add a `finally` block that drains `pending` on non-success, mirroring the `CircuitBreakingException` catch:

```java
} catch (CircuitBreakingException | RuntimeException e) {
    for (SequencedHit sh : pending) { sh.hit.decRef(); }
    throw e;
}
```

Or restructure so that hits are added to the queue atomically after the CB check (and use a `finally` over the `pending` list).

---

### 2. Design inconsistency: last-chunk hits enter the queue before the CB check
**File:** `TransportFetchPhaseCoordinationAction.java` · lines 236–244  
**Severity:** Low–Medium (functionally correct; weakens back-pressure)  
**Status:** PLAUSIBLE

`writeChunk` correctly buffers hits into a local `pending` list and only promotes them to the queue after a successful CB check. The last-chunk path does the opposite: all hits are deserialized and inserted into the queue via `addHitWithSequence` **before** `addEstimateBytesAndMaybeBreak` is called.

```java
// All hits queued, THEN the breaker is checked
for (int i = 0; i < hitCount; i++) {
    SearchHit hit = SearchHit.readFrom(in);
    estimatedRetainedBytes += hit.ramBytesUsed();
    responseStream.addHitWithSequence(hit, position);   // <-- already in queue
}
circuitBreaker.addEstimateBytesAndMaybeBreak(estimatedRetainedBytes, ...);  // throws here
responseStream.trackBreakerBytes(estimatedRetainedBytes);                    // never reached
```

**Why the accounting is still correct.** If the CB throws: `trackBreakerBytes` is skipped (no bytes charged to `totalBreakerBytes`), and `closeInternal()` (reached via the `runAfter` cleanup) drains the queue and `decRef`'s those hits without trying to release phantom CB bytes. No leak, no over-release.

**Why it still matters.** Under memory pressure, the old code would reject a CB request before allocating any objects. The new last-chunk path allocates the full deserialized object graph (potentially many MB) before the CB can refuse. Additionally, hits live in the queue for an indeterminate window until async cleanup fires, rather than being proactively freed on the CB trip.

**Recommendation.** Mirror the `writeChunk` pattern: accumulate hits in a local list, check the CB, then add to the queue — and `decRef` the local list on a CB trip.

---

### 3. `innerHits` map and `SearchHits` wrapper overhead not counted
**File:** `SearchHit.java` · lines 325–330  
**Severity:** Low (systematic undercount for nested queries)  
**Status:** CONFIRMED

The `innerHits` loop recurses into individual leaf `SearchHit` objects but charges nothing for:

1. The outer `Map<String, SearchHits>` object (its `HashMap` shallow size, backing table array, and per-entry nodes).
2. Each `SearchHits` wrapper object (object header + six fields: `hits[]` ref, `totalHits`, `maxScore`, `sortFields`, `collapseField`, `collapseValues`).
3. The `SearchHit[]` array header and reference slots inside each `SearchHits`.

This is inconsistent with `ramBytesUsedByFields()`, which correctly charges `HASH_MAP_SHALLOW_SIZE + NUM_BYTES_ARRAY_HEADER + N * (HASH_MAP_NODE_SIZE + NUM_BYTES_OBJECT_REF)` for the `documentFields` and `metaFields` maps. For queries using `has_child`, `nested`, or `collapse` with `inner_hits`, the omission grows linearly with the number of inner-hit buckets.

**Recommendation.** Apply the same three-layer accounting used for `documentFields`:

```java
if (innerHits != null) {
    size += HASH_MAP_SHALLOW_SIZE + RamUsageEstimator.NUM_BYTES_ARRAY_HEADER
        + (long) innerHits.size() * (HASH_MAP_NODE_SIZE + RamUsageEstimator.NUM_BYTES_OBJECT_REF);
    for (Map.Entry<String, SearchHits> e : innerHits.entrySet()) {
        size += RamUsageEstimator.sizeOf(e.getKey());   // map key (inner-hit name)
        SearchHits sh = e.getValue();
        size += RamUsageEstimator.shallowSizeOfInstance(SearchHits.class)
            + RamUsageEstimator.NUM_BYTES_ARRAY_HEADER
            + (long) sh.getHits().length * RamUsageEstimator.NUM_BYTES_OBJECT_REF;
        for (SearchHit hit : sh.getHits()) { size += hit.ramBytesUsed(); }
    }
}
```

---

### 4. `ramBytesUsedByValues` adds ArrayList overhead for `Collections.emptyList()` singletons
**File:** `DocumentField.java` · lines 141–148  
**Severity:** Low (systematic overcount for the common case)  
**Status:** PLAUSIBLE

`DocumentField(String, List<Object>)` and `DocumentField(String, List<Object>, List<Object>)` both use `Collections.emptyList()` as the default for `ignoredValues` (and `lookupFields`). `Collections.emptyList()` is a JVM singleton — no ArrayList object, no backing array. Yet `ramBytesUsedByValues()` unconditionally adds:

```java
LIST_SHALLOW_SIZE                         // ~40 bytes (shallowSizeOfInstance(ArrayList.class))
+ RamUsageEstimator.NUM_BYTES_ARRAY_HEADER // 16 bytes
+ 0 * NUM_BYTES_OBJECT_REF                 // 0
```

≈ 56 bytes of phantom overhead per field for the singleton's "ignoredValues". For a fetch returning 1 000 hits × 1 000 fields each, this is ~56 MB of inflated estimate.

**Recommendation.** Guard with an identity check:

```java
private static long ramBytesUsedByValues(List<Object> values) {
    if (values == Collections.emptyList()) return 0L;  // shared singleton — zero per-instance cost
    // ... existing logic ...
}
```

---

### 5. `LookupField` internals not walked — only shallow size charged
**File:** `DocumentField.java` · lines 136–138  
**Severity:** Low  
**Status:** PLAUSIBLE

```java
size += LIST_SHALLOW_SIZE + RamUsageEstimator.NUM_BYTES_ARRAY_HEADER + (long) lookupFields.size()
    * (RamUsageEstimator.NUM_BYTES_OBJECT_REF + LOOKUP_FIELD_SHALLOW_SIZE);
```

`LOOKUP_FIELD_SHALLOW_SIZE` is `shallowSizeOfInstance(LookupField.class)` — the record's header and reference slots only. `LookupField` contains: a `targetIndex` `String`, a `query` `QueryBuilder` (potentially a large composite tree), a `List<FieldAndFormat>` for `fetchFields`, and an `int size`. None of these are walked. For lookup runtime fields with non-trivial queries, the estimate is a significant undercount.

**Recommendation.** Add at minimum `RamUsageEstimator.sizeOf(lf.targetIndex())` per lookup field. For fuller coverage, consider a `ramBytesUsed()` method on `LookupField` itself.

---

### 6. `BOXED_PRIMITIVE_SIZE` overcounts cached JVM singleton primitives
**File:** `DocumentField.java` · lines 160–167  
**Severity:** Minor  
**Status:** PLAUSIBLE

`Boolean.TRUE`, `Boolean.FALSE`, and all `Byte`, `Short`, and `Character` instances (and `Integer` from −128 to 127) are JVM-interned singletons — their marginal heap cost per occurrence is zero. `estimateValueRamBytes()` charges `BOXED_PRIMITIVE_SIZE` (≈ 16 bytes from `shallowSizeOfInstance(Long.class)`) for all of them. For a field returning only `true`/`false` across many hits this inflates the estimate by ~16 bytes per value per hit, though it is safe for circuit-breaker purposes (overcounting prevents under-protection).

**Recommendation.** If accuracy is important, handle `Boolean` and `Byte` separately and return 0 for them (they are always cached). For `Integer`, a conditional check (`((Integer) value) >= -128 && ... <= 127`) would cover the cached range.

---

### 7. `alignObjectSize` applied to an aggregate sum, not a single object
**File:** `DocumentField.java` · line 147  
**Severity:** Minor (structural correctness concern, small practical impact)  
**Status:** PLAUSIBLE

`RamUsageEstimator.alignObjectSize()` pads the byte count of a **single Java object** to the next 8-byte boundary (standard JVM object alignment). Calling it on the aggregate `LIST_SHALLOW_SIZE + NUM_BYTES_ARRAY_HEADER + N * ref_size + sum_of_element_sizes` mixes two distinct objects (the ArrayList wrapper and its backing array) plus element contributions into a single alignment call. The result is semantically undefined and inflates reported bytes by up to 7 bytes per `ramBytesUsedByValues` call.

The correct pattern would apply alignment separately to each sub-object size before summing:

```java
// Align the backing array size, not the composite
long arraySize = RamUsageEstimator.alignObjectSize(
    RamUsageEstimator.NUM_BYTES_ARRAY_HEADER + (long) values.size() * RamUsageEstimator.NUM_BYTES_OBJECT_REF);
long size = LIST_SHALLOW_SIZE + arraySize;
for (Object value : values) { size += estimateValueRamBytes(value); }
return size;
```

---

## Summary Table

| # | File | Line | Severity | Status | Action |
|---|------|------|----------|--------|--------|
| 1 | `FetchPhaseResponseStream.java` | 103 | Medium | PLAUSIBLE | Add `finally` block to drain `pending` on non-success |
| 2 | `TransportFetchPhaseCoordinationAction.java` | 236 | Low–Med | PLAUSIBLE | Mirror `writeChunk` pattern: buffer locally, check CB, then queue |
| 3 | `SearchHit.java` | 325 | Low | CONFIRMED | Account for `innerHits` map, `SearchHits` wrappers, and arrays |
| 4 | `DocumentField.java` | 141 | Low | PLAUSIBLE | Guard `ramBytesUsedByValues` with identity check for `emptyList()` |
| 5 | `DocumentField.java` | 136 | Low | PLAUSIBLE | Walk `LookupField` string and field list contents |
| 6 | `DocumentField.java` | 160 | Minor | PLAUSIBLE | Treat cached JVM singleton primitives as zero-cost |
| 7 | `DocumentField.java` | 147 | Minor | PLAUSIBLE | Apply `alignObjectSize` per sub-object, not to the composite |

---

## Items Investigated but Refuted

- **Field name double-counted between `HASH_MAP_NODE_SIZE` and `DocumentField.ramBytesUsed()`**: `HASH_MAP_NODE_SIZE` counts only the 4–8 byte reference slot, not the String's heap content. `sizeOf(name)` counts the String object itself. No overlap. **Refuted.**
- **`source.length()` should be `source.ramBytesUsed()`**: `source.length()` is deliberately correct. On a pooled network buffer, `ramBytesUsed()` would return the size of the full shared page (e.g. 32 KB), massively over-counting this hit's share. `length()` charges exactly the logical bytes the hit owns. **Refuted.**
- **`trackBreakerBytes(int → long)` call-site breakage**: Java widening from `int` to `long` is implicit and lossless. The single production caller already declares `long`. No breakage. **Refuted.**
