/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.core.ilm;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ProjectState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.Strings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.Index;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xpack.core.ccr.ShardFollowNodeTaskStatus;
import org.elasticsearch.xpack.core.ccr.action.FollowStatsAction;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.xpack.core.ilm.UnfollowAction.CCR_METADATA_KEY;

final class WaitForFollowShardTasksStep extends AsyncWaitStep {

    static final String NAME = "wait-for-follow-shard-tasks";

    WaitForFollowShardTasksStep(StepKey key, StepKey nextStepKey, Client client) {
        super(key, nextStepKey, client);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }

    @Override
    public void evaluateCondition(ProjectState state, Index index, Listener listener, TimeValue masterTimeout) {
        IndexMetadata indexMetadata = state.metadata().index(index);
        Map<String, String> customIndexMetadata = indexMetadata.getCustomData(CCR_METADATA_KEY);
        if (customIndexMetadata == null) {
            listener.onResponse(true, null);
            return;
        }

        FollowStatsAction.StatsRequest request = new FollowStatsAction.StatsRequest();
        request.setIndices(new String[] { index.getName() });
        getClient(state.projectId()).execute(
            FollowStatsAction.INSTANCE,
            request,
            ActionListener.wrap(r -> handleResponse(r, listener), listener::onFailure)
        );
    }

    static void handleResponse(FollowStatsAction.StatsResponses responses, Listener listener) {
        List<ShardFollowNodeTaskStatus> unSyncedShardFollowStatuses = responses.getStatsResponses()
            .stream()
            .map(FollowStatsAction.StatsResponse::status)
            .filter(shardFollowStatus -> shardFollowStatus.leaderGlobalCheckpoint() != shardFollowStatus.followerGlobalCheckpoint())
            .toList();

        // Follow stats api needs to return stats for follower index and all shard follow tasks should be synced:
        boolean conditionMet = responses.getStatsResponses().size() > 0 && unSyncedShardFollowStatuses.isEmpty();
        if (conditionMet) {
            listener.onResponse(true, null);
        } else {
            List<ShardFollowTaskInfo> shardFollowTaskInfos = unSyncedShardFollowStatuses.stream()
                .map(
                    status -> new ShardFollowTaskInfo(
                        status.followerIndex(),
                        status.getShardId(),
                        status.leaderGlobalCheckpoint(),
                        status.followerGlobalCheckpoint()
                    )
                )
                .toList();
            listener.onResponse(false, new Info(shardFollowTaskInfos));
        }
    }

    record Info(List<ShardFollowTaskInfo> shardFollowTaskInfos) implements ToXContentObject {

        static final ParseField SHARD_FOLLOW_TASKS = new ParseField("shard_follow_tasks");
        static final ParseField MESSAGE = new ParseField("message");

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.xContentList(SHARD_FOLLOW_TASKS.getPreferredName(), shardFollowTaskInfos);
            String message;
            if (shardFollowTaskInfos.size() > 0) {
                message = "Waiting for [" + shardFollowTaskInfos.size() + "] shard follow tasks to be in sync";
            } else {
                message = "Waiting for following to be unpaused and all shard follow tasks to be up to date";
            }
            builder.field(MESSAGE.getPreferredName(), message);
            builder.endObject();
            return builder;
        }

        @Override
        public String toString() {
            return Strings.toString(this);
        }
    }

    record ShardFollowTaskInfo(String followerIndex, int shardId, long leaderGlobalCheckpoint, long followerGlobalCheckpoint)
        implements
            ToXContentObject {

        static final ParseField FOLLOWER_INDEX_FIELD = new ParseField("follower_index");
        static final ParseField SHARD_ID_FIELD = new ParseField("shard_id");
        static final ParseField LEADER_GLOBAL_CHECKPOINT_FIELD = new ParseField("leader_global_checkpoint");
        static final ParseField FOLLOWER_GLOBAL_CHECKPOINT_FIELD = new ParseField("follower_global_checkpoint");

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(FOLLOWER_INDEX_FIELD.getPreferredName(), followerIndex);
            builder.field(SHARD_ID_FIELD.getPreferredName(), shardId);
            builder.field(LEADER_GLOBAL_CHECKPOINT_FIELD.getPreferredName(), leaderGlobalCheckpoint);
            builder.field(FOLLOWER_GLOBAL_CHECKPOINT_FIELD.getPreferredName(), followerGlobalCheckpoint);
            builder.endObject();
            return builder;
        }
    }
}
