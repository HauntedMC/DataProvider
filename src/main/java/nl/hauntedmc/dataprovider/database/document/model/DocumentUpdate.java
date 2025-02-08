package nl.hauntedmc.dataprovider.database.document.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents an update operation.
 * Typically something like { "$set": { "score": 9999 } } in Mongo.
 */
public class DocumentUpdate {

    private final Map<String, Object> operations = new HashMap<>();

    /**
     * Set a field to a new value.
     */
    public DocumentUpdate set(String field, Object value) {
        // Merge into a "$set" sub-document
        operations.computeIfAbsent("$set", k -> new HashMap<String, Object>());
        Map<String, Object> setMap = (Map<String, Object>) operations.get("$set");
        setMap.put(field, value);
        return this;
    }

    /**
     * Increment a field by some amount
     */
    public DocumentUpdate inc(String field, Number amount) {
        operations.computeIfAbsent("$inc", k -> new HashMap<String, Object>());
        Map<String, Object> incMap = (Map<String, Object>) operations.get("$inc");
        incMap.put(field, amount);
        return this;
    }

    /**
     * Return the entire map of operations
     */
    public Map<String, Object> toMap() {
        return operations;
    }
}
