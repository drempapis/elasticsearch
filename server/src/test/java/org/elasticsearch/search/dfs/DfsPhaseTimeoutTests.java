/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.dfs;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnByteVectorField;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.elasticsearch.action.search.SearchShardTask;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.cache.bitset.BitsetFilterCache;
import org.elasticsearch.index.fielddata.IndexFieldDataCache;
import org.elasticsearch.index.mapper.MapperMetrics;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.query.SearchExecutionContextHelper;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.IndexShardTestCase;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.search.internal.ContextIndexSearcher;
import org.elasticsearch.search.query.QueryPhase;
import org.elasticsearch.test.TestSearchContext;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DfsPhaseTimeoutTests extends IndexShardTestCase {

    private static Directory dir;
    private static IndexReader reader;
    private static int numDocs;
    private IndexShard indexShard;

    @BeforeClass
    public static void init() throws Exception {
        dir = newDirectory();
        IndexWriterConfig iwc = new IndexWriterConfig();
        RandomIndexWriter w = new RandomIndexWriter(random(), dir, iwc);
        numDocs = scaledRandomIntBetween(500, 4500);
        for (int i = 0; i < numDocs; ++i) {
            Document doc = new Document();
            doc.add(new StringField("field", Integer.toString(i), Field.Store.NO));
            doc.add(
                new KnnByteVectorField(
                    "byte_vector",
                    new byte[] { (byte) (i % 128), (byte) ((i + 1) % 128), (byte) ((i + 2) % 128) }
                )
            );
            doc.add(new KnnFloatVectorField("float_vector", new float[] { i * 0.1f, (i + 1) * 0.1f, (i + 2) * 0.1f }));
            w.addDocument(doc);
        }
        w.close();
        reader = DirectoryReader.open(dir);
    }

    @AfterClass
    public static void destroy() throws Exception {
        if (reader != null) {
            reader.close();
        }
        dir.close();
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        indexShard = newShard(true);
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        closeShards(indexShard);
    }

    private static ContextIndexSearcher newContextSearcher(IndexReader reader) throws IOException {
        return new ContextIndexSearcher(
            reader,
            IndexSearcher.getDefaultSimilarity(),
            IndexSearcher.getDefaultQueryCache(),
            LuceneTestCase.MAYBE_CACHE_POLICY,
            true
        );
    }

    public void testSingleKnnSearchNoTimeout() throws IOException {
        ContextIndexSearcher cis = newContextSearcher(reader);
        DfsKnnResults results = DfsPhase.singleKnnSearch(new MatchAllDocsQuery(), 10, null, cis, null);
        assertNotNull(results);
        assertEquals(10, results.scoreDocs().length);
    }

    public void testSingleKnnSearchWithTimeoutNotExceeded() throws Exception {
        ContextIndexSearcher cis = newContextSearcher(reader);

        try (TestSearchContext context = new TestSearchContext(createSearchExecutionContext(), indexShard, cis) {
            @Override
            public long getRelativeTimeInMillis() {
                return 0L;
            }

            @Override
            public TimeValue timeout() {
                return new TimeValue(100, TimeUnit.MILLISECONDS);
            }
        }) {
            context.setTask(new SearchShardTask(123L, "", "", "", null, Collections.emptyMap()));

            Runnable timeoutRunnable = QueryPhase.getTimeoutCheck(context);
            assertNotNull(timeoutRunnable);
            cis.addQueryCancellation(timeoutRunnable);

            DfsKnnResults results = DfsPhase.singleKnnSearch(new MatchAllDocsQuery(), 10, null, cis, null);
            assertNotNull(results);
            assertEquals(10, results.scoreDocs().length);
        }
    }

    public void testSingleKnnSearchTimesOut() throws Exception {
        ContextIndexSearcher cis = newContextSearcher(reader);
        final AtomicBoolean shouldTimeout = new AtomicBoolean(false);

        try (TestSearchContext context = new TestSearchContext(createSearchExecutionContext(), indexShard, cis) {
            @Override
            public long getRelativeTimeInMillis() {
                return shouldTimeout.get() ? 1L : 0L;
            }

            @Override
            public TimeValue timeout() {
                return TimeValue.ZERO;
            }
        }) {
            context.setTask(new SearchShardTask(123L, "", "", "", null, Collections.emptyMap()));

            // Install timeout check — same as our DfsPhase fix does
            Runnable timeoutRunnable = QueryPhase.getTimeoutCheck(context);
            assertNotNull(timeoutRunnable);
            cis.addQueryCancellation(timeoutRunnable);

            // Advance time past the timeout threshold before searching
            shouldTimeout.set(true);

            expectThrows(
                ContextIndexSearcher.TimeExceededException.class,
                () -> DfsPhase.singleKnnSearch(new MatchAllDocsQuery(), 10, null, cis, null)
            );
        }
    }

    private SearchExecutionContext createSearchExecutionContext() {
        IndexMetadata indexMetadata = IndexMetadata.builder("index")
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .creationDate(System.currentTimeMillis())
            .build();
        IndexSettings indexSettings = new IndexSettings(indexMetadata, Settings.EMPTY);
        final long nowInMillis = randomNonNegativeLong();
        return new SearchExecutionContext(
            0,
            0,
            indexSettings,
            new BitsetFilterCache(indexSettings, BitsetFilterCache.Listener.NOOP),
            (ft, fdc) -> ft.fielddataBuilder(fdc).build(new IndexFieldDataCache.None(), new NoneCircuitBreakerService()),
            null,
            MappingLookup.EMPTY,
            null,
            null,
            parserConfig(),
            writableRegistry(),
            null,
            new IndexSearcher(reader),
            () -> nowInMillis,
            null,
            null,
            () -> true,
            null,
            Collections.emptyMap(),
            null,
            MapperMetrics.NOOP,
            SearchExecutionContextHelper.SHARD_SEARCH_STATS
        );
    }
}
