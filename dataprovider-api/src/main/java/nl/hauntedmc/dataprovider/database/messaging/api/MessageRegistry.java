package nl.hauntedmc.dataprovider.database.messaging.api;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;

import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Instance-scoped Gson + type registry for EventMessage classes.
 * <p>
 * Most integrations using typed publish/subscribe do not need explicit type registration,
 * because message classes are provided directly at subscription time.
 * Registration is only required when using {@link #parse(String)} for dynamic payload parsing.
 */
public final class MessageRegistry {
    private static final Pattern TYPE_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    private static final int MAX_JSON_CHARACTERS = 1_048_576;
    private static final int MAX_NESTING_DEPTH = 64;
    private static final int MAX_GRAPH_NODES = 10_000;

    private final Gson gson = new Gson();
    private final Map<String, Class<? extends EventMessage>> types = new ConcurrentHashMap<>();
    private final LoggerAdapter logger;

    public MessageRegistry(LoggerAdapter logger) {
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    /**
     * Register a message type key before using it.
     * @throws IllegalStateException if the type is already registered with a different class
     */
    public void register(String type, Class<? extends EventMessage> cls) {
        validateType(type);
        Objects.requireNonNull(cls, "Message class cannot be null.");

        Class<? extends EventMessage> existing = types.putIfAbsent(type, cls);
        if (existing != null && !existing.equals(cls)) {
            throw new IllegalStateException("Message type '" + type + "' is already registered by " + existing.getName());
        }
        logger.info("Registered message type '" + type + "' for class " + cls.getName());
    }

    /**
     * Remove a registration only when it still belongs to the supplied class.
     *
     * @return whether the registration was removed
     */
    public boolean unregister(String type, Class<? extends EventMessage> cls) {
        String validatedType = validateType(type);
        Objects.requireNonNull(cls, "Message class cannot be null.");
        boolean removed = types.remove(validatedType, cls);
        if (removed) {
            logger.info("Unregistered message type '" + validatedType + "'.");
        }
        return removed;
    }

    /** Remove all dynamic type registrations and release their class references. */
    public void clear() {
        types.clear();
    }

    /** Serialize any EventMessage to JSON. */
    public String toJson(EventMessage msg) {
        Objects.requireNonNull(msg, "Message cannot be null.");
        validateType(msg.getType());
        validateObjectGraph(msg, new IdentityHashMap<>(), 0, new int[] {0});
        LimitedStringWriter writer = new LimitedStringWriter(MAX_JSON_CHARACTERS);
        gson.toJson(msg, msg.getClass(), writer);
        return writer.toString();
    }

    /** Deserialize a known subclass from JSON. */
    public <T extends EventMessage> T fromJson(String json, Class<T> cls) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Message JSON cannot be null or blank.");
        }
        Objects.requireNonNull(cls, "Message class cannot be null.");
        validateJson(json);
        return gson.fromJson(json, cls);
    }

    /** Deserialize a known subclass and require its wire type to match the subscription contract. */
    public <T extends EventMessage> T fromJson(String json, Class<T> cls, String expectedType) {
        String validatedExpectedType = validateType(expectedType);
        T message = fromJson(json, cls);
        if (message == null) {
            throw new JsonParseException("Message JSON resolved to null.");
        }
        String actualType = validateType(message.getType());
        if (!validatedExpectedType.equals(actualType)) {
            throw new JsonParseException(
                    "Message type '" + actualType + "' does not match expected type '" + validatedExpectedType + "'."
            );
        }
        return message;
    }

    /** Parse JSON, look up `type` field, and return correct subclass. */
    public EventMessage parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Message JSON cannot be null or blank.");
        }
        validateJson(json);
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new JsonParseException("Message JSON must contain an object.");
        }
        JsonObject object = parsed.getAsJsonObject();
        JsonElement typeElement = object.get("type");
        if (typeElement == null || !typeElement.isJsonPrimitive()
                || !typeElement.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("Message JSON must contain a string 'type' field.");
        }
        String type = validateType(typeElement.getAsString());
        Class<? extends EventMessage> messageClass = types.get(type);
        if (messageClass == null) {
            throw new JsonParseException("Message type '" + type + "' is not registered.");
        }
        return fromJson(json, messageClass, type);
    }

    private static void validateJson(String json) {
        if (json.length() > MAX_JSON_CHARACTERS) {
            throw new IllegalArgumentException(
                    "Message JSON cannot exceed " + MAX_JSON_CHARACTERS + " characters."
            );
        }
        int depth = 0;
        int nodes = 0;
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            while (true) {
                JsonToken token = reader.peek();
                switch (token) {
                    case BEGIN_ARRAY -> {
                        reader.beginArray();
                        depth++;
                        nodes++;
                    }
                    case BEGIN_OBJECT -> {
                        reader.beginObject();
                        depth++;
                        nodes++;
                    }
                    case END_ARRAY -> {
                        reader.endArray();
                        depth--;
                    }
                    case END_OBJECT -> {
                        reader.endObject();
                        depth--;
                    }
                    case NAME -> {
                        reader.nextName();
                        nodes++;
                    }
                    case STRING -> {
                        reader.nextString();
                        nodes++;
                    }
                    case NUMBER -> {
                        reader.nextString();
                        nodes++;
                    }
                    case BOOLEAN -> {
                        reader.nextBoolean();
                        nodes++;
                    }
                    case NULL -> {
                        reader.nextNull();
                        nodes++;
                    }
                    case END_DOCUMENT -> {
                        return;
                    }
                }
                if (depth > MAX_NESTING_DEPTH) {
                    throw new IllegalArgumentException(
                            "Message JSON cannot exceed " + MAX_NESTING_DEPTH + " nested levels."
                    );
                }
                if (nodes > MAX_GRAPH_NODES) {
                    throw new IllegalArgumentException(
                            "Message JSON cannot exceed " + MAX_GRAPH_NODES + " values."
                    );
                }
            }
        } catch (IOException | IllegalStateException failure) {
            throw new JsonParseException("Invalid message JSON.", failure);
        }
    }

    /** Validate and return a logical wire message type. */
    public static String validateType(String type) {
        if (type == null || !TYPE_PATTERN.matcher(type).matches()) {
            throw new IllegalArgumentException(
                    "Message type contains unsupported characters or has an invalid length."
            );
        }
        return type;
    }

    private static void validateObjectGraph(
            Object value,
            IdentityHashMap<Object, Boolean> active,
            int depth,
            int[] nodes
    ) {
        if (value == null || isLeafValue(value)) {
            return;
        }
        if (depth > MAX_NESTING_DEPTH) {
            throw new IllegalArgumentException(
                    "Message objects cannot exceed " + MAX_NESTING_DEPTH + " nested levels."
            );
        }
        if (++nodes[0] > MAX_GRAPH_NODES) {
            throw new IllegalArgumentException(
                    "Message objects cannot exceed " + MAX_GRAPH_NODES + " values."
            );
        }
        if (active.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Message objects cannot contain reference cycles.");
        }
        try {
            if (value instanceof Map<?, ?> map) {
                map.forEach((key, nested) -> {
                    validateObjectGraph(key, active, depth + 1, nodes);
                    validateObjectGraph(nested, active, depth + 1, nodes);
                });
                return;
            }
            if (value instanceof Iterable<?> iterable) {
                iterable.forEach(nested -> validateObjectGraph(nested, active, depth + 1, nodes));
                return;
            }
            if (value.getClass().isArray()) {
                for (int index = 0; index < Array.getLength(value); index++) {
                    validateObjectGraph(Array.get(value, index), active, depth + 1, nodes);
                }
                return;
            }
            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()
                            || !field.trySetAccessible()) {
                        continue;
                    }
                    try {
                        validateObjectGraph(field.get(value), active, depth + 1, nodes);
                    } catch (IllegalAccessException failure) {
                        throw new IllegalArgumentException("Unable to inspect the message object graph.", failure);
                    }
                }
            }
        } finally {
            active.remove(value);
        }
    }

    private static boolean isLeafValue(Object value) {
        Class<?> type = value.getClass();
        return type.isPrimitive()
                || value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || value instanceof Class<?>
                || type.getPackageName().startsWith("java.time");
    }

    private static final class LimitedStringWriter extends Writer {
        private final int maximumCharacters;
        private final StringBuilder output = new StringBuilder();

        private LimitedStringWriter(int maximumCharacters) {
            this.maximumCharacters = maximumCharacters;
        }

        @Override
        public void write(char[] characters, int offset, int length) {
            requireCapacity(length);
            output.append(characters, offset, length);
        }

        @Override
        public void write(String value, int offset, int length) {
            requireCapacity(length);
            output.append(value, offset, offset + length);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return output.toString();
        }

        private void requireCapacity(int additionalCharacters) {
            if (output.length() > maximumCharacters - additionalCharacters) {
                throw new IllegalArgumentException(
                        "Serialized messages cannot exceed " + maximumCharacters + " characters."
                );
            }
        }
    }
}
