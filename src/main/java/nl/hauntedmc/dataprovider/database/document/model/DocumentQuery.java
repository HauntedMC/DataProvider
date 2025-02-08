package nl.hauntedmc.dataprovider.database.document.model;

import java.util.HashMap;
import java.util.Map;

/**
 * A minimal DSL for building a "query" filter.
 * This can store conditions like { "_id" : "player123", "score" : { "$gte" : 1000 } }
 * in a vendor-neutral structure.
 *
 * The MongoDB implementation will convert it to Bson. Another doc DB might do something else.
 */
public class DocumentQuery {

    private final Map<String, Object> criteria = new HashMap<>();

    /**
     * Put a key-value pair in the query.
     */
    public DocumentQuery eq(String field, Object value) {
        // e.g. criteria.put(field, Map.of("$eq", value)) or just store it directly
        criteria.put(field, value);
        return this;
    }

    /**
     * For advanced comparisons, you might define methods for $gt, $gte, etc.
     */
    public DocumentQuery gte(String field, Object value) {
        // sample approach
        Map<String, Object> op = new HashMap<>();
        op.put("$gte", value);
        criteria.put(field, op);
        return this;
    }

    /**
     * Or you might store raw structures
     */
    public DocumentQuery raw(String field, Object expression) {
        criteria.put(field, expression);
        return this;
    }

    public Map<String, Object> toMap() {
        return criteria;
    }
}
