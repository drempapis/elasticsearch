/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.lucene.search;

import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.util.AttributeSource;
import org.elasticsearch.index.query.SearchExecutionContext;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * An {@link EsFuzzyQuery} that charges the Levenshtein-automata RAM cost against the circuit
 * breaker at the exact moment Lucene is about to materialise a terms enum for this query on a
 * given {@link AttributeSource}.
 *
 * <p><b>Why a subclass on the query (rather than wrapping the rewrite method):</b>
 * every {@link MultiTermQuery.RewriteMethod} — {@link MultiTermQuery#CONSTANT_SCORE_BLENDED_REWRITE},
 * {@link MultiTermQuery#DOC_VALUES_REWRITE}, the top-terms family, and anonymous subclasses — ends
 * up invoking {@link MultiTermQuery#getTermsEnum(Terms, AttributeSource)} either directly (the
 * top-terms path) or via the single-arg default which delegates to the two-arg form with a fresh
 * {@link AttributeSource}. By hooking here we catch every rewrite path with one implementation.
 *
 * <p><b>Why this is a single build, not a double build:</b> {@code super.getTermsEnum(...)} builds
 * the {@link EsFuzzyTermsEnum}, whose constructor primes the shared {@link
 * EsFuzzyTermsEnum.AutomatonAttribute} on {@code atts} with the compiled automata. Once that has
 * happened, {@link EsFuzzyQuery#computeAutomataRamBytes(AttributeSource)} only reads the already-
 * built automata (the attribute's {@code init} is idempotent); it does not re-compile anything.
 *
 * <p><b>Dedup semantics:</b> the same {@link AttributeSource} is reused across segments by
 * {@code TermCollectingRewrite}, so we charge only on the first {@code getTermsEnum} call per
 * identity. Rewrite methods that use a <em>fresh</em> {@code AttributeSource} per segment (notably
 * {@code DOC_VALUES_REWRITE} and the constant-score blended wrapper) will therefore charge
 * cumulatively — once per segment. Peak in-memory cost at any instant is one automaton set, but
 * the pool accumulates across segments until {@link SearchExecutionContext#releaseRewriteMemory()}
 * drains it at request end. This is a deliberate safety-over-precision tradeoff; {@link
 * SearchExecutionContext#addRewriteCircuitBreakerMemory(long, String)} still trips the breaker
 * before the JVM goes down.
 *
 * <p>The dedup set is updated on a single rewrite thread (Lucene's {@code collectTerms} / scorer
 * construction is sequential per shard query), so no synchronization is required.
 */
public final class CircuitBreakingEsFuzzyQuery extends EsFuzzyQuery {

    private final SearchExecutionContext context;
    private final String label;
    private final Set<AttributeSource> charged = Collections.newSetFromMap(new IdentityHashMap<>());

    public CircuitBreakingEsFuzzyQuery(
        Term term,
        int maxEdits,
        int prefixLength,
        int maxExpansions,
        boolean transpositions,
        MultiTermQuery.RewriteMethod rewriteMethod,
        SearchExecutionContext context,
        String label
    ) {
        super(term, maxEdits, prefixLength, maxExpansions, transpositions, rewriteMethod);
        this.context = context;
        this.label = label;
    }

    @Override
    protected TermsEnum getTermsEnum(Terms terms, AttributeSource atts) throws IOException {
        TermsEnum te = super.getTermsEnum(terms, atts);
        if (getMaxEdits() != 0 && charged.add(atts)) {
            long bytes = computeAutomataRamBytes(atts);
            if (bytes > 0) {
                context.addRewriteCircuitBreakerMemory(bytes, label);
            }
        }
        return te;
    }
}
