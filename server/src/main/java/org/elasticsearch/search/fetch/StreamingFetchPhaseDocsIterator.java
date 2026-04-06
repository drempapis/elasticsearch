/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.fetch;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.RefCountingListener;
import org.elasticsearch.common.bytes.ReleasableBytesReference;
import org.elasticsearch.common.io.stream.RecyclerBytesStreamOutput;
import org.elasticsearch.common.util.concurrent.ThrottledIterator;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.fetch.chunk.FetchPhaseResponseChunk;
import org.elasticsearch.tasks.TaskCancelledException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Extends {@link FetchPhaseDocsIterator} with asynchronous chunked iteration
 * via {@link #iterateAsync}. The synchronous {@link #iterate} method from the
 * parent class remains available for non-streaming use.
 * <p>
 * Uses {@link ThrottledIterator} to process chunks of documents across threads:
 * <ul>
 *   <li>A {@link ChunkProducingIterator} yields serialized chunks by performing Lucene I/O</li>
 *   <li>A send consumer initiates asynchronous network sends for each chunk</li>
 *   <li>The {@link ThrottledIterator} releasable is closed on send ACK, triggering the next chunk</li>
 * </ul>
 * <b>Threading:</b> The search thread produces up to {@code maxInFlightChunks} chunks in the
 * initial burst, then returns to the thread pool. Subsequent chunks are produced on whatever
 * thread ACKs a previous send. At each chunk boundary, per-leaf Lucene readers are re-acquired
 * (clone-per-chunk) via {@link #setNextReader} to satisfy thread-affinity assertions -- each
 * call creates fresh reader clones bound to the calling thread.
 * <p>
 * <b>Memory Management:</b> The circuit breaker tracks recycler page allocations via the
 * {@link RecyclerBytesStreamOutput} passed from the chunk writer. If the breaker trips
 * during serialization, the producer fails immediately with a
 * {@link org.elasticsearch.common.breaker.CircuitBreakingException}, preventing unbounded
 * memory growth. Pages are released (and the breaker decremented) when the
 * {@link ReleasableBytesReference} from {@link RecyclerBytesStreamOutput#moveToBytesReference()}
 * is closed -- either on ACK for intermediate chunks or when the last chunk is consumed.
 * <p>
 * <b>Backpressure:</b> {@link ThrottledIterator}'s {@code maxConcurrency} limits concurrent
 * in-flight sends to {@code maxInFlightChunks}. The circuit breaker provides the memory limit.
 * <p>
 * <b>Cancellation:</b> The producer checks the cancellation flag periodically and in
 * {@link ChunkProducingIterator#hasNext()}.
 */
abstract class StreamingFetchPhaseDocsIterator extends FetchPhaseDocsIterator {

    /**
     * Default target chunk size in bytes (256KB).
     * Chunks may slightly exceed this as we complete the current hit before checking.
     */
    static final int DEFAULT_TARGET_CHUNK_BYTES = 256 * 1024;

    /**
     * Asynchronous iteration using {@link ThrottledIterator} for streaming mode.
     *
     * @param shardTarget         the shard being fetched from
     * @param indexReader         the index reader
     * @param docIds              document IDs to fetch (in score order)
     * @param chunkWriter         writer for sending chunks (also provides buffer allocation with CB tracking)
     * @param targetChunkBytes    target size in bytes for each chunk
     * @param chunkCompletionRefs ref-counting listener for tracking chunk ACKs
     * @param maxInFlightChunks   maximum concurrent unacknowledged chunks
     * @param sendFailure         atomic reference to capture send failures
     * @param isCancelled         supplier for cancellation checking
     * @param continuationExecutor executor for dispatching chunk production after ACKs
     * @param listener            receives the result with the last chunk bytes
     */
    void iterateAsync(
        SearchShardTarget shardTarget,
        IndexReader indexReader,
        int[] docIds,
        FetchPhaseResponseChunk.Writer chunkWriter,
        int targetChunkBytes,
        RefCountingListener chunkCompletionRefs,
        int maxInFlightChunks,
        AtomicReference<Throwable> sendFailure,
        Supplier<Boolean> isCancelled,
        Executor continuationExecutor,
        ActionListener<IterateResult> listener
    ) {
        if (docIds == null || docIds.length == 0) {
            listener.onResponse(new IterateResult(new SearchHit[0]));
            return;
        }

        final AtomicReference<PendingChunk> lastChunkHolder = new AtomicReference<>();
        final AtomicReference<Throwable> producerError = new AtomicReference<>();

        DocIdToIndex[] docs = sortDocsByDocId(docIds);
        Iterator<PendingChunk> chunkIterator = new ChunkProducingIterator(
            docs,
            indexReader,
            chunkWriter,
            targetChunkBytes,
            isCancelled,
            sendFailure,
            producerError);

        BiConsumer<Releasable, PendingChunk> sendConsumer = createSendConsumer(
            chunkWriter,
            shardTarget.getShardId(),
            docIds.length,
            sendFailure,
            chunkCompletionRefs,
            isCancelled,
            lastChunkHolder
        );
        Runnable onCompletion = createCompletionHandler(listener, producerError, sendFailure, isCancelled, lastChunkHolder);

        ThrottledIterator.run(chunkIterator, sendConsumer, maxInFlightChunks, onCompletion, continuationExecutor);
    }

    private static DocIdToIndex[] sortDocsByDocId(int[] docIds) {
        DocIdToIndex[] docs = new DocIdToIndex[docIds.length];
        for (int i = 0; i < docIds.length; i++) {
            docs[i] = new DocIdToIndex(docIds[i], i);
        }
        Arrays.sort(docs);
        return docs;
    }

    private static BiConsumer<Releasable, PendingChunk> createSendConsumer(
        FetchPhaseResponseChunk.Writer chunkWriter,
        ShardId shardId,
        int totalDocs,
        AtomicReference<Throwable> sendFailure,
        RefCountingListener chunkCompletionRefs,
        Supplier<Boolean> isCancelled,
        AtomicReference<PendingChunk> lastChunkHolder
    ) {
        return (releasable, chunk) -> {
            try {
                if (chunk == null) {
                    releasable.close();
                    return;
                }
                if (chunk.isLast) {
                    lastChunkHolder.set(chunk);
                    releasable.close();
                    return;
                }
                sendChunk(chunk, releasable, chunkWriter, shardId, totalDocs, sendFailure, chunkCompletionRefs, isCancelled);
            } catch (Exception e) {
                sendFailure.compareAndSet(null, e);
                if (chunk != null) {
                    chunk.close();
                }
                releasable.close();
            }
        };
    }

    private static Runnable createCompletionHandler(
        ActionListener<IterateResult> listener,
        AtomicReference<Throwable> producerError,
        AtomicReference<Throwable> sendFailure,
        Supplier<Boolean> isCancelled,
        AtomicReference<PendingChunk> lastChunkHolder
    ) {
        return () -> {
            final Throwable pError = producerError.get();
            if (pError != null) {
                cleanupLastChunk(lastChunkHolder);
                listener.onFailure(pError instanceof Exception ? (Exception) pError : new RuntimeException(pError));
                return;
            }

            final Throwable sError = sendFailure.get();
            if (sError != null) {
                cleanupLastChunk(lastChunkHolder);
                listener.onFailure(sError instanceof Exception ? (Exception) sError : new RuntimeException(sError));
                return;
            }

            if (isCancelled.get()) {
                cleanupLastChunk(lastChunkHolder);
                listener.onFailure(new TaskCancelledException("cancelled"));
                return;
            }

            final PendingChunk lastChunk = lastChunkHolder.getAndSet(null);
            if (lastChunk == null) {
                listener.onResponse(new IterateResult(new SearchHit[0]));
                return;
            }

            try {
                listener.onResponse(new IterateResult(lastChunk.bytes, lastChunk.hitCount, lastChunk.sequenceStart));
            } catch (Exception e) {
                lastChunk.close();
                throw e;
            }
        };
    }

    /**
     * Yields serialized chunks by fetching documents from Lucene in doc-ID order.
     * <p>
     * Documents are sorted by doc ID for efficient sequential Lucene access, matching
     * the non-streaming {@link FetchPhaseDocsIterator#iterate} path. Each serialized
     * hit is prefixed with its original score-order position (as a vInt) so the
     * coordinator can reassemble results in the correct order.
     * <p>
     * <b>Clone-per-chunk:</b> At the start of each {@link #next()} call, per-leaf Lucene
     * readers are re-acquired via {@link #setNextReaderAndGetLeafEndIndex}. This creates
     * fresh clones of {@code StoredFields}, {@code DocValues}, etc. bound to the calling
     * thread, satisfying Lucene's thread-affinity assertions when chunks are produced
     * across different threads.
     * <p>
     * <b>Error safety:</b> All exceptions from Lucene I/O are caught and stored in
     * {@code producerError}. This is critical because {@link ThrottledIterator} does not
     * catch exceptions from {@code iterator.next()} -- an uncaught exception in
     * {@code onItemRelease -> run -> next} would propagate unhandled on an ACK thread.
     */
    private class ChunkProducingIterator implements Iterator<PendingChunk> {
        private final DocIdToIndex[] docs;
        private final IndexReader indexReader;
        private final FetchPhaseResponseChunk.Writer chunkWriter;
        private final int targetChunkBytes;
        private final Supplier<Boolean> isCancelled;
        private final AtomicReference<Throwable> sendFailure;
        private final AtomicReference<Throwable> producerError;

        private int currentIdx;
        private int endReaderIdx;

        ChunkProducingIterator(
            DocIdToIndex[] docs,
            IndexReader indexReader,
            FetchPhaseResponseChunk.Writer chunkWriter,
            int targetChunkBytes,
            Supplier<Boolean> isCancelled,
            AtomicReference<Throwable> sendFailure,
            AtomicReference<Throwable> producerError
        ) {
            this.docs = docs;
            this.indexReader = indexReader;
            this.chunkWriter = chunkWriter;
            this.targetChunkBytes = targetChunkBytes;
            this.isCancelled = isCancelled;
            this.sendFailure = sendFailure;
            this.producerError = producerError;
        }

        @Override
        public boolean hasNext() {
            return currentIdx < docs.length
                && producerError.get() == null
                && sendFailure.get() == null
                && isCancelled.get() == false;
        }

        @Override
        public PendingChunk next() {
            RecyclerBytesStreamOutput chunkBuffer = null;
            try {
                endReaderIdx = setNextReaderAndGetLeafEndIndex(indexReader, docs, currentIdx);

                chunkBuffer = chunkWriter.newNetworkBytesStream();
                int chunkStartIndex = currentIdx;
                int hitsInChunk = 0;

                while (currentIdx < docs.length) {
                    if (hitsInChunk > 0 && currentIdx % 32 == 0) {
                        if (isCancelled.get()) {
                            throw new TaskCancelledException("cancelled");
                        }
                        Throwable failure = sendFailure.get();
                        if (failure != null) {
                            throw failure instanceof Exception ? (Exception) failure : new RuntimeException(failure);
                        }
                    }

                    if (currentIdx >= endReaderIdx) {
                        endReaderIdx = setNextReaderAndGetLeafEndIndex(indexReader, docs, currentIdx);
                    }

                    SearchHit hit = nextDoc(docs[currentIdx].docId);
                    try {
                        chunkBuffer.writeVInt(docs[currentIdx].index);
                        hit.writeTo(chunkBuffer);
                    } finally {
                        hit.decRef();
                    }
                    currentIdx++;
                    hitsInChunk++;

                    if (currentIdx == docs.length || chunkBuffer.size() >= targetChunkBytes) {
                        break;
                    }
                }

                ReleasableBytesReference chunkBytes = chunkBuffer.moveToBytesReference();
                chunkBuffer = null;
                boolean isLast = (currentIdx == docs.length);
                return new PendingChunk(chunkBytes, hitsInChunk, chunkStartIndex, isLast);
            } catch (Exception e) {
                producerError.compareAndSet(null, e);
                if (chunkBuffer != null) {
                    Releasables.closeWhileHandlingException(chunkBuffer);
                }
                return null;
            }
        }
    }

    private int setNextReaderAndGetLeafEndIndex(IndexReader indexReader, DocIdToIndex[] docs, int index) throws IOException {
        int leafOrd = ReaderUtil.subIndex(docs[index].docId, indexReader.leaves());
        LeafReaderContext ctx = indexReader.leaves().get(leafOrd);
        int endReaderIdx = endReaderIdx(ctx, index, docs);
        int[] docsInLeaf = docIdsInLeaf(index, endReaderIdx, docs, ctx.docBase);
        setNextReader(ctx, docsInLeaf);
        return endReaderIdx;
    }

    /**
     * Sends a single intermediate chunk. Initiates an asynchronous network write and
     * closes the {@link ThrottledIterator} releasable on ACK (or failure), which triggers
     * production of the next chunk.
     */
    private static void sendChunk(
        PendingChunk chunk,
        Releasable iteratorReleasable,
        FetchPhaseResponseChunk.Writer writer,
        ShardId shardId,
        int totalDocs,
        AtomicReference<Throwable> sendFailure,
        RefCountingListener chunkCompletionRefs,
        Supplier<Boolean> isCancelled
    ) {
        if (isCancelled.get()) {
            chunk.close();
            iteratorReleasable.close();
            return;
        }

        final Throwable failure = sendFailure.get();
        if (failure != null) {
            chunk.close();
            iteratorReleasable.close();
            return;
        }

        FetchPhaseResponseChunk responseChunk = null;
        ActionListener<Void> ackRef = null;
        try {
            responseChunk = new FetchPhaseResponseChunk(shardId, chunk.bytes, chunk.hitCount, totalDocs, chunk.sequenceStart);

            final FetchPhaseResponseChunk chunkToClose = responseChunk;

            ackRef = chunkCompletionRefs.acquire();
            final ActionListener<Void> finalAckRef = ackRef;

            writer.writeResponseChunk(responseChunk, ActionListener.wrap(v -> {
                chunkToClose.close();
                finalAckRef.onResponse(null);
                iteratorReleasable.close();
            }, e -> {
                chunkToClose.close();
                sendFailure.compareAndSet(null, e);
                finalAckRef.onFailure(e);
                iteratorReleasable.close();
            }));

            responseChunk = null;
        } catch (Exception e) {
            if (responseChunk != null) {
                responseChunk.close();
            } else {
                chunk.close();
            }
            sendFailure.compareAndSet(null, e);
            if (ackRef != null) {
                ackRef.onFailure(e);
            }
            iteratorReleasable.close();
        }
    }

    private static void cleanupLastChunk(AtomicReference<PendingChunk> lastChunkHolder) {
        PendingChunk lastChunk = lastChunkHolder.getAndSet(null);
        if (lastChunk != null) {
            lastChunk.close();
        }
    }

    /**
     * Represents a chunk ready to be sent. The underlying {@link ReleasableBytesReference} carries
     * the page-level circuit breaker release callback from {@link RecyclerBytesStreamOutput#moveToBytesReference()}.
     */
    static class PendingChunk implements AutoCloseable {
        final ReleasableBytesReference bytes;
        final int hitCount;
        final int sequenceStart;
        final boolean isLast;

        PendingChunk(ReleasableBytesReference bytes, int hitCount, int sequenceStart, boolean isLast) {
            this.bytes = bytes;
            this.hitCount = hitCount;
            this.sequenceStart = sequenceStart;
            this.isLast = isLast;
        }

        @Override
        public void close() {
            if (bytes != null) {
                Releasables.closeWhileHandlingException(bytes);
            }
        }
    }
}
