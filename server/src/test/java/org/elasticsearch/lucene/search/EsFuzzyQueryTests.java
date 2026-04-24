/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.lucene.search;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.AttributeSource;
import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;

/**
 * Covers the accounting contract of {@link EsFuzzyQuery}:
 * <ul>
 *     <li>{@link EsFuzzyQuery#ramBytesUsed()} is stable across a query's lifetime and deliberately
 *     excludes the Levenshtein automata, which are not retained by the query object.</li>
 *     <li>{@link EsFuzzyQuery#computeAutomataRamBytes(AttributeSource)} is the opt-in way to
 *     observe the transient automaton cost; it also primes the {@code AutomatonAttribute} cache
 *     on the supplied {@link AttributeSource} so a subsequent {@code EsFuzzyTermsEnum} does not
 *     rebuild them.</li>
 * </ul>
 */
public class EsFuzzyQueryTests extends ESTestCase {

    public void testRamBytesUsedIsStable() throws IOException {
        Term term = new Term("field", "foobar");
        EsFuzzyQuery query = new EsFuzzyQuery(term, 2, 0, 50, true);

        long expected = RamUsageEstimator.shallowSizeOfInstance(EsFuzzyQuery.class) + term.ramBytesUsed();
        assertEquals("ramBytesUsed should be shallowSize + term.ramBytesUsed()", expected, query.ramBytesUsed());

        long before = query.ramBytesUsed();
        try (Directory dir = new ByteBuffersDirectory()) {
            try (IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
                Document doc = new Document();
                doc.add(new StringField("field", "foobar", Field.Store.NO));
                writer.addDocument(doc);
                doc = new Document();
                doc.add(new StringField("field", "foobaz", Field.Store.NO));
                writer.addDocument(doc);
            }
            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.count(query);
            }
        }

        assertEquals("ramBytesUsed must not grow during/after rewrite", before, query.ramBytesUsed());
    }

    public void testComputeAutomataRamBytesPositiveForMaxEditsGtZero() {
        EsFuzzyQuery query = new EsFuzzyQuery(new Term("field", "foobar"), 2, 0, 50, true);
        long bytes = query.computeAutomataRamBytes(new AttributeSource());
        assertTrue("automata ram bytes must be > 0 for maxEdits >= 1 (got " + bytes + ")", bytes > 0);
    }

    public void testComputeAutomataRamBytesZeroForExactMatch() {
        EsFuzzyQuery query = new EsFuzzyQuery(new Term("field", "foobar"), 0, 0, 50, true);
        assertEquals("maxEdits == 0 must not build automata", 0L, query.computeAutomataRamBytes(new AttributeSource()));
    }

    public void testComputeAutomataRamBytesIsIdempotentOnSameAttributes() {
        EsFuzzyQuery query = new EsFuzzyQuery(new Term("field", "foobar"), 2, 0, 50, true);
        AttributeSource atts = new AttributeSource();
        long first = query.computeAutomataRamBytes(atts);
        long second = query.computeAutomataRamBytes(atts);
        assertTrue("first call should report non-zero automaton cost", first > 0);
        assertEquals("second call on the same AttributeSource must reuse primed automata", first, second);
    }
}
