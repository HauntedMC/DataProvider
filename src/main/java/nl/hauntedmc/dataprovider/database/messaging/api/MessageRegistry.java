package nl.hauntedmc.dataprovider.database.messaging.api;

import com.google.gson.*;
import java.util.*;

import nl.hauntedmc.dataprovider.DataProvider;

/**
 * Central Gson instance + type registry for all EventMessage classes.
 */
public final class MessageRegistry {
    private static final Gson GSON = new Gson();
    private static final Map<String, Class<? extends EventMessage>> TYPES = new HashMap<>();

    private MessageRegistry() {}

    /**
     * Register a message type key before using it.
     * @throws IllegalStateException if the type is already registered with a different class
     */
    public static synchronized void register(String type, Class<? extends EventMessage> cls) {
        Class<? extends EventMessage> existing = TYPES.put(type, cls);
        if (existing != null) {
            DataProvider.getLogger().info("Overwriting message type '" + type
                    + "' previously mapped to " + existing.getName()
                    + " with " + cls.getName());
        } else {
            DataProvider.getLogger().info("Registered message type '" + type
                    + "' for class " + cls.getName());
        }
    }

    /** Serialize any EventMessage to JSON. */
    public static String toJson(EventMessage msg) {
        return GSON.toJson(msg);
    }

    /** Deserialize a known subclass from JSON. */
    public static <T extends EventMessage> T fromJson(String json, Class<T> cls) {
        return GSON.fromJson(json, cls);
    }

    /** Parse JSON, look up `type` field, and return correct subclass. */
    public static EventMessage parse(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        String type = obj.get("type").getAsString();
        Class<? extends EventMessage> cls = TYPES.get(type);
        if (cls == null) {
            DataProvider.getLogger().warn("Unknown message type " +type + " encountered during parse");
            return null;
        }
        return GSON.fromJson(obj, cls);
    }
}
