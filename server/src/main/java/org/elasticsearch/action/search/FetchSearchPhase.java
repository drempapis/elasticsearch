/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.action.search;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TotalHits;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.util.concurrent.AtomicArray;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.SearchPhaseResult;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.dfs.AggregatedDfs;
import org.elasticsearch.search.fetch.FetchSearchResult;
import org.elasticsearch.search.fetch.ShardFetchSearchRequest;
import org.elasticsearch.search.fetch.stream.ChunkedShardFetchRequest;
import org.elasticsearch.search.fetch.stream.ChunkedShardFetchResponse;
import org.elasticsearch.search.fetch.stream.TransportShardFetchAction;
import org.elasticsearch.search.internal.ShardSearchContextId;
import org.elasticsearch.search.rank.RankDoc;
import org.elasticsearch.search.rank.RankDocShardInfo;
import org.elasticsearch.transport.Transport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This search phase merges the query results from the previous phase together and calculates the topN hits for this search.
 * Then it reaches out to all relevant shards to fetch the topN hits.
 */

class FetchSearchPhase extends SearchPhase {
    static final String NAME = "fetch";

    private static final ByteSizeValue DEFAULT_FETCH_CHUNK_SIZE = ByteSizeValue.ofMb(4);

    private final AtomicArray<SearchPhaseResult> searchPhaseShardResults;
    private final AbstractSearchAsyncAction<?> context;
    private final Logger logger;
    private final SearchProgressListener progressListener;
    private final AggregatedDfs aggregatedDfs;
    @Nullable
    private final SearchPhaseResults<SearchPhaseResult> resultConsumer;
    private final SearchPhaseController.ReducedQueryPhase reducedQueryPhase;

    private final TransportShardFetchAction chunkedFetchAction;

    FetchSearchPhase(
        SearchPhaseResults<SearchPhaseResult> resultConsumer,
        AggregatedDfs aggregatedDfs,
        AbstractSearchAsyncAction<?> context,
        @Nullable SearchPhaseController.ReducedQueryPhase reducedQueryPhase,
        @Nullable TransportShardFetchAction chunkedFetchAction
    ) {
        super(NAME);
        if (context.getNumShards() != resultConsumer.getNumShards()) {
            throw new IllegalStateException(
                "number of shards must match the length of the query results but doesn't:"
                    + context.getNumShards()
                    + "!="
                    + resultConsumer.getNumShards()
            );
        }
        this.searchPhaseShardResults = resultConsumer.getAtomicArray();
        this.aggregatedDfs = aggregatedDfs;
        this.context = context;
        this.logger = context.getLogger();
        this.progressListener = context.getTask().getProgressListener();
        this.reducedQueryPhase = reducedQueryPhase;
        this.resultConsumer = reducedQueryPhase == null ? resultConsumer : null;
        this.chunkedFetchAction = chunkedFetchAction;
    }

    // protected for tests
    protected SearchPhase nextPhase(SearchResponseSections searchResponseSections, AtomicArray<SearchPhaseResult> queryPhaseResults) {
        return new ExpandSearchPhase(context, searchResponseSections, queryPhaseResults);
    }

    @Override
    protected void run() {
        context.execute(new AbstractRunnable() {

            @Override
            protected void doRun() throws Exception {
                innerRun();
            }

            @Override
            public void onFailure(Exception e) {
                context.onPhaseFailure(NAME, "", e);
            }
        });
    }

    private void innerRun() throws Exception {
        assert this.reducedQueryPhase == null ^ this.resultConsumer == null;
        long phaseStartTimeInNanos = System.nanoTime();
        // depending on whether we executed the RankFeaturePhase we may or may not have the reduced query result computed already
        final var reducedQueryPhase = this.reducedQueryPhase == null ? resultConsumer.reduce() : this.reducedQueryPhase;
        final int numShards = context.getNumShards();
        // Usually when there is a single shard, we force the search type QUERY_THEN_FETCH. But when there's kNN, we might
        // still use DFS_QUERY_THEN_FETCH, which does not perform the "query and fetch" optimization during the query phase.
        boolean queryAndFetchOptimization = numShards == 1
            && context.getRequest().hasKnnSearch() == false
            && reducedQueryPhase.queryPhaseRankCoordinatorContext() == null
            && (context.getRequest().source() == null || context.getRequest().source().rankBuilder() == null);
        if (queryAndFetchOptimization) {
            assert assertConsistentWithQueryAndFetchOptimization();
            // query AND fetch optimization
            moveToNextPhase(searchPhaseShardResults, reducedQueryPhase, phaseStartTimeInNanos);
        } else {
            ScoreDoc[] scoreDocs = reducedQueryPhase.sortedTopDocs().scoreDocs();
            // no docs to fetch -- sidestep everything and return
            if (scoreDocs.length == 0) {
                // we have to release contexts here to free up resources
                searchPhaseShardResults.asList()
                    .forEach(searchPhaseShardResult -> releaseIrrelevantSearchContext(searchPhaseShardResult, context));
                moveToNextPhase(new AtomicArray<>(0), reducedQueryPhase, phaseStartTimeInNanos);
            } else {
                innerRunFetch(scoreDocs, numShards, reducedQueryPhase, phaseStartTimeInNanos);
            }
        }
    }

    private void innerRunFetch(
        ScoreDoc[] scoreDocs,
        int numShards,
        SearchPhaseController.ReducedQueryPhase reducedQueryPhase,
        long phaseStartTimeInNanos
    ) {
        ArraySearchPhaseResults<FetchSearchResult> fetchResults = new ArraySearchPhaseResults<>(numShards);
        final List<Map<Integer, RankDoc>> rankDocsPerShard = false == shouldExplainRankScores(context.getRequest())
            ? null
            : splitRankDocsPerShard(scoreDocs, numShards);
        final ScoreDoc[] lastEmittedDocPerShard = context.getRequest().scroll() != null
            ? SearchPhaseController.getLastEmittedDocPerShard(reducedQueryPhase, numShards)
            : null;
        final List<Integer>[] docIdsToLoad = SearchPhaseController.fillDocIdsToLoad(numShards, scoreDocs);
        final CountedCollector<FetchSearchResult> counter = new CountedCollector<>(
            fetchResults,
            docIdsToLoad.length, // we count down every shard in the result no matter if we got any results or not
            () -> {
                try (fetchResults) {
                    moveToNextPhase(fetchResults.getAtomicArray(), reducedQueryPhase, phaseStartTimeInNanos);
                }
            },
            context
        );
        for (int i = 0; i < docIdsToLoad.length; i++) {
            List<Integer> entry = docIdsToLoad[i];
            SearchPhaseResult shardPhaseResult = searchPhaseShardResults.get(i);
            if (entry == null) { // no results for this shard ID
                // if we got some hits from this shard we have to release the context
                // we do this below after sending out the fetch requests relevant to the search to give priority to those requests
                // that contribute to the final search response
                // in any case we count down this result since we don't talk to this shard anymore
                if (shardPhaseResult != null) {
                    // notifying the listener here as otherwise the search operation might finish before we
                    // get a chance to notify the progress listener for some fetch results
                    progressListener.notifyFetchResult(i);
                }
                counter.countDown();
            } else {
                executeFetch(
                    shardPhaseResult,
                    counter,
                    entry,
                    rankDocsPerShard == null || rankDocsPerShard.get(i).isEmpty() ? null : new RankDocShardInfo(rankDocsPerShard.get(i)),
                    (lastEmittedDocPerShard != null) ? lastEmittedDocPerShard[i] : null
                );
            }
        }
        for (int i = 0; i < docIdsToLoad.length; i++) {
            if (docIdsToLoad[i] == null) {
                SearchPhaseResult shardPhaseResult = searchPhaseShardResults.get(i);
                if (shardPhaseResult != null) {
                    releaseIrrelevantSearchContext(shardPhaseResult, context);
                }
            }
        }
    }

    private List<Map<Integer, RankDoc>> splitRankDocsPerShard(ScoreDoc[] scoreDocs, int numShards) {
        List<Map<Integer, RankDoc>> rankDocsPerShard = new ArrayList<>(numShards);
        for (int i = 0; i < numShards; i++) {
            rankDocsPerShard.add(new HashMap<>());
        }
        for (ScoreDoc scoreDoc : scoreDocs) {
            assert scoreDoc instanceof RankDoc : "ScoreDoc is not a RankDoc";
            assert scoreDoc.shardIndex >= 0 && scoreDoc.shardIndex <= numShards;
            RankDoc rankDoc = (RankDoc) scoreDoc;
            Map<Integer, RankDoc> shardScoreDocs = rankDocsPerShard.get(rankDoc.shardIndex);
            shardScoreDocs.put(rankDoc.doc, rankDoc);
        }
        return rankDocsPerShard;
    }

    private boolean assertConsistentWithQueryAndFetchOptimization() {
        var phaseResults = searchPhaseShardResults.asList();
        assert phaseResults.isEmpty() || phaseResults.get(0).fetchResult() != null
            : "phaseResults empty [" + phaseResults.isEmpty() + "], single result: " + phaseResults.get(0).fetchResult();
        return true;
    }

    private void executeFetch(
        SearchPhaseResult shardPhaseResult,
        final CountedCollector<FetchSearchResult> counter,
        final List<Integer> entry,
        final RankDocShardInfo rankDocs,
        ScoreDoc lastEmittedDocForShard
    ) {
        final SearchShardTarget shardTarget = shardPhaseResult.getSearchShardTarget();
        final int shardIndex = shardPhaseResult.getShardIndex();
        final ShardSearchContextId contextId = shardPhaseResult.queryResult() != null
            ? shardPhaseResult.queryResult().getContextId()
            : shardPhaseResult.rankFeatureResult().getContextId();

        final Transport.Connection connection;
        try {
            connection = context.getConnection(shardTarget.getClusterAlias(), shardTarget.getNodeId());
        } catch (Exception e) {
            logger.debug(() -> "[" + contextId + "] failed to get connection for fetch phase", e);
            progressListener.notifyFetchFailure(shardIndex, shardTarget, e);
            counter.onFailure(shardIndex, shardTarget, e);
            releaseIrrelevantSearchContext(shardPhaseResult, context);
            return;
        }

        if (chunkedFetchAction == null) {
            var listener = new SearchActionListener<FetchSearchResult>(shardTarget, shardIndex) {
                @Override
                public void innerOnResponse(FetchSearchResult result) {
                    try {
                        progressListener.notifyFetchResult(shardIndex);
                        counter.onResult(result);
                    } catch (Exception e) {
                        context.onPhaseFailure(NAME, "", e);
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    try {
                        logger.debug(() -> "[" + contextId + "] Failed to execute fetch phase", e);
                        progressListener.notifyFetchFailure(shardIndex, shardTarget, e);
                        counter.onFailure(shardIndex, shardTarget, e);
                    } finally {
                        // see comment in original code – ensure we clear the search context if needed
                        releaseIrrelevantSearchContext(shardPhaseResult, context);
                    }
                }
            };

            context.getSearchTransport()
                .sendExecuteFetch(
                    connection,
                    new ShardFetchSearchRequest(
                        context.getOriginalIndices(shardPhaseResult.getShardIndex()),
                        contextId,
                        shardPhaseResult.getShardSearchRequest(),
                        entry,
                        rankDocs,
                        lastEmittedDocForShard,
                        shardPhaseResult.getRescoreDocIds(),
                        aggregatedDfs
                    ),
                    context.getTask(),
                    listener
                );
            return;
        }

        // Chunked fetch behavior
        final ShardFetchSearchRequest shardFetchRequest = new ShardFetchSearchRequest(
            context.getOriginalIndices(shardPhaseResult.getShardIndex()),
            contextId,
            shardPhaseResult.getShardSearchRequest(),
            entry,
            rankDocs,
            lastEmittedDocForShard,
            shardPhaseResult.getRescoreDocIds(),
            aggregatedDfs
        );

        // Create listener that handles the final result
        var finalListener = new SearchActionListener<FetchSearchResult>(shardTarget, shardIndex) {
            @Override
            public void innerOnResponse(FetchSearchResult result) {
                try {
                    progressListener.notifyFetchResult(shardIndex);
                    counter.onResult(result);
                } catch (Exception e) {
                    context.onPhaseFailure(NAME, "", e);
                }
            }

            @Override
            public void onFailure(Exception e) {
                try {
                    logger.debug(() -> "[" + contextId + "] Failed to execute chunked fetch phase", e);
                    progressListener.notifyFetchFailure(shardIndex, shardTarget, e);
                    counter.onFailure(shardIndex, shardTarget, e);
                } finally {
                    releaseIrrelevantSearchContext(shardPhaseResult, context);
                }
            }
        };

        // Create the aggregator
        final ChunkedFetchAggregator aggregator = new ChunkedFetchAggregator(finalListener);

        // Start chunked fetching
        ChunkedShardFetchRequest firstRequest = new ChunkedShardFetchRequest(
            shardFetchRequest,
            null, // no continuation token for first request
            DEFAULT_FETCH_CHUNK_SIZE
        );

        sendNextChunk(connection, firstRequest, aggregator);
    }

    private void sendNextChunk(
        Transport.Connection connection,
        ChunkedShardFetchRequest request,
        ChunkedFetchAggregator aggregator
    ) {
        chunkedFetchAction.execute(
            connection,
            request,
            context.getTask(),
            new ActionListener<ChunkedShardFetchResponse>() {
                @Override
                public void onResponse(ChunkedShardFetchResponse response) {
                    try {
                        if (aggregator.canAcceptMore() == false) {
                            return;
                        }

                        aggregator.onChunk(response);

                        if (response.hasMore() && aggregator.canAcceptMore()) {
                            // Request next chunk
                            ChunkedShardFetchRequest nextRequest = new ChunkedShardFetchRequest(
                                request.getShardFetchRequest(),
                                response.getContinuationToken(),
                                request.getChunkSize(),
                                request.getChunkIndex() + 1
                            );
                            sendNextChunk(connection, nextRequest, aggregator);
                        } else {
                            // No more chunks or hit limit - complete
                            aggregator.complete();
                        }
                    } catch (Exception e) {
                        aggregator.onFailure(e);
                    } finally {
                        FetchSearchResult fetchResult = response.getFetchResult();
                        if (fetchResult != null) {
                            // Use the correct release mechanism for your ES version:
                            // e.g. fetchResult.decRef() or Releasables.close(fetchResult) / fetchResult.release()
                            fetchResult.decRef(); // or appropriate close
                        }
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    aggregator.onFailure(e);
                }
            }
        );
    }

    private void moveToNextPhase(
        AtomicArray<? extends SearchPhaseResult> fetchResultsArr,
        SearchPhaseController.ReducedQueryPhase reducedQueryPhase,
        long phaseStartTimeInNanos
    ) {
        context.getSearchResponseMetrics()
            .recordSearchPhaseDuration(getName(), System.nanoTime() - phaseStartTimeInNanos, context.getSearchRequestAttributes());
        context.executeNextPhase(NAME, () -> {
            var resp = SearchPhaseController.merge(context.getRequest().scroll() != null, reducedQueryPhase, fetchResultsArr);
            context.addReleasable(resp);
            return nextPhase(resp, searchPhaseShardResults);
        });
    }

    private boolean shouldExplainRankScores(SearchRequest request) {
        return request.source() != null
            && request.source().explain() != null
            && request.source().explain()
            && request.source().rankBuilder() != null;
    }


    private class ChunkedFetchAggregator {

        private final ActionListener<FetchSearchResult> listener;
        private final List<SearchHit[]> accumulatedHits = new CopyOnWriteArrayList<>();
        private final List<FetchSearchResult> intermediateResults = new CopyOnWriteArrayList<>();
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private volatile FetchSearchResult firstChunkResult;

        ChunkedFetchAggregator(ActionListener<FetchSearchResult> listener) {
            this.listener = listener;
        }

        void onChunk(ChunkedShardFetchResponse response) {
            if (completed.get()) {
                // Already completed - clean up this response
                FetchSearchResult fetchResult = response.getFetchResult();
                if (fetchResult != null) {
                    fetchResult.decRef();
                }
                return;
            }

            FetchSearchResult fetchResult = response.getFetchResult();
            if (fetchResult != null) {
                // Track this result for cleanup
                intermediateResults.add(fetchResult);

                if (firstChunkResult == null) {
                    firstChunkResult = fetchResult;
                    // Keep an extra reference since we're using it as the base
                    firstChunkResult.incRef();
                }
            }

            SearchHit[] hits = response.getHits();
            if (hits != null && hits.length > 0) {
                // Increment ref count on each hit since we're storing them
                for (SearchHit hit : hits) {
                    hit.incRef();
                }
                accumulatedHits.add(hits);
            }

            if (response.isLastChunk()) {
                complete();
            }
        }

        boolean canAcceptMore() {
            return completed.get() == false;
        }

        void complete() {
            if (completed.compareAndSet(false, true) == false) {
                return;
            }

            try {
                if (firstChunkResult == null) {
                    cleanupAll();
                    listener.onFailure(
                        new IllegalStateException("No base FetchSearchResult received for chunked fetch")
                    );
                    return;
                }

                mergeAccumulatedHitsIntoBaseResult(firstChunkResult);

                // Clean up intermediate results except the one we're returning
                cleanupIntermediateResultsExcept(firstChunkResult);

                // Pass the result to the listener
                listener.onResponse(firstChunkResult);

                // Decrement our extra reference (listener now owns it)
                firstChunkResult.decRef();
            } catch (Exception e) {
                cleanupAll();
                listener.onFailure(e);
            }
        }

        void onFailure(Exception e) {
            if (completed.compareAndSet(false, true)) {
                cleanupAll();
                listener.onFailure(e);
            }
        }

        private void cleanupAll() {
            // Clean up all intermediate FetchSearchResults
            for (FetchSearchResult result : intermediateResults) {
                try {
                    result.decRef();
                } catch (Exception ex) {
                    logger.warn("Failed to release intermediate fetch result", ex);
                }
            }
            intermediateResults.clear();

            // Clean up all accumulated hits
            for (SearchHit[] chunk : accumulatedHits) {
                for (SearchHit hit : chunk) {
                    try {
                        hit.decRef();
                    } catch (Exception ex) {
                        logger.warn("Failed to release search hit", ex);
                    }
                }
            }
            accumulatedHits.clear();
        }

        private void cleanupIntermediateResultsExcept(FetchSearchResult keep) {
            // Clean up all intermediate results except the one we're keeping
            for (FetchSearchResult result : intermediateResults) {
                if (result != keep) {
                    try {
                        result.decRef();
                    } catch (Exception ex) {
                        logger.warn("Failed to release intermediate fetch result", ex);
                    }
                }
            }
            intermediateResults.clear();

            for (SearchHit[] chunk : accumulatedHits) {
                for (SearchHit hit : chunk) {
                    try {
                        hit.decRef();
                    } catch (Exception ex) {
                        logger.warn("Failed to release search hit", ex);
                    }
                }
            }
            accumulatedHits.clear();
        }

        private void mergeAccumulatedHitsIntoBaseResult(FetchSearchResult result) {
            SearchHits originalHits = result.hits();
            final TotalHits totalHits;
            final float maxScore;

            if (originalHits != null) {
                totalHits = originalHits.getTotalHits();
                maxScore = originalHits.getMaxScore();
            } else {
                totalHits = null;
                maxScore = Float.NaN;
            }

            int totalHitCount = accumulatedHits.stream().mapToInt(h -> h.length).sum();

            // If no accumulated hits, don't replace anything
            if (totalHitCount == 0) {
                return;
            }

            // Build merged array
            SearchHit[] merged = new SearchHit[totalHitCount];
            int pos = 0;
            for (SearchHit[] chunk : accumulatedHits) {
                System.arraycopy(chunk, 0, merged, pos, chunk.length);
                pos += chunk.length;
            }

            // Create new SearchHits with merged array
            // The SearchHit objects already have their ref counts incremented from onChunk
            SearchHits mergedHits = new SearchHits(merged, totalHits, maxScore);

            // Replace the hits in the result
            // The FetchSearchResult will handle cleanup of the old SearchHits
            result.setHits(mergedHits);
        }
    }

}
