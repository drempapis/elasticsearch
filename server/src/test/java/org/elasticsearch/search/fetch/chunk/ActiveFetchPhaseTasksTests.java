/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.fetch.chunk;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ESTestCase;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentMap;

public class ActiveFetchPhaseTasksTests extends ESTestCase {

    private static final ShardId TEST_SHARD_ID = new ShardId(new Index("test-index", "test-uuid"), 0);

    public void testAcquireRegisteredStream() {
        ActiveFetchPhaseTasks tasks = new ActiveFetchPhaseTasks();
        FetchPhaseResponseStream stream = new FetchPhaseResponseStream(0, 10, new NoopCircuitBreaker("test"));
        Releasable registration = tasks.registerResponseBuilder(123L, TEST_SHARD_ID, stream);

        try {
            FetchPhaseResponseStream acquired = tasks.acquireResponseStream(123L, TEST_SHARD_ID);
            assertSame(stream, acquired);
            assertTrue(acquired.hasReferences());
            acquired.decRef();
            registration.close();

            expectThrows(ResourceNotFoundException.class, () -> tasks.acquireResponseStream(123L, TEST_SHARD_ID));
        } finally {
            stream.decRef();
        }
    }

    public void testDuplicateRegisterThrows() {
        ActiveFetchPhaseTasks tasks = new ActiveFetchPhaseTasks();
        FetchPhaseResponseStream first = new FetchPhaseResponseStream(0, 10, new NoopCircuitBreaker("test"));
        FetchPhaseResponseStream second = new FetchPhaseResponseStream(0, 10, new NoopCircuitBreaker("test"));
        Releasable registration = tasks.registerResponseBuilder(123L, TEST_SHARD_ID, first);

        try {
            Exception e = expectThrows(IllegalStateException.class, () -> tasks.registerResponseBuilder(123L, TEST_SHARD_ID, second));
            assertEquals("already executing fetch task [123]", e.getMessage());
        } finally {
            registration.close();
            first.decRef();
            second.decRef();
        }
    }

    public void testCompleteAlreadyCompletedThrows() {
        ActiveFetchPhaseTasks tasks = new ActiveFetchPhaseTasks();
        FetchPhaseResponseStream stream = new FetchPhaseResponseStream(0, 10, new NoopCircuitBreaker("test"));
        Releasable registration = tasks.registerResponseBuilder(123L, TEST_SHARD_ID, stream);

        try {
            ConcurrentMap<ActiveFetchPhaseTasks.ResponseStreamKey, FetchPhaseResponseStream> activeTasks = getActiveTasksMap(tasks);
            boolean removed = activeTasks.remove(new ActiveFetchPhaseTasks.ResponseStreamKey(123L, TEST_SHARD_ID), stream);
            assertTrue("test setup should remove the task once", removed);

            Exception e = expectThrows(IllegalStateException.class, registration::close);
            assertTrue(e.getMessage().contains("already completed fetch task [123]"));
        } finally {
            stream.decRef();
        }
    }

    public void testAcquireMissingTaskThrowsResourceNotFound() {
        ActiveFetchPhaseTasks tasks = new ActiveFetchPhaseTasks();
        expectThrows(ResourceNotFoundException.class, () -> tasks.acquireResponseStream(999L, TEST_SHARD_ID));
    }

    public void testAcquireFailsWhenStreamAlreadyClosed() {
        ActiveFetchPhaseTasks tasks = new ActiveFetchPhaseTasks();
        FetchPhaseResponseStream stream = new FetchPhaseResponseStream(0, 10, new NoopCircuitBreaker("test"));
        Releasable registration = tasks.registerResponseBuilder(123L, TEST_SHARD_ID, stream);
        registration.close();

        try {
            expectThrows(ResourceNotFoundException.class, () -> tasks.acquireResponseStream(123L, TEST_SHARD_ID));
        } finally {
            stream.decRef();
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<ActiveFetchPhaseTasks.ResponseStreamKey, FetchPhaseResponseStream> getActiveTasksMap(
        ActiveFetchPhaseTasks tasks
    ) {
        try {
            Field tasksField = ActiveFetchPhaseTasks.class.getDeclaredField("tasks");
            tasksField.setAccessible(true);
            return (ConcurrentMap<ActiveFetchPhaseTasks.ResponseStreamKey, FetchPhaseResponseStream>) tasksField.get(tasks);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to inspect active fetch phase tasks map", e);
        }
    }
}
