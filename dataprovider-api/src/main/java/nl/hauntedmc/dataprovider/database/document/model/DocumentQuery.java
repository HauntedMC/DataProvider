package nl.hauntedmc.dataprovider.database.document.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A minimal DSL for building a "query" filter.
 * This can store conditions like { "_id" : "player123", "score" : { "$gte" : 1000 } }
 * in a vendor–neutral structure.
 */
public class DocumentQuery {

    private final Map<String, Object> criteria = new HashMap<>();
    private boolean explicitMatchAll;

    /** Creates an explicit match-all query for intentionally broad operations. */
    public static DocumentQuery all() {
        DocumentQuery query = new DocumentQuery();
        query.explicitMatchAll = true;
        return query;
    }

    /**
     * Put a key–value pair in the query.
     *
     * @param field the field name
     * @param value the value to match
     * @return this query instance for chaining
     */
    public DocumentQuery eq(String field, Object value) {
        return comparison(field, "$eq", value, null);
    }

    /** Adds a not-equal condition for a field. */
    public DocumentQuery ne(String field, Object value) {
        return comparison(field, "$ne", value, null);
    }

    /** Adds a greater-than condition for a field. */
    public DocumentQuery gt(String field, Object value) {
        return comparison(field, "$gt", value, "Greater-than value cannot be null.");
    }

    /**
     * Adds a greater–than–or–equal condition for a field.
     *
     * @param field the field name
     * @param value the threshold value
     * @return this query instance for chaining
     */
    public DocumentQuery gte(String field, Object value) {
        return comparison(field, "$gte", value, "Greater-than-or-equal value cannot be null.");
    }

    /** Adds a less-than condition for a field. */
    public DocumentQuery lt(String field, Object value) {
        return comparison(field, "$lt", value, "Less-than value cannot be null.");
    }

    /** Adds a less-than-or-equal condition for a field. */
    public DocumentQuery lte(String field, Object value) {
        return comparison(field, "$lte", value, "Less-than-or-equal value cannot be null.");
    }

    /**
     * Returns a deeply immutable snapshot of the query criteria.
     *
     * @return a map representing the query criteria
     */
    public Map<String, Object> toMap() {
        return DocumentValueSnapshot.map(criteria);
    }

    /** Whether at least one field criterion is present. */
    public boolean hasCriteria() {
        return !criteria.isEmpty();
    }

    /** Whether this empty query was deliberately constructed through {@link #all()}. */
    public boolean isExplicitMatchAll() {
        return explicitMatchAll && criteria.isEmpty();
    }

    private DocumentQuery comparison(String field, String operator, Object value, String nullMessage) {
        String validatedField = requireFieldName(field);
        if (nullMessage != null) {
            Objects.requireNonNull(value, nullMessage);
        }
        explicitMatchAll = false;
        Map<String, Object> expression = new HashMap<>();
        expression.put(operator, DocumentValueSnapshot.value(value));
        criteria.put(validatedField, expression);
        return this;
    }

    private static String requireFieldName(String field) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("Query field name cannot be null or blank.");
        }
        String normalized = field.trim();
        if (normalized.startsWith("$") || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Query field names cannot be operators or contain null characters.");
        }
        return normalized;
    }
}
