# Code Review — `aggs_rewrite_step` branch

**Branch:** `aggs_rewrite_step`  
**Base:** `main`  
**Commit:** `ee77bd72d75e Update code for query-rewrite`  
**Scope:** `QueryPhase.java` (production) + `QueryPhaseTimeoutTests.java` (tests)

---

## Summary of Changes

The branch adds timeout protection during the query-rewrite phase in `QueryPhase.executeQuery`. Before this change, `AggregationPhase.preProcess` was called before the query rewrite completed, meaning a rewrite timeout would leave aggregations in an undefined state. The fix extracts query rewriting into a dedicated `rewriteUnderTimeout` helper that guards the call to `searchContext.rewrittenQuery()` with the timeout cancellation runnable, and early-returns from `executeQuery` (skipping `preProcess`) if a timeout is detected. Two regression tests are added for the in-sort-order aggregation scenario.

Additionally, there are **unstaged working tree changes** to `QueryPhase.java` that are not yet committed. These changes are substantive and address a real correctness issue introduced by the committed code (see Critical Issues below).

---

## Critical Issues

### 1. Double `getTimeoutCheck` invocation creates independent timeout windows (committed code)

**File:** `QueryPhase.java`

In the committed version, `getTimeoutCheck(searchContext)` is called independently inside both `rewriteUnderTimeout` and `addCollectorsAndSearch`:

```java
// rewriteUnderTimeout (line ~158 in committed HEAD):
private static boolean rewriteUnderTimeout(SearchContext searchContext) {
    final Runnable timeoutRunnable = getTimeoutCheck(searchContext); // startTime_1 = T0
    ...
}

// addCollectorsAndSearch (line ~184 in committed HEAD):
static void addCollectorsAndSearch(SearchContext searchContext, ...) {
    ...
    final Runnable timeoutRunnable = getTimeoutCheck(searchContext); // startTime_2 = T0 + delta
    ...
}
```

`getTimeoutCheck` captures `startTime = searchContext.getRelativeTimeInMillis()` at call time and computes `maxTime = startTime + timeout`. Each call creates an independent Runnable with a different origin. As a result:

- Rewrite phase timeout window: `[T0, T0 + timeout]`
- Search phase timeout window: `[T0 + δ, T0 + δ + timeout]`

The search phase gets a **full fresh timeout** after the rewrite completes, so the entire `executeQuery` can take up to `2 × timeout` in the worst case. This contradicts the intent of the user-specified timeout.

**The unstaged working tree changes fix this** by hoisting `timeoutRunnable` creation to `executeQuery` and passing it as a parameter to both methods, so both phases share a single `maxTime`:

```java
// Fixed (working tree):
final Runnable timeoutRunnable = getTimeoutCheck(searchContext); // computed once at T0
if (rewriteUnderTimeout(searchContext, timeoutRunnable) == false) { return; }
...
addCollectorsAndSearch(searchContext, ..., timeoutRunnable);
```

**Action:** The unstaged working tree changes must be committed. They constitute a correctness fix, not a cleanup.

---

## Significant Issues

### 2. Misleading error message in `rewriteUnderTimeout`

**File:** `QueryPhase.java`, lines 173–179

```java
if (searcher.timeExceeded()) {
    try {
        finalizeAsTimedOutResult(searchContext);
    } catch (Exception e) {
        throw new QueryPhaseExecutionException(searchContext.shardTarget(), "Failed to execute main query", e);
    }
    return false;
}
```

The error message `"Failed to execute main query"` is borrowed from `addCollectorsAndSearch`. However, this code path is entered when `finalizeAsTimedOutResult` fails after a **rewrite-phase** timeout—not during main query execution. A support engineer reading a stack trace with this message would look in the wrong place.

**Action:** Change the message to something like `"Failed to finalize timed-out rewrite result"`.

### 3. Catch scope in `rewriteUnderTimeout` is imprecise

**File:** `QueryPhase.java`, lines 163–167

```java
try {
    searchContext.rewrittenQuery();
} catch (RuntimeException e) {
    throw new QueryPhaseExecutionException(searchContext.shardTarget(), "Failed to rewrite query", e);
}
```

Catching `RuntimeException` is intentionally correct here because `SearchContext.rewrittenQuery()` wraps any `IOException` (from `searcher.rewrite(query)`) in `QueryShardException` before rethrowing. However, this is non-obvious and the implicit dependency on that wrapping behavior deserves a brief comment for future readers, since removing that wrapping upstream would silently allow checked exceptions to escape through the `finally` block.

**Action:** Add a one-line comment explaining why `RuntimeException` is sufficient (because `rewrittenQuery()` itself wraps `IOException` in `QueryShardException`).

---

## Minor Issues

### 4. Typo in test helper method name

**File:** `QueryPhaseTimeoutTests.java`, line 978

```java
void intiPlugins() {          // typo: "intiPlugins" should be "initPlugins"
    super.initPlugins();
}
```

`intiPlugins()` is never called anywhere — `AggregationTestHelper.createDefaultAggregationContext` calls `createAggregationContext` directly; `initPlugins()` from `AggregatorTestCase` is only invoked as `super.initPlugins()` inside this dead method. The method is dead code with a typo, creating confusion about whether initialization is actually performed.

**Action:** Either remove the method (since it's unused) or fix the typo and add a call to it from `createDefaultAggregationContext`.

### 5. Working tree removes existing Javadoc (violates AGENTS.md)

**File:** `QueryPhase.java` (unstaged diff)

The unstaged changes delete the Javadoc from the package-private `addCollectorsAndSearch` overload:

```java
// Removed:
/**
 * In a package-private method so that it can be tested without having to
 * wire everything (mapperService, etc.)
 */
```

Per AGENTS.md: *"Do not remove existing comments from code unless the code is also being removed or the comment has become incorrect."* The method remains package-private for the same testing reason — the comment is still accurate and useful.

**Action:** Retain the Javadoc on the package-private overload.

### 6. Stale commented-out code in test

**File:** `QueryPhaseTimeoutTests.java`, line 621

```java
// final SimilarityService similarityService = new SimilarityService(indexSettings, null, Map.of());
```

This appears to be a leftover from development. It adds noise with no value.

**Action:** Remove the commented-out line (this was pre-existing, but the branch touches this method and could clean it up).

---

## Test Coverage Assessment

### What the tests validate

| Scenario | Test |
|---|---|
| Rewrite timeout, allow partial results | `testRewriteTimeout` (pre-existing) |
| Rewrite timeout, disallow partial results | `testRewriteTimeoutDisallowPartialResults` (pre-existing) |
| Rewrite timeout + in-sort-order agg, allow partial | `testRewriteTimeoutWithInSortOrderAggregation` ✨ new |
| Rewrite timeout + in-sort-order agg, disallow partial | `testRewriteTimeoutWithInSortOrderAggregationDisallowPartialResults` ✨ new |

### What is not tested

1. **Rewrite timeout with regular (non-in-sort-order) aggregations**: The two new tests specifically cover in-sort-order aggregations. A test with `aggregations()` returning a normal `SearchContextAggregations` (with `isInSortOrderExecutionRequired() == false`) after a rewrite timeout would improve confidence that `finalizeAsTimedOutResult` correctly initialises `InternalAggregations.EMPTY` for all aggregation types.

2. **`rewrittenQuery()` throwing a non-timeout `RuntimeException`**: The `catch (RuntimeException e)` branch in `rewriteUnderTimeout` handles non-timeout failures (e.g., a `QueryShardException` from a malformed query), but there is no test that exercises this path.

### Test design observations

- The `inSortOrderAggregations()` helper is well-designed: `factories()` throws `AssertionError` with a clear diagnostic message, making it immediately obvious if `AggregationPhase.preProcess` is called unexpectedly. The `isInSortOrderExecutionRequired()` override is direct rather than delegating to `factories()`, so it avoids the `AssertionError` when the method is evaluated legitimately.
- The use of `TestSearchContext` (rather than `SearchContext`) is necessary and correct because it exposes the `aggregations(SearchContextAggregations)` setter.
- Both new tests correctly use `createSearchContextWithTimeout` for the timeout path, consistent with the existing test patterns.

---

## Code Correctness Summary

The fix is correct in its core approach: guarding `rewrittenQuery()` under the timeout cancellation mechanism and early-returning before `AggregationPhase.preProcess` prevents the aggregation phase from receiving an unresolved query state. The `finally` block correctly removes the cancellation runnable regardless of how `rewrittenQuery()` exits.

The most impactful outstanding issue is the double-`getTimeoutCheck` call (Issue #1), which is already fixed in the working tree but not yet committed. Everything else is a quality and clarity concern.

---

## Recommended Actions (Priority Order)

1. **Commit the working tree changes** to `QueryPhase.java` — they fix the double-timeout-window correctness bug.
2. **Fix the error message** in `rewriteUnderTimeout` from `"Failed to execute main query"` to something describing the rewrite-phase finalization failure.
3. **Add a comment** in `rewriteUnderTimeout` explaining why `catch (RuntimeException e)` is sufficient.
4. **Retain the Javadoc** on `addCollectorsAndSearch` (don't remove it in the working tree commit).
5. **Fix or remove** the `intiPlugins` dead method in `AggregationTestHelper`.
6. **Add a test** for rewrite timeout with a standard (non-in-sort-order) aggregation.
7. **Remove** the stale commented-out `SimilarityService` line (opportunistic cleanup).
