package nl.hauntedmc.dataprovider.database.document.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents an update operation.
 * Typically something like { "$set": { "score": 9999 } } in Mongo.
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
        operations.computeIfAbsent("$set", k -> new HashMap<String, Object>());
        @SuppressWarnings("unchecked")
        Map<String, Object> setMap = (Map<String, Object>) operations.get("$set");
        setMap.put(field, value);
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
        operations.computeIfAbsent("$inc", k -> new HashMap<String, Object>());
        @SuppressWarnings("unchecked")
        Map<String, Object> incMap = (Map<String, Object>) operations.get("$inc");
        incMap.put(field, amount);
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
}
