/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.common.document;

import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.index.get.GetResult;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.fetch.subphase.LookupField;
import org.elasticsearch.xcontent.ToXContentFragment;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.common.xcontent.XContentParserUtils.ensureExpectedToken;
import static org.elasticsearch.common.xcontent.XContentParserUtils.parseFieldsValue;

/**
 * A single field name and values part of {@link SearchHit} and {@link GetResult}.
 *
 * @see SearchHit
 * @see GetResult
 */
public class DocumentField implements Writeable, Iterable<Object> {

    private static final long SHALLOW_SIZE = RamUsageEstimator.shallowSizeOfInstance(DocumentField.class);

    /**
     * Hard cap on the recursion depth used by {@link #ramBytesUsedEstimate()}. We need more than
     * the {@code MAX_DEPTH=1} that {@link RamUsageEstimator#sizeOfObject(Object)} uses internally
     * because script-produced {@link Map} and {@link List} payloads frequently nest. The depth limit
     * exists purely as a safety valve against pathological / cyclic inputs.
     */
    private static final int MAX_ESTIMATE_DEPTH = 20;

    private final String name;
    private final List<Object> values;
    private final List<Object> ignoredValues;
    private final List<LookupField> lookupFields;

    public DocumentField(StreamInput in) throws IOException {
        name = in.readString();
        values = in.readCollectionAsList(StreamInput::readGenericValue);
        ignoredValues = in.readCollectionAsList(StreamInput::readGenericValue);
        lookupFields = in.readCollectionAsList(LookupField::new);
    }

    public DocumentField(String name, List<Object> values) {
        this(name, values, Collections.emptyList());
    }

    public DocumentField(String name, List<Object> values, List<Object> ignoredValues) {
        this(name, values, ignoredValues, Collections.emptyList());
    }

    public DocumentField(String name, List<Object> values, List<Object> ignoredValues, List<LookupField> lookupFields) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.values = Objects.requireNonNull(values, "values must not be null");
        this.ignoredValues = Objects.requireNonNull(ignoredValues, "ignoredValues must not be null");
        this.lookupFields = Objects.requireNonNull(lookupFields, "lookupFields must not be null");
        assert lookupFields.isEmpty() || (values.isEmpty() && ignoredValues.isEmpty())
            : "DocumentField can't have both lookup fields and values";
    }

    /**
     * Read map of document fields written via {@link StreamOutput#writeMapValues(Map)}.
     * @param in stream input
     * @return map of {@link DocumentField} keyed by {@link DocumentField#getName()}
     */
    public static Map<String, DocumentField> readFieldsFromMapValues(StreamInput in) throws IOException {
        return in.readMapValues(DocumentField::new, DocumentField::getName);
    }

    /**
     * The name of the field.
     */
    public String getName() {
        return name;
    }

    /**
     * The first value of the hit.
     */
    @SuppressWarnings("unchecked")
    public <V> V getValue() {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return (V) values.get(0);
    }

    /**
     * The field values.
     */
    public List<Object> getValues() {
        return values;
    }

    @Override
    public Iterator<Object> iterator() {
        return values.iterator();
    }

    /**
     * The field's ignored values as an immutable list.
     */
    public List<Object> getIgnoredValues() {
        return ignoredValues == Collections.emptyList() ? ignoredValues : Collections.unmodifiableList(ignoredValues);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(name);
        out.writeCollection(values, StreamOutput::writeGenericValue);
        out.writeCollection(ignoredValues, StreamOutput::writeGenericValue);
        out.writeCollection(lookupFields);
    }

    public List<LookupField> getLookupFields() {
        return lookupFields;
    }

    /**
     * Best-effort estimate of the retained heap of this {@link DocumentField}, including the
     * recursive contents of {@link #values} and {@link #ignoredValues}.
     * <p>
     * Used by the fetch-phase circuit breaker to size scripted-field payloads (which typically
     * dominate {@link SearchHit} memory) before they accumulate enough to OOM the JVM. The number
     * is deliberately a coarse upper bound: it walks nested {@link Collection}s, {@link Map}s, and
     * arrays via {@link RamUsageEstimator#sizeOfObject(Object)} for leaf values, but caps the
     * traversal at {@link #MAX_ESTIMATE_DEPTH} to bound work on pathological inputs.
     * <p>
     * {@link LookupField}s are not included because they are immutable, small, and never produced
     * by user scripts; the breaker accounting we drive from this estimate targets the script-field
     * code path.
     */
    public long ramBytesUsedEstimate() {
        return SHALLOW_SIZE + RamUsageEstimator.sizeOf(name) + estimateContainer(values) + estimateContainer(ignoredValues);
    }

    private static long estimateContainer(Collection<?> collection) {
        if (collection == null || collection.isEmpty()) {
            return 0L;
        }
        long size = RamUsageEstimator.shallowSizeOf(collection);
        for (Object value : collection) {
            size += estimateValue(value, 0);
        }
        return size;
    }

    private static long estimateValue(Object value, int depth) {
        if (value == null) {
            return 0L;
        }
        if (depth >= MAX_ESTIMATE_DEPTH) {
            // bail out to the shallow estimator to avoid runaway recursion on cyclic / huge graphs.
            return RamUsageEstimator.shallowSizeOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            long size = RamUsageEstimator.shallowSizeOf(map);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                size += estimateValue(entry.getKey(), depth + 1);
                size += estimateValue(entry.getValue(), depth + 1);
            }
            return size;
        }
        if (value instanceof Collection<?> collection) {
            long size = RamUsageEstimator.shallowSizeOf(collection);
            for (Object o : collection) {
                size += estimateValue(o, depth + 1);
            }
            return size;
        }
        // Leaf values (String, Number, primitive arrays, etc.) are handled fully by Lucene's helper.
        return RamUsageEstimator.sizeOfObject(value);
    }

    public ToXContentFragment getValidValuesWriter() {
        return (builder, params) -> {
            builder.startArray(name);
            for (Object value : values) {
                try {
                    builder.value(value);
                } catch (RuntimeException e) {
                    // if the value cannot be serialized, we catch here and return a placeholder value
                    builder.value("<unserializable>");
                }
            }
            builder.endArray();
            return builder;
        };
    }

    public ToXContentFragment getIgnoredValuesWriter() {
        return (builder, params) -> {
            builder.startArray(name);
            for (Object value : ignoredValues) {
                builder.value(value);
            }
            builder.endArray();
            return builder;
        };
    }

    public static DocumentField fromXContent(XContentParser parser) throws IOException {
        ensureExpectedToken(XContentParser.Token.FIELD_NAME, parser.currentToken(), parser);
        String fieldName = parser.currentName();
        XContentParser.Token token = parser.nextToken();
        ensureExpectedToken(XContentParser.Token.START_ARRAY, token, parser);
        List<Object> values = new ArrayList<>();
        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
            values.add(parseFieldsValue(parser));
        }
        return new DocumentField(fieldName, values);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DocumentField objects = (DocumentField) o;
        return Objects.equals(name, objects.name)
            && Objects.equals(values, objects.values)
            && Objects.equals(ignoredValues, objects.ignoredValues)
            && Objects.equals(lookupFields, objects.lookupFields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, values, ignoredValues, lookupFields);
    }

    @Override
    public String toString() {
        return "DocumentField{"
            + "name='"
            + name
            + '\''
            + ", values="
            + values
            + ", ignoredValues="
            + ignoredValues
            + ", lookupFields="
            + lookupFields
            + '}';
    }

}
