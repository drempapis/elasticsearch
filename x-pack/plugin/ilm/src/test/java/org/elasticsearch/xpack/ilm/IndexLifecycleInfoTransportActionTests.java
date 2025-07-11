/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ilm;

import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.cluster.project.TestProjectResolvers;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.MockUtils;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureResponse;
import org.elasticsearch.xpack.core.ilm.IndexLifecycleFeatureSetUsage;
import org.elasticsearch.xpack.core.ilm.IndexLifecycleFeatureSetUsage.PolicyStats;
import org.elasticsearch.xpack.core.ilm.IndexLifecycleMetadata;
import org.elasticsearch.xpack.core.ilm.LifecyclePolicy;
import org.elasticsearch.xpack.core.ilm.LifecyclePolicyMetadata;
import org.elasticsearch.xpack.core.ilm.LifecycleSettings;
import org.elasticsearch.xpack.core.ilm.OperationMode;
import org.elasticsearch.xpack.core.ilm.Phase;
import org.junit.Before;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;

public class IndexLifecycleInfoTransportActionTests extends ESTestCase {

    private ClusterService clusterService;

    @Before
    public void init() throws Exception {
        clusterService = mock(ClusterService.class);
    }

    public void testAvailable() {
        TransportService transportService = MockUtils.setupTransportServiceWithThreadpoolExecutor();
        IndexLifecycleInfoTransportAction featureSet = new IndexLifecycleInfoTransportAction(transportService, mock(ActionFilters.class));
        assertThat(featureSet.available(), equalTo(true));
    }

    public void testName() {
        TransportService transportService = MockUtils.setupTransportServiceWithThreadpoolExecutor();
        IndexLifecycleInfoTransportAction featureSet = new IndexLifecycleInfoTransportAction(transportService, mock(ActionFilters.class));
        assertThat(featureSet.name(), equalTo("ilm"));
    }

    public void testUsageStats() throws Exception {
        Map<String, String> indexPolicies = new HashMap<>();
        List<LifecyclePolicy> policies = new ArrayList<>();
        String policy1Name = randomAlphaOfLength(10);
        String policy2Name = randomAlphaOfLength(10);
        String policy3Name = randomAlphaOfLength(10);
        indexPolicies.put("index_1", policy1Name);
        indexPolicies.put("index_2", policy1Name);
        indexPolicies.put("index_3", policy1Name);
        indexPolicies.put("index_4", policy1Name);
        indexPolicies.put("index_5", policy3Name);
        LifecyclePolicy policy1 = new LifecyclePolicy(policy1Name, Map.of());
        policies.add(policy1);
        PolicyStats policy1Stats = new PolicyStats(Map.of(), 4);

        Map<String, Phase> phases1 = new HashMap<>();
        LifecyclePolicy policy2 = new LifecyclePolicy(policy2Name, phases1);
        policies.add(policy2);
        PolicyStats policy2Stats = new PolicyStats(Map.of(), 0);

        LifecyclePolicy policy3 = new LifecyclePolicy(policy3Name, Map.of());
        policies.add(policy3);
        PolicyStats policy3Stats = new PolicyStats(Map.of(), 1);

        final var projectId = randomProjectIdOrDefault();
        ClusterState clusterState = buildClusterState(projectId, policies, indexPolicies);
        Mockito.when(clusterService.state()).thenReturn(clusterState);

        ThreadPool threadPool = mock(ThreadPool.class);
        TransportService transportService = MockUtils.setupTransportServiceWithThreadpoolExecutor(threadPool);
        var usageAction = new IndexLifecycleUsageTransportAction(
            transportService,
            null,
            threadPool,
            mock(ActionFilters.class),
            TestProjectResolvers.singleProject(projectId)
        );
        PlainActionFuture<XPackUsageFeatureResponse> future = new PlainActionFuture<>();
        usageAction.localClusterStateOperation(null, null, clusterState, future);
        IndexLifecycleFeatureSetUsage ilmUsage = (IndexLifecycleFeatureSetUsage) future.get().getUsage();
        assertThat(ilmUsage.enabled(), equalTo(true));
        assertThat(ilmUsage.available(), equalTo(true));

        List<PolicyStats> policyStatsList = ilmUsage.getPolicyStats();
        assertThat(policyStatsList.size(), equalTo(policies.size()));
        assertTrue(policyStatsList.contains(policy1Stats));
        assertTrue(policyStatsList.contains(policy2Stats));
        assertTrue(policyStatsList.contains(policy3Stats));

    }

    private ClusterState buildClusterState(
        ProjectId projectId,
        List<LifecyclePolicy> lifecyclePolicies,
        Map<String, String> indexPolicies
    ) {
        Map<String, LifecyclePolicyMetadata> lifecyclePolicyMetadatasMap = lifecyclePolicies.stream()
            .map(p -> new LifecyclePolicyMetadata(p, Map.of(), 1, 0L))
            .collect(Collectors.toMap(LifecyclePolicyMetadata::getName, Function.identity()));
        IndexLifecycleMetadata indexLifecycleMetadata = new IndexLifecycleMetadata(lifecyclePolicyMetadatasMap, OperationMode.RUNNING);

        ProjectMetadata.Builder project = ProjectMetadata.builder(projectId).putCustom(IndexLifecycleMetadata.TYPE, indexLifecycleMetadata);
        indexPolicies.forEach((indexName, policyName) -> {
            Settings indexSettings = indexSettings(IndexVersion.current(), 1, 0).put(LifecycleSettings.LIFECYCLE_NAME, policyName).build();
            IndexMetadata.Builder indexMetadata = IndexMetadata.builder(indexName).settings(indexSettings);
            project.put(indexMetadata);
        });

        return ClusterState.builder(new ClusterName("my_cluster")).putProjectMetadata(project).build();
    }
}
