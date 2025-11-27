/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.fetch.stream;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.search.fetch.ShardFetchRequest;

import java.io.IOException;

/**
 * Request for fetching a chunk of search results from a shard
 */
public class ChunkedShardFetchRequest extends ActionRequest {

    private final ShardFetchRequest shardFetchRequest;
    private final String continuationToken;  // null for initial request
    private final ByteSizeValue chunkSize;
    private final int chunkIndex;  // which chunk to return

    public ChunkedShardFetchRequest(
            ShardFetchRequest shardFetchRequest,
            String continuationToken,
            ByteSizeValue chunkSize
    ) {
        this(shardFetchRequest, continuationToken, chunkSize, 0);
    }

    public ChunkedShardFetchRequest(
            ShardFetchRequest shardFetchRequest,
            String continuationToken,
            ByteSizeValue chunkSize,
            int chunkIndex
    ) {
        this.shardFetchRequest = shardFetchRequest;
        this.continuationToken = continuationToken;
        this.chunkSize = chunkSize;
        this.chunkIndex = chunkIndex;
    }

    public ChunkedShardFetchRequest(StreamInput in) throws IOException {
        super(in);
        this.shardFetchRequest = new ShardFetchRequest(in);
        this.continuationToken = in.readOptionalString();
        this.chunkSize = in.readOptionalWriteable(ByteSizeValue::readFrom);
        this.chunkIndex = in.readVInt();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        shardFetchRequest.writeTo(out);
        out.writeOptionalString(continuationToken);
        out.writeOptionalWriteable(chunkSize);
        out.writeVInt(chunkIndex);
    }

    @Override
    public ActionRequestValidationException validate() {
        return null;
    }

    public ShardFetchRequest getShardFetchRequest() {
        return shardFetchRequest;
    }

    public String getContinuationToken() {
        return continuationToken;
    }

    public ByteSizeValue getChunkSize() {
        return chunkSize;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public boolean isInitialRequest() {
        return continuationToken == null;
    }
}
