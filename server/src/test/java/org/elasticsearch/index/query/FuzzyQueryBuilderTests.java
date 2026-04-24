/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.query;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.AttributeSource;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.core.Strings;
import org.elasticsearch.lucene.search.CircuitBreakingEsFuzzyQuery;
import org.elasticsearch.lucene.search.EsFuzzyQuery;
import org.elasticsearch.test.AbstractQueryTestCase;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class FuzzyQueryBuilderTests extends AbstractQueryTestCase<FuzzyQueryBuilder> {

    @Override
    protected FuzzyQueryBuilder doCreateTestQueryBuilder() {
        String fieldName = randomFrom(TEXT_FIELD_NAME, TEXT_ALIAS_FIELD_NAME);
        FuzzyQueryBuilder query = new FuzzyQueryBuilder(fieldName, getRandomValueForFieldName(fieldName));
        if (randomBoolean()) {
            query.fuzziness(randomFuzziness(query.fieldName()));
        }
        if (randomBoolean()) {
            query.prefixLength(randomIntBetween(0, 10));
        }
        if (randomBoolean()) {
            query.maxExpansions(randomIntBetween(1, 10));
        }
        if (randomBoolean()) {
            query.transpositions(randomBoolean());
        }
        if (randomBoolean()) {
            query.rewrite(getRandomRewriteMethod());
        }
        return query;
    }

    @Override
    protected Map<String, FuzzyQueryBuilder> getAlternateVersions() {
        Map<String, FuzzyQueryBuilder> alternateVersions = new HashMap<>();
        FuzzyQueryBuilder fuzzyQuery = new FuzzyQueryBuilder(randomAlphaOfLengthBetween(1, 10), randomAlphaOfLengthBetween(1, 10));
        String contentString = Strings.format("""
            {
                "fuzzy" : {
                    "%s" : "%s"
                }
            }""", fuzzyQuery.fieldName(), fuzzyQuery.value());
        alternateVersions.put(contentString, fuzzyQuery);
        return alternateVersions;
    }

    @Override
    protected void doAssertLuceneQuery(FuzzyQueryBuilder queryBuilder, Query query, SearchExecutionContext context) throws IOException {
        assertThat(query, instanceOf(EsFuzzyQuery.class));

        EsFuzzyQuery fuzzyQuery = (EsFuzzyQuery) query;
        String expectedFieldName = expectedFieldName(queryBuilder.fieldName());
        String actualFieldName = fuzzyQuery.getTerm().field();
        assertThat(actualFieldName, equalTo(expectedFieldName));
    }

    public void testIllegalArguments() {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> new FuzzyQueryBuilder(null, "text"));
        assertEquals("field name cannot be null or empty", e.getMessage());

        e = expectThrows(IllegalArgumentException.class, () -> new FuzzyQueryBuilder("", "text"));
        assertEquals("field name cannot be null or empty", e.getMessage());

        e = expectThrows(IllegalArgumentException.class, () -> new FuzzyQueryBuilder("field", null));
        assertEquals("query value cannot be null", e.getMessage());
    }

    public void testToQueryWithStringField() throws IOException {
        String query = Strings.format("""
            {
                "fuzzy":{
                    "%s":{
                        "value":"sh",
                        "fuzziness": "AUTO",
                        "prefix_length":1,
                        "boost":2.0
                    }
                }
            }""", TEXT_FIELD_NAME);
        Query parsedQuery = parseQuery(query).toQuery(createSearchExecutionContext());
        assertThat(parsedQuery, instanceOf(BoostQuery.class));
        BoostQuery boostQuery = (BoostQuery) parsedQuery;
        assertThat(boostQuery.getBoost(), equalTo(2.0f));
        assertThat(boostQuery.getQuery(), instanceOf(EsFuzzyQuery.class));
        EsFuzzyQuery fuzzyQuery = (EsFuzzyQuery) boostQuery.getQuery();
        assertThat(fuzzyQuery.getTerm(), equalTo(new Term(TEXT_FIELD_NAME, "sh")));
        assertThat(fuzzyQuery.getMaxEdits(), equalTo(Fuzziness.AUTO.asDistance("sh")));
        assertThat(fuzzyQuery.getPrefixLength(), equalTo(1));
    }

    public void testToQueryWithStringFieldDefinedFuzziness() throws IOException {
        String query = Strings.format("""
            {
                "fuzzy":{
                    "%s":{
                        "value":"sh",
                        "fuzziness": "AUTO:2,5",
                        "prefix_length":1,
                        "boost":2.0
                    }
                }
            }""", TEXT_FIELD_NAME);
        Query parsedQuery = parseQuery(query).toQuery(createSearchExecutionContext());
        assertThat(parsedQuery, instanceOf(BoostQuery.class));
        BoostQuery boostQuery = (BoostQuery) parsedQuery;
        assertThat(boostQuery.getBoost(), equalTo(2.0f));
        assertThat(boostQuery.getQuery(), instanceOf(EsFuzzyQuery.class));
        EsFuzzyQuery fuzzyQuery = (EsFuzzyQuery) boostQuery.getQuery();
        assertThat(fuzzyQuery.getTerm(), equalTo(new Term(TEXT_FIELD_NAME, "sh")));
        assertThat(fuzzyQuery.getMaxEdits(), equalTo(1));
        assertThat(fuzzyQuery.getPrefixLength(), equalTo(1));
    }

    public void testToQueryWithStringFieldDefinedWrongFuzziness() throws IOException {
        String queryMissingFuzzinessUpLimit = Strings.format("""
            {
                "fuzzy":{
                    "%s":{
                        "value":"sh",
                        "fuzziness": "AUTO:2",
                        "prefix_length":1,
                        "boost":2.0
                    }
                }
            }""", TEXT_FIELD_NAME);
        ElasticsearchParseException e = expectThrows(
            ElasticsearchParseException.class,
            () -> parseQuery(queryMissingFuzzinessUpLimit).toQuery(createSearchExecutionContext())
        );
        String msg = "failed to find low and high distance values";
        assertTrue(e.getMessage() + " didn't contain: " + msg + " but: " + e.getMessage(), e.getMessage().contains(msg));

        String queryHavingNegativeFuzzinessLowLimit = Strings.format("""
            {
                "fuzzy":{
                    "%s":{
                        "value":"sh",
                        "fuzziness": "AUTO:-1,6",
                        "prefix_length":1,
                        "boost":2.0
                    }
                }
            }""", TEXT_FIELD_NAME);
        String msg2 = "fuzziness wrongly configured";
        ElasticsearchParseException e2 = expectThrows(
            ElasticsearchParseException.class,
            () -> parseQuery(queryHavingNegativeFuzzinessLowLimit).toQuery(createSearchExecutionContext())
        );
        assertTrue(e2.getMessage() + " didn't contain: " + msg2 + " but: " + e.getMessage(), e.getMessage().contains(msg));

        String queryMissingFuzzinessUpLimit2 = Strings.format("""
            {
                "fuzzy":{
                    "%s":{
                        "value":"sh",
                        "fuzziness": "AUTO:1,",
                        "prefix_length":1,
                        "boost":2.0
                    }
                }
            }""", TEXT_FIELD_NAME);
        e = expectThrows(
            ElasticsearchParseException.class,
            () -> parseQuery(queryMissingFuzzinessUpLimit2).toQuery(createSearchExecutionContext())
        );
        assertTrue(e.getMessage() + " didn't contain: " + msg + " but: " + e.getMessage(), e.getMessage().contains(msg));

        String queryMissingFuzzinessLowLimit = Strings.format("""
            {
                "fuzzy":{
                    "%s":{
                        "value":"sh",
                        "fuzziness": "AUTO:,5",
                        "prefix_length":1,
                        "boost":2.0
                    }
                }
            }""", TEXT_FIELD_NAME);
        e = expectThrows(
            ElasticsearchParseException.class,
            () -> parseQuery(queryMissingFuzzinessLowLimit).toQuery(createSearchExecutionContext())
        );
        msg = "failed to parse [AUTO:,5] as a \"auto:int,int\"";
        assertTrue(e.getMessage() + " didn't contain: " + msg + " but: " + e.getMessage(), e.getMessage().contains(msg));
    }

    public void testToQueryWithNumericField() throws IOException {
        String query = Strings.format("""
            {
                "fuzzy":{
                    "%s":{
                        "value":12,
                        "fuzziness":2
                    }
                }
            }
            """, INT_FIELD_NAME);
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> parseQuery(query).toQuery(createSearchExecutionContext())
        );
        assertEquals(
            "Can only use fuzzy queries on keyword and text fields - not on [mapped_int] which is of type [integer]",
            e.getMessage()
        );
    }

    public void testFromJson() throws IOException {
        String json = """
            {
              "fuzzy" : {
                "user" : {
                  "value" : "ki",
                  "fuzziness" : "2",
                  "prefix_length" : 0,
                  "max_expansions" : 100,
                  "transpositions" : false,
                  "boost" : 42.0
                }
              }
            }""";
        FuzzyQueryBuilder parsed = (FuzzyQueryBuilder) parseQuery(json);
        checkGeneratedJson(json, parsed);
        assertEquals(json, 42.0, parsed.boost(), 0.00001);
        assertEquals(json, 2, parsed.fuzziness().asFloat(), 0f);
        assertEquals(json, false, parsed.transpositions());
    }

    public void testParseFailsWithMultipleFields() throws IOException {
        String json1 = """
            {
              "fuzzy" : {
                "message1" : {
                  "value" : "this is a test"
                }
              }
            }""";
        parseQuery(json1); // should be all good

        String json2 = """
            {
              "fuzzy" : {
                "message1" : {
                  "value" : "this is a test"
                },
                "message2" : {
                  "value" : "this is a test"
                }
              }
            }""";

        ParsingException e = expectThrows(ParsingException.class, () -> parseQuery(json2));
        assertEquals("[fuzzy] query doesn't support multiple fields, found [message1] and [message2]", e.getMessage());

        String shortJson = """
            {
              "fuzzy" : {
                "message1" : "this is a test",
                "message2" : "value" : "this is a test"
              }
            }""";

        e = expectThrows(ParsingException.class, () -> parseQuery(shortJson));
        assertEquals("[fuzzy] query doesn't support multiple fields, found [message1] and [message2]", e.getMessage());
    }

    public void testParseFailsWithValueArray() {
        String query = """
            {
              "fuzzy" : {
                "message1" : {
                  "value" : [ "one", "two", "three"]
                }
              }
            }""";

        ParsingException e = expectThrows(ParsingException.class, () -> parseQuery(query));
        assertEquals("[fuzzy] unexpected token [START_ARRAY] after [value]", e.getMessage());
    }

    public void testToQueryWithTranspositions() throws Exception {
        Query query = new FuzzyQueryBuilder(TEXT_FIELD_NAME, "text").toQuery(createSearchExecutionContext());
        assertThat(query, instanceOf(EsFuzzyQuery.class));
        assertEquals(EsFuzzyQuery.defaultTranspositions, ((EsFuzzyQuery) query).getTranspositions());

        query = new FuzzyQueryBuilder(TEXT_FIELD_NAME, "text").transpositions(true).toQuery(createSearchExecutionContext());
        assertThat(query, instanceOf(EsFuzzyQuery.class));
        assertEquals(true, ((EsFuzzyQuery) query).getTranspositions());

        query = new FuzzyQueryBuilder(TEXT_FIELD_NAME, "text").transpositions(false).toQuery(createSearchExecutionContext());
        assertThat(query, instanceOf(EsFuzzyQuery.class));
        assertEquals(false, ((EsFuzzyQuery) query).getTranspositions());
    }

    public void testFuzzyQueryCircuitBreakerAccounting() throws IOException {
        assertCircuitBreakerAccountsForQuery(new FuzzyQueryBuilder(TEXT_FIELD_NAME, "text"));
    }

    public void testFuzzyCircuitBreakerTripsWithLowLimit() {
        assertCircuitBreakerTripsOnQueryConstruction("1kb", () -> {
            BoolQueryBuilder boolQuery = new BoolQueryBuilder();
            IntStream.range(0, 500).forEach(i -> boolQuery.should(new FuzzyQueryBuilder(TEXT_FIELD_NAME, "value" + i)));
            return boolQuery;
        });
    }

    public void testFuzzyCircuitBreakerTripsDuringRewrite() throws IOException {
        CircuitBreaker cb = createCircuitBreakerService("2kb");
        SearchExecutionContext context = new SearchExecutionContext(createSearchExecutionContext(), cb);
        try {
            EsFuzzyQuery query = (EsFuzzyQuery) new FuzzyQueryBuilder(TEXT_FIELD_NAME, "value").toQuery(context);
            assertThat("construction must succeed under the tight limit", query, instanceOf(CircuitBreakingEsFuzzyQuery.class));
            try (Directory dir = new ByteBuffersDirectory()) {
                try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
                    Document doc = new Document();
                    doc.add(new StringField(TEXT_FIELD_NAME, "value0", Field.Store.NO));
                    w.addDocument(doc);
                }
                try (DirectoryReader reader = DirectoryReader.open(dir)) {
                    IndexSearcher searcher = new IndexSearcher(reader);
                    expectThrows(
                        org.elasticsearch.common.breaker.CircuitBreakingException.class,
                        () -> searcher.rewrite(query)
                    );
                }
            }
        } finally {
            context.releaseQueryConstructionMemory();
            context.releaseRewriteMemory();
        }
    }

    public void testRewriteMemoryPoolChargedAndReleased() throws IOException {
        CircuitBreaker cb = createCircuitBreakerService();
        SearchExecutionContext context = new SearchExecutionContext(createSearchExecutionContext(), cb);

        long cbBaseline = cb.getUsed();
        Query query = new FuzzyQueryBuilder(TEXT_FIELD_NAME, "value").toQuery(context);
        assertThat(query, instanceOf(EsFuzzyQuery.class));
        long afterConstruction = cb.getUsed();
        assertTrue("construction-time charge should be recorded", afterConstruction >= cbBaseline);

        context.releaseQueryConstructionMemory();
        assertEquals("construction pool drains independently", 0L, context.getQueryConstructionMemoryUsed());

        long beforeRewrite = cb.getUsed();
        try (Directory dir = new ByteBuffersDirectory()) {
            try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
                for (int i = 0; i < 8; i++) {
                    Document doc = new Document();
                    doc.add(new StringField(TEXT_FIELD_NAME, "value" + i, Field.Store.NO));
                    w.addDocument(doc);
                }
            }
            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.rewrite(query);
            }
        }
        long rewriteCharged = context.getRewriteMemoryUsed();
        assertTrue(
            "rewrite pool must be charged for built automata (got=" + rewriteCharged + ")",
            rewriteCharged > 0
        );
        assertEquals(
            "circuit breaker delta should equal the rewrite pool charge",
            rewriteCharged,
            cb.getUsed() - beforeRewrite
        );

        context.releaseRewriteMemory();
        assertEquals("rewrite pool must be drained on release", 0L, context.getRewriteMemoryUsed());
        assertEquals("circuit breaker bookkeeping must be restored", beforeRewrite, cb.getUsed());
    }

    public void testRewriteMemoryChargeEqualsAutomataRamBytes() throws IOException {
        CircuitBreaker cb = createCircuitBreakerService();
        SearchExecutionContext context = new SearchExecutionContext(createSearchExecutionContext(), cb);

        EsFuzzyQuery query = (EsFuzzyQuery) new FuzzyQueryBuilder(TEXT_FIELD_NAME, "value").toQuery(context);
        context.releaseQueryConstructionMemory();
        long expected = query.computeAutomataRamBytes(new AttributeSource());
        assertTrue("expected automaton bytes must be > 0 for maxEdits >= 1", expected > 0);

        try (Directory dir = new ByteBuffersDirectory()) {
            try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
                Document doc = new Document();
                doc.add(new StringField(TEXT_FIELD_NAME, "value0", Field.Store.NO));
                w.addDocument(doc);
            }
            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                new IndexSearcher(reader).rewrite(query);
            }
        }
        assertEquals(
            "rewrite pool charge must equal the automata RAM cost measured independently",
            expected,
            context.getRewriteMemoryUsed()
        );
        context.releaseRewriteMemory();
    }

    public void testRewriteMemoryPoolChargesOncePerRewriteAcrossSegments() throws IOException {
        CircuitBreaker cb = createCircuitBreakerService();
        SearchExecutionContext context = new SearchExecutionContext(createSearchExecutionContext(), cb);

        EsFuzzyQuery query = (EsFuzzyQuery) new FuzzyQueryBuilder(TEXT_FIELD_NAME, "value").toQuery(context);
        context.releaseQueryConstructionMemory();
        long expected = query.computeAutomataRamBytes(new AttributeSource());

        try (Directory dir = new ByteBuffersDirectory()) {
            // Force several separate segments by committing between each document. The default
            // top-terms rewrite reuses a single AttributeSource across all segments, so the lazy
            // charge on CircuitBreakingEsFuzzyQuery must fire exactly once via identity dedup.
            try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
                for (int i = 0; i < 4; i++) {
                    Document doc = new Document();
                    doc.add(new StringField(TEXT_FIELD_NAME, "value" + i, Field.Store.NO));
                    w.addDocument(doc);
                    w.commit();
                }
            }
            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                assertTrue("test requires multiple segments", reader.leaves().size() > 1);
                new IndexSearcher(reader).rewrite(query);
            }
        }
        assertEquals(
            "multi-segment top-terms rewrite must charge exactly once (dedup by AttributeSource identity)",
            expected,
            context.getRewriteMemoryUsed()
        );
        context.releaseRewriteMemory();
    }

    public void testUserSuppliedRewriteChargesLazilyNotUpfront() throws IOException {
        CircuitBreaker cb = createCircuitBreakerService();
        SearchExecutionContext context = new SearchExecutionContext(createSearchExecutionContext(), cb);

        FuzzyQueryBuilder builder = new FuzzyQueryBuilder(TEXT_FIELD_NAME, "value");
        builder.rewrite("constant_score");
        EsFuzzyQuery query = (EsFuzzyQuery) builder.toQuery(context);
        context.releaseQueryConstructionMemory();

        assertThat(
            "breaker-active construction must return a CircuitBreakingEsFuzzyQuery regardless of user rewrite",
            query,
            instanceOf(CircuitBreakingEsFuzzyQuery.class)
        );
        assertEquals("no upfront automata charge; lazy hook fires during execution", 0L, context.getRewriteMemoryUsed());

        long expected = query.computeAutomataRamBytes(new AttributeSource());
        assertTrue("expected automaton bytes must be > 0", expected > 0);

        try (Directory dir = new ByteBuffersDirectory()) {
            try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
                Document doc = new Document();
                doc.add(new StringField(TEXT_FIELD_NAME, "value0", Field.Store.NO));
                w.addDocument(doc);
            }
            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                new IndexSearcher(reader).count(query);
            }
        }
        assertTrue(
            "lazy charge must land in the rewrite pool after execution (got=" + context.getRewriteMemoryUsed() + ")",
            context.getRewriteMemoryUsed() >= expected
        );

        context.releaseRewriteMemory();
        assertEquals("rewrite pool must drain on release", 0L, context.getRewriteMemoryUsed());
    }

    public void testNullRewriteReturnsCircuitBreakingEsFuzzyQuery() throws IOException {
        CircuitBreaker cb = createCircuitBreakerService();
        SearchExecutionContext context = new SearchExecutionContext(createSearchExecutionContext(), cb);

        EsFuzzyQuery query = (EsFuzzyQuery) new FuzzyQueryBuilder(TEXT_FIELD_NAME, "value").toQuery(context);
        assertThat(query, instanceOf(CircuitBreakingEsFuzzyQuery.class));
        assertThat(query.getRewriteMethod(), instanceOf(MultiTermQuery.TopTermsBlendedFreqScoringRewrite.class));
        context.releaseQueryConstructionMemory();
        context.releaseRewriteMemory();
    }

    public void testFieldTypeFuzzyQueryReturnsCircuitBreakingEsFuzzyQueryWhenRewriteMethodIsNull() throws IOException {
        CircuitBreaker cb = createCircuitBreakerService();
        SearchExecutionContext context = new SearchExecutionContext(createSearchExecutionContext(), cb);

        try {
            long cbBefore = cb.getUsed();
            EsFuzzyQuery query = (EsFuzzyQuery) context.getFieldType(TEXT_FIELD_NAME)
                .fuzzyQuery("value", Fuzziness.fromEdits(2), 1, 50, true, context, (MultiTermQuery.RewriteMethod) null);

            assertThat(
                "null rewrite with an active circuit breaker must return a CircuitBreakingEsFuzzyQuery",
                query,
                instanceOf(CircuitBreakingEsFuzzyQuery.class)
            );
            assertEquals(
                "construction pool must be charged exactly the query's ramBytesUsed()",
                query.ramBytesUsed(),
                context.getQueryConstructionMemoryUsed()
            );
            assertEquals(
                "rewrite pool must remain empty until Lucene rewrite fires the lazy hook",
                0L,
                context.getRewriteMemoryUsed()
            );
            assertEquals("circuit breaker delta must equal the construction-pool charge", query.ramBytesUsed(), cb.getUsed() - cbBefore);
        } finally {
            context.releaseQueryConstructionMemory();
            context.releaseRewriteMemory();
        }
    }

    public void testConstantScoreRewriteChargesOncePerSegment() throws IOException {
        CircuitBreaker cb = createCircuitBreakerService();
        SearchExecutionContext context = new SearchExecutionContext(createSearchExecutionContext(), cb);
        try {
            FuzzyQueryBuilder builder = new FuzzyQueryBuilder(TEXT_FIELD_NAME, "value");
            builder.rewrite("constant_score");
            EsFuzzyQuery query = (EsFuzzyQuery) builder.toQuery(context);
            context.releaseQueryConstructionMemory();
            long perAttributeBytes = query.computeAutomataRamBytes(new AttributeSource());

            int numSegments;
            try (Directory dir = new ByteBuffersDirectory()) {
                try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
                    for (int i = 0; i < 4; i++) {
                        Document doc = new Document();
                        doc.add(new StringField(TEXT_FIELD_NAME, "value" + i, Field.Store.NO));
                        w.addDocument(doc);
                        w.commit();
                    }
                }
                try (DirectoryReader reader = DirectoryReader.open(dir)) {
                    numSegments = reader.leaves().size();
                    assertTrue("test requires multiple segments", numSegments > 1);
                    // constant_score wraps in MultiTermQueryConstantScoreWrapper, which enumerates
                    // terms at scoring time — not at rewrite(). Use count() to drive it.
                    new IndexSearcher(reader).count(query);
                }
            }

            long totalCharged = context.getRewriteMemoryUsed();
            assertTrue(
                "cumulative charge must be at least numSegments * perAttributeBytes (got=" + totalCharged + ")",
                totalCharged >= (long) numSegments * perAttributeBytes
            );
            assertEquals(
                "each segment must contribute exactly one full charge",
                (long) numSegments * perAttributeBytes,
                totalCharged
            );
        } finally {
            context.releaseQueryConstructionMemory();
            context.releaseRewriteMemory();
        }
    }
    
    public void testFieldTypeFuzzyQueryWithUserRewriteDefersAutomataCharge() throws IOException {
        CircuitBreaker cb = createCircuitBreakerService();
        SearchExecutionContext context = new SearchExecutionContext(createSearchExecutionContext(), cb);

        try {
            long cbBefore = cb.getUsed();
            EsFuzzyQuery query = (EsFuzzyQuery) context.getFieldType(TEXT_FIELD_NAME)
                .fuzzyQuery("value", Fuzziness.fromEdits(2), 1, 50, true, context, MultiTermQuery.CONSTANT_SCORE_BOOLEAN_REWRITE);

            assertThat(query, instanceOf(CircuitBreakingEsFuzzyQuery.class));
            assertSame(
                "user-supplied rewrite must be preserved on the query",
                MultiTermQuery.CONSTANT_SCORE_BOOLEAN_REWRITE,
                query.getRewriteMethod()
            );
            assertEquals(
                "construction pool must be charged exactly the query's ramBytesUsed()",
                query.ramBytesUsed(),
                context.getQueryConstructionMemoryUsed()
            );
            assertEquals("rewrite pool must stay empty until the lazy hook fires", 0L, context.getRewriteMemoryUsed());
            assertEquals(
                "circuit breaker delta must equal only the construction-pool charge",
                query.ramBytesUsed(),
                cb.getUsed() - cbBefore
            );
        } finally {
            context.releaseQueryConstructionMemory();
            context.releaseRewriteMemory();
        }
    }
}
