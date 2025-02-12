/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.cluster.coordination;

import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.common.util.concurrent.TaskExecutionTimeTrackingPerIndexEsThreadPoolExecutor;
import org.elasticsearch.index.Index;

public class SearchIndexTimeTrackingCleanupService implements ClusterStateListener {
    private TaskExecutionTimeTrackingPerIndexEsThreadPoolExecutor executor;

    public SearchIndexTimeTrackingCleanupService(TaskExecutionTimeTrackingPerIndexEsThreadPoolExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        for (Index index : event.indicesDeleted()) {
            executor.stopTrackingIndex(index.getName());
        }
    }
}
