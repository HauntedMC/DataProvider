package nl.hauntedmc.dataprovider.database.messaging.api;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import nl.hauntedmc.dataprovider.DataProvider;

/**
 * Central Gson instance + type registry for all EventMessage classes.
 */
public final class MessageRegistry {
    private static final Gson GSON = new Gson();
    private static final Map<String, Class<? extends EventMessage>> TYPES = new ConcurrentHashMap<>();

    private MessageRegistry() {}

    /**
     * Register a message type key before using it.
     * @throws IllegalStateException if the type is already registered with a different class
     */
    public static void register(String type, Class<? extends EventMessage> cls) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Message type cannot be null or blank.");
        }
        Objects.requireNonNull(cls, "Message class cannot be null.");

        Class<? extends EventMessage> existing = TYPES.putIfAbsent(type, cls);
        if (existing != null && !existing.equals(cls)) {
            throw new IllegalStateException("Message type '" + type + "' is already registered by " + existing.getName());
        }
        DataProvider.getLogger().info("Registered message type '" + type + "' for class " + cls.getName());
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
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("type")) {
                DataProvider.getLogger().warn("Encountered message without a 'type' field during parse.");
                return null;
            }
            String type = obj.get("type").getAsString();
            Class<? extends EventMessage> cls = TYPES.get(type);
            if (cls == null) {
                DataProvider.getLogger().warn("Unknown message type " + type + " encountered during parse");
                return null;
            }
            return GSON.fromJson(obj, cls);
        } catch (Exception e) {
            DataProvider.getLogger().warn("Failed to parse message payload: " + e.getMessage());
            return null;
        }
    }
}
