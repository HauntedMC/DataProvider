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
        String validatedField = requireFieldName(field);
        explicitMatchAll = false;
        criteria.put(validatedField, DocumentValueSnapshot.value(value));
        return this;
    }

    /**
     * Adds a greater–than–or–equal condition for a field.
     *
     * @param field the field name
     * @param value the threshold value
     * @return this query instance for chaining
     */
    public DocumentQuery gte(String field, Object value) {
        String validatedField = requireFieldName(field);
        Objects.requireNonNull(value, "Greater-than-or-equal value cannot be null.");
        explicitMatchAll = false;
        Map<String, Object> op = new HashMap<>();
        op.put("$gte", DocumentValueSnapshot.value(value));
        criteria.put(validatedField, op);
        return this;
    }

    /**
     * Adds a raw expression for a field.
     *
     * @param field      the field name
     * @param expression the expression object
     * @return this query instance for chaining
     */
    public DocumentQuery raw(String field, Object expression) {
        String validatedField = requireFieldName(field);
        Objects.requireNonNull(expression, "Raw expression cannot be null.");
        explicitMatchAll = false;
        criteria.put(validatedField, DocumentValueSnapshot.value(expression));
        return this;
    }

    /**
     * Returns a deeply immutable snapshot of the query criteria.
     *
     * @return a map representing the query criteria
     */
    public Map<String, Object> toMap() {
        return DocumentValueSnapshot.map(criteria);
    }

    /** Whether this empty query was deliberately constructed through {@link #all()}. */
    public boolean isExplicitMatchAll() {
        return explicitMatchAll && criteria.isEmpty();
    }

    private static String requireFieldName(String field) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("Query field name cannot be null or blank.");
        }
        return field;
    }
}
