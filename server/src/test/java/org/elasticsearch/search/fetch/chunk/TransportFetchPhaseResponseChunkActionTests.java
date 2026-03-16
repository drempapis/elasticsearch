/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.fetch.chunk;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.action.ActionListenerResponseHandler;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.cluster.node.VersionInformation;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.bytes.ReleasableBytesReference;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.BytesTransportRequest;
import org.elasticsearch.transport.TransportResponseHandler;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;

public class TransportFetchPhaseResponseChunkActionTests extends ESTestCase {

    private static final ShardId TEST_SHARD_ID = new ShardId(new Index("test-index", "test-uuid"), 0);

    private ThreadPool threadPool;
    private MockTransportService transportService;
    private ActiveFetchPhaseTasks activeFetchPhaseTasks;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool(getTestName());
        transportService = MockTransportService.createNewService(
            Settings.EMPTY,
            VersionInformation.CURRENT,
            TransportFetchPhaseCoordinationAction.CHUNKED_FETCH_PHASE,
            threadPool
        );
        transportService.start();
        transportService.acceptIncomingRequests();

        activeFetchPhaseTasks = new ActiveFetchPhaseTasks();
        new TransportFetchPhaseResponseChunkAction(
            transportService,
            activeFetchPhaseTasks,
            new NamedWriteableRegistry(Collections.emptyList())
        );
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
        if (transportService != null) {
            transportService.close();
        }
        if (threadPool != null) {
            ThreadPool.terminate(threadPool, 10, TimeUnit.SECONDS);
        }
    }

    public void testProcessChunkWhenWriteChunkThrowsSendsErrorAndReleasesChunk() throws Exception {
        final long coordinatingTaskId = 123L;
        AtomicReference<FetchPhaseResponseChunk> processedChunk = new AtomicReference<>();

        FetchPhaseResponseStream stream = new FetchPhaseResponseStream(0, 1, new NoopCircuitBreaker("test")) {
            @Override
            void writeChunk(FetchPhaseResponseChunk chunk, Releasable releasable) {
                processedChunk.set(chunk);
                try {
                    chunk.getHits();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                throw new IllegalStateException("simulated writeChunk failure");
            }
        };

        Releasable registration = activeFetchPhaseTasks.registerResponseBuilder(coordinatingTaskId, TEST_SHARD_ID, stream);
        SearchHit originalHit = createHit(7);
        FetchPhaseResponseChunk chunk = null;
        try {
            chunk = new FetchPhaseResponseChunk(System.currentTimeMillis(), TEST_SHARD_ID, serializeHits(originalHit), 1, 0, 1, 0L);

            ReleasableBytesReference wireBytes = chunk.toReleasableBytesReference(coordinatingTaskId);
            PlainActionFuture<ActionResponse.Empty> future = new PlainActionFuture<>();

            transportService.sendRequest(
                transportService.getLocalNode(),
                TransportFetchPhaseResponseChunkAction.ZERO_COPY_ACTION_NAME,
                new BytesTransportRequest(wireBytes, TransportVersion.current()),
                new ActionListenerResponseHandler<>(future, in -> ActionResponse.Empty.INSTANCE, TransportResponseHandler.TRANSPORT_WORKER)
            );

            Exception e = expectThrows(Exception.class, () -> future.actionGet(10, TimeUnit.SECONDS));
            assertThat(e.getMessage(), containsString("simulated writeChunk failure"));

            assertBusy(() -> {
                FetchPhaseResponseChunk seen = processedChunk.get();
                assertNotNull("Chunk should have been processed before failure", seen);
                assertEquals("Chunk should be closed on failure", 0L, seen.getBytesLength());
            });
        } finally {
            if (chunk != null) {
                chunk.close();
            }
            registration.close();
            stream.decRef();
            originalHit.decRef();
        }
    }

    private SearchHit createHit(int id) {
        SearchHit hit = new SearchHit(id);
        hit.sourceRef(new BytesArray("{\"id\":" + id + "}"));
        return hit;
    }

    private BytesReference serializeHits(SearchHit... hits) throws IOException {
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            for (SearchHit hit : hits) {
                hit.writeTo(out);
            }
            return out.bytes();
        }
    }
}
