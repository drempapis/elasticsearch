/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.searchbusinessrules;

import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.NumericUtils;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.xcontent.ConstructingObjectParser.optionalConstructorArg;

/**
 * A query that will promote selected documents (identified by ID) above matches produced by an "organic" query. In practice, some upstream
 * system will identify the promotions associated with a user's query string and use this object to ensure these are "pinned" to the top of
 * the other search results.
 */
public class PinnedQueryBuilder extends AbstractQueryBuilder<PinnedQueryBuilder> {
    public static final String NAME = "pinned";
    public static final int MAX_NUM_PINNED_HITS = 100;

    public static final ParseField IDS_FIELD = new ParseField("ids");
    public static final ParseField DOCS_FIELD = new ParseField("docs");
    public static final ParseField ORGANIC_QUERY_FIELD = new ParseField("organic");

    private final List<String> ids;
    private final List<SpecifiedDocument> docs;
    private QueryBuilder organicQuery;

    // Organic queries will have their scores capped to this number range,
    // We reserve the highest float exponent for scores of pinned queries
    public static final float MAX_ORGANIC_SCORE = Float.intBitsToFloat((0xfe << 23)) - 1;

    public PinnedQueryBuilder(QueryBuilder organicQuery, String... ids) {
        this(organicQuery, Arrays.asList(ids), null);
    }

    public PinnedQueryBuilder(QueryBuilder organicQuery, SpecifiedDocument... docs) {
        this(organicQuery, null, Arrays.asList(docs));
    }

    /**
     * Creates a new PinnedQueryBuilder
     */
    private PinnedQueryBuilder(QueryBuilder organicQuery, List<String> ids, List<SpecifiedDocument> docs) {
        if (organicQuery == null) {
            throw new IllegalArgumentException("[" + NAME + "] organicQuery cannot be null");
        }
        this.organicQuery = organicQuery;
        if (ids == null && docs == null) {
            throw new IllegalArgumentException("[" + NAME + "] ids and docs cannot both be null");
        }
        if (ids != null && docs != null) {
            throw new IllegalArgumentException("[" + NAME + "] ids and docs cannot both be used");
        }
        if (ids != null) {
            if (ids.size() > MAX_NUM_PINNED_HITS) {
                throw new IllegalArgumentException(
                    "[" + NAME + "] Max of " + MAX_NUM_PINNED_HITS + " ids exceeded: " + ids.size() + " provided."
                );
            }
            LinkedHashSet<String> deduped = new LinkedHashSet<>();
            for (String id : ids) {
                if (id == null) {
                    throw new IllegalArgumentException("[" + NAME + "] id cannot be null");
                }
                if (deduped.add(id) == false) {
                    throw new IllegalArgumentException("[" + NAME + "] duplicate id found in list: " + id);
                }
            }
        }
        if (docs != null) {
            if (docs.size() > MAX_NUM_PINNED_HITS) {
                throw new IllegalArgumentException(
                    "[" + NAME + "] Max of " + MAX_NUM_PINNED_HITS + " docs exceeded: " + docs.size() + " provided."
                );
            }
            LinkedHashSet<SpecifiedDocument> deduped = new LinkedHashSet<>();
            for (SpecifiedDocument doc : docs) {
                if (doc == null) {
                    throw new IllegalArgumentException("[" + NAME + "] doc cannot be null");
                }
                if (deduped.add(doc) == false) {
                    throw new IllegalArgumentException("[" + NAME + "] duplicate doc found in list: " + doc);
                }
            }
        }
        this.ids = ids;
        this.docs = docs;
    }

    /**
     * Read from a stream.
     */
    public PinnedQueryBuilder(StreamInput in) throws IOException {
        super(in);
        ids = in.readOptionalStringCollectionAsList();
        docs = in.readBoolean() ? in.readCollectionAsList(SpecifiedDocument::new) : null;
        organicQuery = in.readNamedWriteable(QueryBuilder.class);
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeOptionalStringCollection(this.ids);
        if (docs == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeCollection(docs);
        }
        out.writeNamedWriteable(organicQuery);
    }

    /**
     * @return the organic query set in the constructor
     */
    public QueryBuilder organicQuery() {
        return this.organicQuery;
    }

    /**
     * Returns the pinned ids for the query.
     */
    public List<String> ids() {
        if (this.ids == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(this.ids);
    }

    /**
     * @return the pinned docs for the query.
     */
    public List<SpecifiedDocument> docs() {
        if (this.docs == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(this.docs);
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        if (organicQuery != null) {
            builder.field(ORGANIC_QUERY_FIELD.getPreferredName());
            organicQuery.toXContent(builder, params);
        }
        if (ids != null) {
            builder.startArray(IDS_FIELD.getPreferredName());
            for (String value : ids) {
                builder.value(value);
            }
            builder.endArray();
        }
        if (docs != null) {
            builder.startArray(DOCS_FIELD.getPreferredName());
            for (SpecifiedDocument specifiedDocument : docs) {
                builder.value(specifiedDocument);
            }
            builder.endArray();
        }
        boostAndQueryNameToXContent(builder);
        builder.endObject();
    }

    private static final ConstructingObjectParser<PinnedQueryBuilder, Void> PARSER = new ConstructingObjectParser<>(NAME, a -> {
        QueryBuilder organicQuery = (QueryBuilder) a[0];
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) a[1];
        @SuppressWarnings("unchecked")
        List<SpecifiedDocument> docs = (List<SpecifiedDocument>) a[2];
        return new PinnedQueryBuilder(organicQuery, ids, docs);
    });
    static {
        PARSER.declareObject(constructorArg(), (p, c) -> parseInnerQueryBuilder(p), ORGANIC_QUERY_FIELD);
        PARSER.declareStringArray(optionalConstructorArg(), IDS_FIELD);
        PARSER.declareObjectArray(optionalConstructorArg(), SpecifiedDocument.PARSER, DOCS_FIELD);
        declareStandardFields(PARSER);
    }

    public static PinnedQueryBuilder fromXContent(XContentParser parser) {
        try {
            return PARSER.apply(parser, null);
        } catch (IllegalArgumentException e) {
            throw new ParsingException(parser.getTokenLocation(), e.getMessage(), e);
        }
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    protected QueryBuilder doRewrite(QueryRewriteContext queryRewriteContext) throws IOException {
        QueryBuilder newOrganicQuery = organicQuery.rewrite(queryRewriteContext);
        if (newOrganicQuery != organicQuery) {
            PinnedQueryBuilder result = new PinnedQueryBuilder(newOrganicQuery, ids, docs);
            result.boost(this.boost);
            return result;
        }
        return this;
    }

    @Override
    protected Query doToQuery(SearchExecutionContext context) throws IOException {
        MappedFieldType idField = context.getFieldType(IdFieldMapper.NAME);
        if (idField == null) {
            return new MatchNoDocsQuery("No mappings");
        }
        List<SpecifiedDocument> specifiedDocuments = (docs != null)
            ? docs
            : ids.stream().map(id -> new SpecifiedDocument(null, id)).toList();
        if (specifiedDocuments.isEmpty()) {
            return new CappedScoreQuery(organicQuery.toQuery(context), MAX_ORGANIC_SCORE);
        } else {
            List<Query> pinnedQueries = new ArrayList<>();

            // Ensure each pin order using a Boost query with the relevant boost factor
            int minPin = NumericUtils.floatToSortableInt(MAX_ORGANIC_SCORE) + 1;
            int boostNum = minPin + specifiedDocuments.size();
            float lastScore = Float.MAX_VALUE;
            for (SpecifiedDocument specifiedDocument : specifiedDocuments) {
                float pinScore = NumericUtils.sortableIntToFloat(boostNum);
                assert pinScore < lastScore;
                lastScore = pinScore;
                boostNum--;
                if (specifiedDocument.index() == null || context.indexMatches(specifiedDocument.index())) {
                    // Ensure the pin order using a Boost query with the relevant boost factor
                    Query idQuery = new BoostQuery(new ConstantScoreQuery(idField.termQuery(specifiedDocument.id(), context)), pinScore);
                    pinnedQueries.add(idQuery);
                }
            }

            // Score for any pinned query clause should be used, regardless of any organic clause score, to preserve pin order.
            // Use dismax to always take the larger (ie pinned) of the organic vs pinned scores
            List<Query> organicAndPinned = new ArrayList<>();
            organicAndPinned.add(new DisjunctionMaxQuery(pinnedQueries, 0));
            // Cap the scores of the organic query
            organicAndPinned.add(new CappedScoreQuery(organicQuery.toQuery(context), MAX_ORGANIC_SCORE));
            return new DisjunctionMaxQuery(organicAndPinned, 0);
        }

    }

    @Override
    protected int doHashCode() {
        return Objects.hash(ids, docs, organicQuery);
    }

    @Override
    protected boolean doEquals(PinnedQueryBuilder other) {
        return Objects.equals(ids, other.ids)
            && Objects.equals(docs, other.docs)
            && Objects.equals(organicQuery, other.organicQuery)
            && boost == other.boost;
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return TransportVersions.ZERO;
    }
}
