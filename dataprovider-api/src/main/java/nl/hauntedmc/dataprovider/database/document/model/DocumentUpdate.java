package nl.hauntedmc.dataprovider.database.document.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an update operation.
 * Typically, something like { "$set": { "score": 9999 } } in Mongo.
 */
public class DocumentUpdate {

    private final Map<String, Object> operations = new HashMap<>();

    /**
     * Sets a field to a new value.
     *
     * @param field the field name
     * @param value the new value
     * @return this update instance for chaining
     */
    public DocumentUpdate set(String field, Object value) {
        String validatedField = requireFieldName(field);
        operations.computeIfAbsent("$set", k -> new HashMap<String, Object>());
        @SuppressWarnings("unchecked")
        Map<String, Object> setMap = (Map<String, Object>) operations.get("$set");
        setMap.put(validatedField, value);
        return this;
    }

    /**
     * Increments a field by a given amount.
     *
     * @param field  the field name
     * @param amount the amount to increment
     * @return this update instance for chaining
     */
    public DocumentUpdate inc(String field, Number amount) {
        String validatedField = requireFieldName(field);
        Objects.requireNonNull(amount, "Increment amount cannot be null.");
        operations.computeIfAbsent("$inc", k -> new HashMap<String, Object>());
        @SuppressWarnings("unchecked")
        Map<String, Object> incMap = (Map<String, Object>) operations.get("$inc");
        incMap.put(validatedField, amount);
        return this;
    }

    /**
     * Returns an unmodifiable view of the update operations.
     *
     * @return a map representing the update operations
     */
    public Map<String, Object> toMap() {
        return Collections.unmodifiableMap(operations);
    }

    private static String requireFieldName(String field) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("Update field name cannot be null or blank.");
        }
        return field;
    }
}
