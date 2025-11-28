/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.fetch.stream;

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.fetch.FetchSearchResult;

import java.io.IOException;

public class ChunkedShardFetchResponse extends ActionResponse {

    @SuppressWarnings("rawtypes")
    private static final SearchHit[] EMPTY_HITS = new SearchHit[0];

    /**
     * Base FetchSearchResult created on the shard.
     * This is non-null on the first chunk, and typically null on subsequent chunks.
     */
    private final FetchSearchResult fetchResult;

    /**
     * Hits contained in this chunk.
     */
    private final SearchHit[] hits;

    /**
     * Whether there are more chunks to come for this shard.
     */
    private final boolean hasMore;

    /**
     * Continuation token to request the next chunk.
     */
    private final String continuationToken;

    /**
     * 0-based index of this chunk for this shard.
     */
    private final int chunkNumber;

    /**
     * Total number of chunks for this shard (optional / best-effort).
     */
    private final int totalChunks;

    public static ChunkedShardFetchResponse empty() {
        return new ChunkedShardFetchResponse(
            null,
            EMPTY_HITS,
            false,
            null,
            0,
            0
        );
    }

    public ChunkedShardFetchResponse(
        FetchSearchResult fetchResult, // may be null except for first chunk
        SearchHit[] hits,
        boolean hasMore,
        String continuationToken,
        int chunkNumber,
        int totalChunks
    ) {
        this.fetchResult = fetchResult;
        this.hits = hits == null ? EMPTY_HITS : hits;
        this.hasMore = hasMore;
        this.continuationToken = continuationToken;
        this.chunkNumber = chunkNumber;
        this.totalChunks = totalChunks;
    }

    public ChunkedShardFetchResponse(StreamInput in) throws IOException {
        // super(in);

        // fetchResult: presence flag + body
        if (in.readBoolean()) {
            this.fetchResult = new FetchSearchResult(in);
        } else {
            this.fetchResult = null;
        }

        // hits
        int hitsLength = in.readVInt();
        this.hits = new SearchHit[hitsLength];
        for (int i = 0; i < hitsLength; i++) {
            this.hits[i] = SearchHit.readFrom(in, false);
        }

        this.hasMore = in.readBoolean();
        this.continuationToken = in.readOptionalString();
        this.chunkNumber = in.readVInt();
        this.totalChunks = in.readVInt();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        //super.writeTo(out);

        // fetchResult
        if (fetchResult != null) {
            out.writeBoolean(true);
            fetchResult.writeTo(out);
        } else {
            out.writeBoolean(false);
        }

        // hits
        out.writeVInt(hits.length);
        for (SearchHit hit : hits) {
            hit.writeTo(out);
        }

        out.writeBoolean(hasMore);
        out.writeOptionalString(continuationToken);
        out.writeVInt(chunkNumber);
        out.writeVInt(totalChunks);
    }

    /** Non-null only on the first chunk for a shard. */
    public FetchSearchResult getFetchResult() {
        return fetchResult;
    }

    public SearchHit[] getHits() {
        return hits;
    }

    public boolean hasMore() {
        return hasMore;
    }

    /** Convenience: last chunk is simply the one with hasMore == false. */
    public boolean isLastChunk() {
        return hasMore == false;
    }

    public String getContinuationToken() {
        return continuationToken;
    }

    public int getChunkNumber() {
        return chunkNumber;
    }

    public int getTotalChunks() {
        return totalChunks;
    }
}
