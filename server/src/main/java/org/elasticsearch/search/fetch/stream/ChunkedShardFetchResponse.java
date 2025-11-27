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
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.search.SearchHit;

import java.io.IOException;

public class ChunkedShardFetchResponse extends ActionResponse {

    private final SearchHit[] hits;
    private final boolean hasMore;
    private final String continuationToken;
    private final int chunkNumber;
    private final int totalChunks;

    public static ChunkedShardFetchResponse empty() {
        return new ChunkedShardFetchResponse(
            new SearchHit[0],
            false,
            null,
            0,
            0
        );
    }

    public ChunkedShardFetchResponse(
        SearchHit[] hits,
        boolean hasMore,
        String continuationToken,
        int chunkNumber,
        int totalChunks
    ) {
        this.hits = hits;
        this.hasMore = hasMore;
        this.continuationToken = continuationToken;
        this.chunkNumber = chunkNumber;
        this.totalChunks = totalChunks;;
    }

    public ChunkedShardFetchResponse(StreamInput in) throws IOException {
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
        out.writeVInt(hits.length);
        for (SearchHit hit : hits) {
            hit.writeTo(out);
        }

        out.writeBoolean(hasMore);
        out.writeOptionalString(continuationToken);
        out.writeVInt(chunkNumber);
        out.writeVInt(totalChunks);
    }

    public SearchHit[] getHits() {
        return hits;
    }

    public boolean hasMore() {
        return hasMore;
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
