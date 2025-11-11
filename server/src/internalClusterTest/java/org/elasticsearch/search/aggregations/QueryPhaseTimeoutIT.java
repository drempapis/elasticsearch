/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.aggregations;

import com.carrotsearch.randomizedtesting.annotations.Repeat;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.IncludeExclude;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.hamcrest.ElasticsearchAssertions;
import org.junit.Before;

import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 1)
public class QueryPhaseTimeoutIT extends ESIntegTestCase {

    private static final String INDEX = "test-early-timeout";

    @Before
    public void setupIndex() {
        // A few shards help amplify prep work, but not required.
        Settings idxSettings = Settings.builder()
            .put("index.number_of_shards", 3)
            .put("index.number_of_replicas", 0)
            .build();

        prepareCreate(INDEX)
            .setSettings(idxSettings)
            .setMapping("""
                {
                  "properties": {
                    "category": { "type": "keyword" },
                    "payload":  { "type": "text" }
                  }
                }
                """)
            .get();

        // Low cardinality values to encourage filter-by-filter adaptation.
        for (int i = 0; i < 500; i++) {
            String cat = String.format(Locale.ROOT, "cat_%02d", i % 10);
            indexDoc(INDEX, Integer.toString(i), "category", cat, "payload", "text " + i);
        }
        refresh(INDEX);
        ensureGreen(INDEX);
    }

   // @Repeat(iterations = 30)
    public void testTimeoutDuringCollectorPreparationYieldsTimedOutResponse() {
        SortedSet<BytesRef> includeValues = new TreeSet<>();
        for (int i = 0; i < 40_000; i++) {
            if (i % 5_000 == 0) {
                includeValues.add(new BytesRef(String.format(Locale.ROOT, "cat_%02d", (i / 1000) % 10)));
            } else {
                includeValues.add(new BytesRef("nonexistent_" + i));
            }
        }

        SearchResponse response = client().prepareSearch(INDEX)
            .setQuery(QueryBuilders.matchAllQuery())
            .setTimeout(TimeValue.ZERO)
            .addAggregation(
                AggregationBuilders.terms("by_category")
                    .field("category")
                    .includeExclude(new IncludeExclude(null, null, includeValues, null))
                    .size(100)
            )
            .setSize(0)
            .get();

        response.decRef();

        // Should be HTTP 200 and flagged as timed out, with no shard failures.
        assertThat(response.status().getStatus(), is(200));
        ElasticsearchAssertions.assertNoFailures(response);
        assertTrue("Expected response to be marked as timed out", response.isTimedOut());
        assertThat(response.getAggregations(), notNullValue());
    }
}
