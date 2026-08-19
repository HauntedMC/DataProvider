package nl.hauntedmc.dataprovider.database.messaging.api;

import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageRegistryConvenienceTest {

    @Test
    void registrationIntrospectionReturnsImmutableSnapshots() {
        MessageRegistry registry = new MessageRegistry(LoggerAdapterTestStub.INSTANCE);

        assertEquals(0, registry.registrationCount());
        assertFalse(registry.isRegistered("test"));
        registry.register("test", TestMessage.class);

        assertTrue(registry.isRegistered("test"));
        assertEquals(1, registry.registrationCount());
        Map<String, Class<? extends EventMessage>> snapshot = registry.registeredTypes();
        assertEquals(TestMessage.class, snapshot.get("test"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("other", TestMessage.class));

        registry.clear();
        assertFalse(registry.isRegistered("test"));
        assertEquals(0, registry.registrationCount());
        assertEquals(TestMessage.class, snapshot.get("test"));
    }

    private record TestMessage(String type) implements EventMessage {
        private TestMessage() {
            this("test");
        }

        @Override public String getType() { return type; }
    }

    private enum LoggerAdapterTestStub implements LoggerAdapter {
        INSTANCE;

        @Override
        public void log(nl.hauntedmc.dataprovider.logging.LogLevel level, String message, Throwable throwable) {
        }
    }
}
