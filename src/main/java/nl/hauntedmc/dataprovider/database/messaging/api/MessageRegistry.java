package nl.hauntedmc.dataprovider.database.messaging.api;

import com.google.gson.*;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Instance-scoped Gson + type registry for EventMessage classes.
 * <p>
 * Most integrations using typed publish/subscribe do not need explicit type registration,
 * because message classes are provided directly at subscription time.
 * Registration is only required when using {@link #parse(String)} for dynamic payload parsing.
 */
public final class MessageRegistry {
    private final Gson gson = new Gson();
    private final Map<String, Class<? extends EventMessage>> types = new ConcurrentHashMap<>();
    private final ILoggerAdapter logger;

    public MessageRegistry(ILoggerAdapter logger) {
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    /**
     * Register a message type key before using it.
     * @throws IllegalStateException if the type is already registered with a different class
     */
    public void register(String type, Class<? extends EventMessage> cls) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Message type cannot be null or blank.");
        }
        Objects.requireNonNull(cls, "Message class cannot be null.");

        Class<? extends EventMessage> existing = types.putIfAbsent(type, cls);
        if (existing != null && !existing.equals(cls)) {
            throw new IllegalStateException("Message type '" + type + "' is already registered by " + existing.getName());
        }
        logger.info("Registered message type '" + type + "' for class " + cls.getName());
    }

    /** Serialize any EventMessage to JSON. */
    public String toJson(EventMessage msg) {
        return gson.toJson(msg);
    }

    /** Deserialize a known subclass from JSON. */
    public <T extends EventMessage> T fromJson(String json, Class<T> cls) {
        return gson.fromJson(json, cls);
    }

    /** Parse JSON, look up `type` field, and return correct subclass. */
    public EventMessage parse(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("type")) {
                logger.warn("Encountered message without a 'type' field during parse.");
                return null;
            }
            String type = obj.get("type").getAsString();
            Class<? extends EventMessage> cls = types.get(type);
            if (cls == null) {
                logger.warn("Unknown message type " + type + " encountered during parse");
                return null;
            }
            return gson.fromJson(obj, cls);
        } catch (Exception e) {
            logger.warn("Failed to parse message payload: " + e.getMessage());
            return null;
        }
    }
}
