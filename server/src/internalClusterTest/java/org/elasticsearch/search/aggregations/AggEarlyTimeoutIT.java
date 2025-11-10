/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.aggregations;

import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.search.aggregations.bucket.terms.IncludeExclude;
import org.elasticsearch.search.internal.ContextIndexSearcher;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.search.aggregations.AggregationBuilders.terms;
import static org.hamcrest.Matchers.is;

@ESIntegTestCase.ClusterScope
public class AggEarlyTimeoutIT extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(RegisterCancellationPlugin.class);
    }

    public void testEarlyTimeoutDuringPrepResultsInTimedOutTrue() {
        final String index = "index";
        final String field = "field";

        String mapping = """
            {
              "properties": {
                "field": { "type": "keyword" }
              }
            }
            """;

        indicesAdmin().prepareCreate(index)
            .setSettings(Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                .build())
            .setMapping(mapping)
            .get();

        BulkRequestBuilder bulk = client().prepareBulk();
        for (int i = 0; i < 200; i++) {
            String v = String.format("v%06d", i);
            bulk.add(client().prepareIndex(index).setSource(Map.of(field, v)));
        }
        assertFalse(bulk.get().hasFailures());
        indicesAdmin().prepareRefresh(index).get();

        SearchResponse resp = client().prepareSearch(index)
            .setQuery(new CancelNowQueryBuilder())
            .addAggregation(
                terms("terms")
                    .field(field)
                    .includeExclude(new IncludeExclude("v[0-9]{6}", null, null, null))
                    .size(5)
            )
            .setTimeout(TimeValue.timeValueSeconds(1))
            .get();

        try {
            assertThat("search should report timed_out: true", resp.isTimedOut(), is(true));
        } finally {
            resp.decRef();
        }
    }

    public static class RegisterCancellationPlugin extends Plugin implements SearchPlugin {
        @Override
        public List<QuerySpec<?>> getQueries() {
            return List.of(new QuerySpec<>(
                CancelNowQueryBuilder.NAME,
                CancelNowQueryBuilder::new,
                CancelNowQueryBuilder::fromXContent
            ));
        }
    }

    public static class CancelNowQueryBuilder extends AbstractQueryBuilder<CancelNowQueryBuilder> {

        public static final String NAME = "cancel_now";

        public CancelNowQueryBuilder() {}

        public CancelNowQueryBuilder(StreamInput in) throws IOException {
            super(in);
        }

        public static CancelNowQueryBuilder fromXContent(XContentParser parser) throws IOException {
            XContentParser.Token t = parser.currentToken();
            if (t == null) {
                t = parser.nextToken();
            }
            if (t == XContentParser.Token.START_OBJECT) {
                parser.skipChildren();
            }
            return new CancelNowQueryBuilder();
        }

        @Override
        protected void doWriteTo(StreamOutput out) {
        }

        @Override
        protected void doXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject(NAME).endObject();
        }

        @Override
        protected Query doToQuery(SearchExecutionContext context)  {
            IndexSearcher s = context.searcher();
            if (s instanceof ContextIndexSearcher cis) {
                cis.addQueryCancellation(cis::throwTimeExceededException);
            }
            return new MatchAllDocsQuery();
        }

        @Override
        protected QueryBuilder doRewrite(QueryRewriteContext ctx) {
            return this;
        }

        @Override
        protected boolean doEquals(CancelNowQueryBuilder other) {
            return true;
        }

        @Override
        protected int doHashCode() {
            return NAME.hashCode();
        }

        @Override
        public String getWriteableName() {
            return NAME;
        }

        @Override
        public TransportVersion getMinimalSupportedVersion() {
            return TransportVersion.zero();
        }
    }
}
