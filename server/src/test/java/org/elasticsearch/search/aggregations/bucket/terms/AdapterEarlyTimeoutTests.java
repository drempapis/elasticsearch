/*
 * Copyright Elasticsearch B.V.
 */

package org.elasticsearch.search.aggregations.bucket.terms;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.index.mapper.DocCountFieldMapper;
import org.elasticsearch.index.mapper.KeywordFieldMapper;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorTestCase;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.internal.ContextIndexSearcher;


import static org.elasticsearch.test.InternalAggregationTestCase.DEFAULT_MAX_BUCKETS;

public class AdapterEarlyTimeoutTests extends AggregatorTestCase {

    /**
     * Tests that the terms aggregator's filter-by-filter adapter properly declines
     * when an early timeout occurs during its initialization phase.
     *
     * Specifically, this test simulates a {@link ContextIndexSearcher.TimeExceededException}
     * being thrown while probing the _doc_count field as part
     * of {@link AggregationContext#hasDocCountField()}. The adapter returns null,
     * causing the terms aggregator factory to fall back to the standard (non-adapted)
     * implementation rather than using {@link StringTermsAggregatorFromFilters}.
     */
    public void testFilterByFilterAdapterDeclinesOnEarlyTimeoutDuringDocFreqProbe() throws Exception {
        final String FIELD = "field";

        try (Directory directory = newDirectory()) {

            // Index two documents with distinct ords so the adapter will add two filters
            // (triggering the _doc_count probe on the second add). The first doc adds
            // the _doc_count postings term so that hasDocCountField() performs
            // a docFreq() lookup where a simulated timeout will occur.
            try (RandomIndexWriter indexWriter = new RandomIndexWriter(random(), directory)) {
                Document doc1 = new Document();
                doc1.add(new SortedSetDocValuesField(FIELD, new BytesRef("v000000")));
                doc1.add(new StringField(DocCountFieldMapper.NAME, DocCountFieldMapper.NAME, Field.Store.NO));
                indexWriter.addDocument(doc1);

                Document doc2 = new Document();
                doc2.add(new SortedSetDocValuesField(FIELD, new BytesRef("v000001")));
                indexWriter.addDocument(doc2);

                indexWriter.commit();
            }

            try (DirectoryReader reader = DirectoryReader.open(directory)) {

                ContextIndexSearcher searcher = new ContextIndexSearcher(
                    reader,
                    IndexSearcher.getDefaultSimilarity(),
                    IndexSearcher.getDefaultQueryCache(),
                    IndexSearcher.getDefaultQueryCachingPolicy(),
                    true
                );
                searcher.addQueryCancellation(searcher::throwTimeExceededException);

                AggregationContext context = createAggregationContext(
                    searcher,
                    createIndexSettings(),
                    new MatchAllDocsQuery(),
                    new NoneCircuitBreakerService(),
                    /* bytesToPreallocate */ 0L,
                    DEFAULT_MAX_BUCKETS,
                    /* inSortOrderExecutionRequired */ false,
                    new KeywordFieldMapper.KeywordFieldType(FIELD)
                );

                try {
                    TermsAggregationBuilder aggBuilder = new TermsAggregationBuilder("termsBuilder")
                        .field(FIELD)
                        .includeExclude(new IncludeExclude("v[0-9]{6}", null, null, null));

                    Aggregator agg = createAggregator(aggBuilder, context);
                    String impl = agg.getClass().getName();

                    assertFalse(
                        "Adapter must decline (return null) on early timeout; expected fallback, got: " + impl,
                        impl.endsWith(".StringTermsAggregatorFromFilters")
                    );
                } finally {
                    Releasables.close(context);
                }
            }
        }
    }
}
