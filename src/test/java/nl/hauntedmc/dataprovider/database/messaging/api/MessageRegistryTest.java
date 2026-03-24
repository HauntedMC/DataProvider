package nl.hauntedmc.dataprovider.database.messaging.api;

import nl.hauntedmc.dataprovider.testutil.RecordingLoggerAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageRegistryTest {

    @Test
    void constructorRejectsNullLogger() {
        assertThrows(NullPointerException.class, () -> new MessageRegistry(null));
    }

    @Test
    void registerValidatesArgumentsAndRejectsConflictingType() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        MessageRegistry registry = new MessageRegistry(logger);

        assertThrows(IllegalArgumentException.class, () -> registry.register(" ", PingMessage.class));
        assertThrows(NullPointerException.class, () -> registry.register("ping", null));

        registry.register("ping", PingMessage.class);
        registry.register("ping", PingMessage.class);
        IllegalStateException conflict = assertThrows(
                IllegalStateException.class,
                () -> registry.register("ping", OtherMessage.class)
        );

        assertTrue(conflict.getMessage().contains("already registered"));
        assertTrue(logger.infoMessages().stream().anyMatch(m -> m.contains("Registered message type 'ping'")));
    }

    @Test
    void toJsonAndFromJsonRoundTrip() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        MessageRegistry registry = new MessageRegistry(logger);
        PingMessage message = new PingMessage("hello");

        String json = registry.toJson(message);
        PingMessage fromJson = registry.fromJson(json, PingMessage.class);

        assertNotNull(fromJson);
        assertEquals("ping", fromJson.getType());
        assertEquals("hello", fromJson.content);
    }

    @Test
    void parseHandlesMissingUnknownAndInvalidPayloads() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        MessageRegistry registry = new MessageRegistry(logger);
        registry.register("ping", PingMessage.class);

        assertNull(registry.parse("{\"payload\":\"x\"}"));
        assertNull(registry.parse("{\"type\":\"unknown\"}"));
        assertNull(registry.parse("{not-json"));

        assertTrue(logger.warnMessages().stream().anyMatch(m -> m.contains("without a 'type' field")));
        assertTrue(logger.warnMessages().stream().anyMatch(m -> m.contains("Unknown message type unknown")));
        assertTrue(logger.warnMessages().stream().anyMatch(m -> m.contains("Failed to parse message payload")));
    }

    @Test
    void parseResolvesRegisteredType() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        MessageRegistry registry = new MessageRegistry(logger);
        registry.register("ping", PingMessage.class);

        EventMessage parsed = registry.parse("{\"type\":\"ping\",\"content\":\"world\"}");

        assertInstanceOf(PingMessage.class, parsed);
        assertEquals("world", ((PingMessage) parsed).content);
    }

    private static final class PingMessage extends AbstractEventMessage {
        private String content;

        private PingMessage() {
            super("ping");
        }

        private PingMessage(String content) {
            super("ping");
            this.content = content;
        }
    }

    private static final class OtherMessage extends AbstractEventMessage {
        private OtherMessage() {
            super("other");
        }
    }
}
