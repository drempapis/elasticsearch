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
    private static final long LIST_SHALLOW_SIZE = RamUsageEstimator.shallowSizeOfInstance(ArrayList.class);
    private static final long LOOKUP_FIELD_SHALLOW_SIZE = RamUsageEstimator.shallowSizeOfInstance(LookupField.class);
    private static final long BOXED_PRIMITIVE_SIZE = RamUsageEstimator.shallowSizeOfInstance(Long.class);
    private static final long DEFAULT_VALUE_RAM_BYTES = 32L;

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

    /**
     * Estimates the retained heap of this field: its shallow size plus the field name and, recursively,
     * its values and ignored values. This is used to price circuit-breaker accounting on the deserialized
     * object graph rather than on the much smaller serialized (wire) form, which badly undercounts memory
     * when a hit carries many extracted fields (e.g. a {@code fields:[*]} request).
     *
     * <p>The estimate is approximate: it covers the common scalar value types precisely and falls back to a
     * conservative constant for structured values whose layout is not walked.
     */
    public long ramBytesUsed() {
        long size = SHALLOW_SIZE;
        size += RamUsageEstimator.sizeOf(name);
        size += ramBytesUsedByValues(values);
        size += ramBytesUsedByValues(ignoredValues);
        size += LIST_SHALLOW_SIZE + RamUsageEstimator.NUM_BYTES_ARRAY_HEADER + (long) lookupFields.size()
            * (RamUsageEstimator.NUM_BYTES_OBJECT_REF + LOOKUP_FIELD_SHALLOW_SIZE);
        return size;
    }

    private static long ramBytesUsedByValues(List<Object> values) {
        long size = LIST_SHALLOW_SIZE + RamUsageEstimator.NUM_BYTES_ARRAY_HEADER + (long) values.size()
            * RamUsageEstimator.NUM_BYTES_OBJECT_REF;
        for (Object value : values) {
            size += estimateValueRamBytes(value);
        }
        return RamUsageEstimator.alignObjectSize(size);
    }

    private static long estimateValueRamBytes(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof String s) {
            return RamUsageEstimator.sizeOf(s);
        }
        if (value instanceof byte[] b) {
            return RamUsageEstimator.sizeOf(b);
        }
        if (value instanceof Long
            || value instanceof Double
            || value instanceof Integer
            || value instanceof Float
            || value instanceof Short
            || value instanceof Byte
            || value instanceof Boolean
            || value instanceof Character) {
            return BOXED_PRIMITIVE_SIZE;
        }
        return RamUsageEstimator.sizeOfObject(value, DEFAULT_VALUE_RAM_BYTES);
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
