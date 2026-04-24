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
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.util.AttributeSource;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.query.SearchExecutionContext;

/**
 * Factory helpers for constructing fuzzy queries with circuit-breaker accounting.
 *
 * <p>All production code paths that build an {@link EsFuzzyQuery} for search should go through
 * this class so that the Levenshtein automata RAM and the query-object RAM are consistently
 * charged against the search-tier circuit breaker. The {@link EsFuzzyQuery} fork itself is kept
 * deliberately framework-agnostic (targeted for upstream Lucene), so the Elasticsearch-specific
 * concerns (breaker, release hooks, labels) live here.
 *
 * <p><b>Charging strategy</b>
 * <ol>
 *   <li>The query object's own retained memory ({@link EsFuzzyQuery#ramBytesUsed()}) is charged
 *       upfront against the request-scoped query-construction pool via
 *       {@link SearchExecutionContext#addCircuitBreakerMemory}. This is stable and bounded.</li>
 *   <li>The Levenshtein {@link org.apache.lucene.util.automaton.CompiledAutomaton} set — the
 *       dominant contributor in pathological fuzzy workloads — is charged <em>lazily</em> by the
 *       {@link CircuitBreakingEsFuzzyQuery} subclass returned from {@link #create}. It charges the
 *       first time Lucene calls {@link CircuitBreakingEsFuzzyQuery#getTermsEnum(
 *       org.apache.lucene.index.Terms, org.apache.lucene.util.AttributeSource)} for a given
 *       {@link org.apache.lucene.util.AttributeSource}, using the same automata that the terms
 *       enum will actually use (no double build).</li>
 * </ol>
 *
 * <p><b>Explicit upfront charge</b> ({@link #chargeQuery}) exists for the small set of callers
 * that construct an anonymous {@link EsFuzzyQuery} subclass or a wrapper that never dispatches
 * back through {@link EsFuzzyQuery#getTermsEnum}: in those cases the lazy hook on the subclass
 * cannot observe the real terms enum, so we size the automata upfront via {@link
 * EsFuzzyQuery#computeAutomataRamBytes(AttributeSource)} against a throwaway {@code
 * AttributeSource}, charge the result, and let the caller's own code rebuild the automata at
 * search time. Known callers:
 * <ul>
 *   <li>{@code VersionStringFieldMapper} — anonymous subclass with a custom
 *       {@code getTermsEnum} that builds a {@code FilteredTermsEnum} rather than an
 *       {@link EsFuzzyTermsEnum}.</li>
 *   <li>{@code StringScriptFieldFuzzyQuery} — the {@link EsFuzzyQuery} it builds is a metadata
 *       delegate (for {@code toString}/equality) and is never rewritten.</li>
 * </ul>
 *
 * <p>When {@code context} is {@code null} or its circuit breaker is {@code null} (tests,
 * coordinator-side cluster-state rewrites) this class constructs the query without charging.
 */
public final class FuzzyQueries {

    private FuzzyQueries() {}

    /**
     * Construct an {@link EsFuzzyQuery} and account its RAM cost against the circuit breaker on
     * {@code context} where possible. When the breaker is active a {@link
     * CircuitBreakingEsFuzzyQuery} subclass instance is returned so the compiled automata are
     * charged lazily at rewrite time; otherwise a plain {@link EsFuzzyQuery} is returned.
     *
     * @param term            the indexed term to match
     * @param maxEdits        the maximum edit distance (0..{@link
     *                        org.apache.lucene.util.automaton.LevenshteinAutomata#MAXIMUM_SUPPORTED_DISTANCE})
     * @param prefixLength    common non-fuzzy prefix length
     * @param maxExpansions   maximum expansions for the default top-terms rewrite
     * @param transpositions  whether transpositions count as a single edit
     * @param rewriteMethod   optional caller-supplied rewrite method; when {@code null} the
     *                        Lucene default {@link EsFuzzyQuery#defaultRewriteMethod(int)} is used
     * @param context         search execution context; may be {@code null} for non-search paths
     * @param fieldLabel      label used in circuit breaker messages (typically the field name)
     */
    public static EsFuzzyQuery create(
        Term term,
        int maxEdits,
        int prefixLength,
        int maxExpansions,
        boolean transpositions,
        @Nullable MultiTermQuery.RewriteMethod rewriteMethod,
        @Nullable SearchExecutionContext context,
        String fieldLabel
    ) {
        boolean circuitBreakerActive = context != null && context.getCircuitBreaker() != null;
        MultiTermQuery.RewriteMethod effectiveRewrite = rewriteMethod != null
            ? rewriteMethod
            : EsFuzzyQuery.defaultRewriteMethod(maxExpansions);
        String label = "fuzzy:" + fieldLabel;
        EsFuzzyQuery query;
        if (circuitBreakerActive) {
            query = new CircuitBreakingEsFuzzyQuery(
                term,
                maxEdits,
                prefixLength,
                maxExpansions,
                transpositions,
                effectiveRewrite,
                context,
                label
            );
            context.addCircuitBreakerMemory(query.ramBytesUsed(), label);
        } else {
            query = new EsFuzzyQuery(term, maxEdits, prefixLength, maxExpansions, transpositions, effectiveRewrite);
        }

        return query;
    }

    /**
     * Explicit upfront-charge API for callers that <em>cannot</em> use {@link #create(Term, int,
     * int, int, boolean, MultiTermQuery.RewriteMethod, SearchExecutionContext, String)} because
     * they construct an anonymous {@link EsFuzzyQuery} subclass or wrapper whose terms enum does
     * not flow through {@link EsFuzzyQuery#getTermsEnum(org.apache.lucene.index.Terms,
     * AttributeSource)}.
     *
     * <p>This method charges {@link EsFuzzyQuery#ramBytesUsed()} against the query-construction
     * pool and then sizes the full compiled automaton set via {@link
     * EsFuzzyQuery#computeAutomataRamBytes(AttributeSource)} against a throwaway {@link
     * AttributeSource} in order to charge its bytes against the rewrite pool. The throwaway
     * automata are GC-eligible immediately after this method returns; the caller's own code will
     * rebuild them at search time.
     *
     * <p>No-ops when {@code context} is {@code null} or has no circuit breaker.
     */
    public static void chargeQuery(EsFuzzyQuery query, @Nullable SearchExecutionContext context, String fieldLabel) {
        if (context == null || context.getCircuitBreaker() == null) {
            return;
        }
        String label = "fuzzy:" + fieldLabel;
        context.addCircuitBreakerMemory(query.ramBytesUsed(), label);
        long automataBytes = query.computeAutomataRamBytes(new AttributeSource());
        context.addRewriteCircuitBreakerMemory(automataBytes, label);
    }
}
