/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.fetch.stream;

import org.apache.lucene.search.TotalHits;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionListenerResponseHandler;
import org.elasticsearch.action.search.SearchShardTask;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportRequestHandler;
import org.elasticsearch.transport.TransportService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shard-level fetch action that implements chunking using the request-back mechanism.
 *
 * Key insight: Instead of buffering all results, we:
 * 1. Execute FetchPhase normally to get all hits
 * 2. Store hits in a temporary cache with a continuation token
 * 3. Return only the first chunk immediately
 * 4. Coordinator requests back subsequent chunks using the token
 * 5. Clean up cache when fetch is complete
 */
public class TransportShardFetchAction {

    public static final String ACTION_NAME = "indices:data/read/search/fetch/shard_streaming";

    private final TransportService transportService;
    private final SearchService searchService;
    private final ChunkCache chunkCache;

    @Inject
    public TransportShardFetchAction(
            TransportService transportService,
            SearchService searchService
    ) {
        this.transportService = transportService;
        this.searchService = searchService;
        this.chunkCache = new ChunkCache();

        transportService.registerRequestHandler(
            ACTION_NAME,
            EsExecutors.DIRECT_EXECUTOR_SERVICE, // TODO get ThreadPool.Names.SEARCH from threadpool
            ChunkedShardFetchRequest::new,
            new ChunkedFetchRequestHandler()
        );
    }

    public void execute(
        Transport.Connection connection,
        ChunkedShardFetchRequest request,
        Task parentTask,
        ActionListener<ChunkedShardFetchResponse> listener
    ) {
        transportService.sendChildRequest(connection, ACTION_NAME, request, parentTask,
            new ActionListenerResponseHandler<>(
                listener,
                ChunkedShardFetchResponse::new,
                EsExecutors.DIRECT_EXECUTOR_SERVICE));
    }

    /**
     * Execute a chunked fetch on a remote node
     */
    public void execute(
            DiscoveryNode node,
            ChunkedShardFetchRequest request,
            ActionListener<ChunkedShardFetchResponse> listener
    ) {
        transportService.sendRequest(
            node,
            ACTION_NAME,
            request,
            new ActionListenerResponseHandler<>(
                listener,
                ChunkedShardFetchResponse::new,
                EsExecutors.DIRECT_EXECUTOR_SERVICE
            )
        );
    }

    /**
     * Handles chunked fetch requests on the data node
     */
    private class ChunkedFetchRequestHandler implements TransportRequestHandler<ChunkedShardFetchRequest> {

        @Override
        public void messageReceived(
                ChunkedShardFetchRequest request,
                TransportChannel channel,
                Task task
        ) throws Exception {

            String continuationToken = request.getContinuationToken();

            if (continuationToken == null) {
                // First request - execute fetch and cache results
                handleInitialFetchRequest(request, channel, task);
            } else {
                // Subsequent request - return next chunk from cache
                handleContinuationRequest(request, continuationToken, channel);
            }
        }

        /**
         * Handle the initial fetch request
         */
        private void handleInitialFetchRequest(
                ChunkedShardFetchRequest request,
                TransportChannel channel,
                Task task
        ) {
            final SearchShardTask shardTask = new SearchShardTask(
                task.getId(),
                task.getType(),
                task.getAction(),
                task.getDescription(),
                task.getParentTaskId(),
                task.headers()
            );

            // Execute the actual fetch using existing SearchService
            searchService.executeFetchPhase(
                request.getShardFetchRequest(),
                shardTask,
                ActionListener.wrap(fetchResult -> {
                    SearchHits searchHits = fetchResult.hits();
                    SearchHit[] allHits = searchHits.getHits();

                    List<SearchHit[]> chunks = createChunks(allHits, request.getChunkSize());

                    if (chunks.isEmpty()) {
                        channel.sendResponse(ChunkedShardFetchResponse.empty());
                        return;
                    }

                    boolean hasMore = chunks.size() > 1;
                    String token = null;
                    if (hasMore) {
                        token = UUID.randomUUID().toString();
                        chunkCache.put(token, chunks);
                    }

                    ChunkedShardFetchResponse response = new ChunkedShardFetchResponse(
                        chunks.getFirst(),
                        hasMore,
                        token,
                        1,
                        chunks.size()
                    );
                    channel.sendResponse(response);
                }, channel::sendResponse)
            );

        }

        /**
         * Handle a continuation request for subsequent chunks
         */
        private void handleContinuationRequest(
                ChunkedShardFetchRequest request,
                String token,
                TransportChannel channel
        ) throws Exception {
            List<SearchHit[]> chunks = chunkCache.get(token);

            if (chunks == null) {
                throw new IllegalStateException("Continuation token not found or expired: " + token);
            }

            // Determine which chunk to return based on how many we've already sent
            // (This is simplified - in reality you'd track chunk index in the token)
            int chunkIndex = request.getChunkIndex();

            if (chunkIndex >= chunks.size()) {
                chunkCache.remove(token);
                channel.sendResponse(ChunkedShardFetchResponse.empty());
                return;
            }

            SearchHit[] chunk = chunks.get(chunkIndex);
            boolean hasMore = chunkIndex < chunks.size() - 1;

            ChunkedShardFetchResponse response = new ChunkedShardFetchResponse(
                chunk,
                hasMore,
                hasMore ? token : null,
                chunkIndex + 1,
                chunks.size()
            );

            if (hasMore == false) {
                chunkCache.remove(token);
            }

            channel.sendResponse(response);
        }

        /**
         * Split hits into chunks of roughly equal size (in bytes)
         */
        private List<SearchHit[]> createChunks(SearchHit[] allHits, ByteSizeValue chunkSize) {
            List<SearchHit[]> chunks = new ArrayList<>();
            List<SearchHit> currentChunk = new ArrayList<>();
            long currentChunkSize = 0;
            long targetChunkSize = chunkSize.getBytes();

            for (SearchHit hit : allHits) {
                long hitSize = estimateHitSize(hit);

                // If adding this hit would exceed chunk size, start new chunk
                if (currentChunk.isEmpty() == false && currentChunkSize + hitSize > targetChunkSize) {
                    chunks.add(currentChunk.toArray(new SearchHit[0]));
                    currentChunk = new ArrayList<>();
                    currentChunkSize = 0;
                }

                currentChunk.add(hit);
                currentChunkSize += hitSize;
            }

            // Add final chunk
            if (currentChunk.isEmpty() == false) {
                chunks.add(currentChunk.toArray(new SearchHit[0]));
            }

            return chunks;
        }

        /**
         * Estimate the size of a SearchHit in bytes
         */
        private long estimateHitSize(SearchHit hit) {
            // Rough estimation:
            // - Source field size
            // - Overhead for hit metadata (~1KB)
            long size = 1024; // Base overhead

            if (hit.hasSource()) {
                size += hit.getSourceRef().length();
            }

            // Add size of stored fields
            if (hit.getFields() != null) {
                for (var field : hit.getFields().values()) {
                    size += field.getName().length();
                    size += estimateFieldValueSize(field.getValue());
                }
            }

            return size;
        }

        private long estimateFieldValueSize(Object value) {
            if (value == null) return 0;
            if (value instanceof String) return ((String) value).length();
            if (value instanceof Number) return 8;
            if (value instanceof Boolean) return 1;
            // ... more type handling
            return 100; // Default estimate
        }
    }

    /**
     * Cache for storing chunks during multi-request fetch
     *
     * Note: In production, this should:
     * 1. Have TTL-based expiration
     * 2. Have size limits
     * 3. Be monitored for memory usage
     * 4. Use off-heap storage for large chunks
     */
    private static class ChunkCache {
        private final Map<String, CachedChunks> cache = new ConcurrentHashMap<>();

        void put(String token, List<SearchHit[]> chunks) {
            cache.put(token, new CachedChunks(chunks, System.currentTimeMillis()));
        }

        List<SearchHit[]> get(String token) {
            CachedChunks cached = cache.get(token);
            if (cached == null) {
                return null;
            }

            // Check if expired (e.g., 5 minute TTL)
            if (System.currentTimeMillis() - cached.timestamp > 300_000) {
                cache.remove(token);
                return null;
            }

            return cached.chunks;
        }

        void remove(String token) {
            cache.remove(token);
        }

        private static class CachedChunks {
            final List<SearchHit[]> chunks;
            final long timestamp;

            CachedChunks(List<SearchHit[]> chunks, long timestamp) {
                this.chunks = chunks;
                this.timestamp = timestamp;
            }
        }
    }
}
