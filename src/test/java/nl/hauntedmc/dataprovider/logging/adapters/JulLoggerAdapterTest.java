package nl.hauntedmc.dataprovider.logging.adapters;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class JulLoggerAdapterTest {

    @Test
    void forwardsMessagesWithExpectedLevels() {
        Logger logger = Logger.getLogger("JulLoggerAdapterTest-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        RecordingHandler handler = new RecordingHandler();
        logger.addHandler(handler);

        JulLoggerAdapter adapter = new JulLoggerAdapter(logger);
        RuntimeException throwable = new RuntimeException("boom");

        adapter.info("info");
        adapter.warn("warn");
        adapter.error("error");
        adapter.info("info-throwable", throwable);
        adapter.warn("warn-throwable", throwable);
        adapter.error("error-throwable", throwable);

        assertEquals(6, handler.records.size());
        assertEquals(Level.INFO, handler.records.get(0).getLevel());
        assertEquals(Level.WARNING, handler.records.get(1).getLevel());
        assertEquals(Level.SEVERE, handler.records.get(2).getLevel());
        assertSame(throwable, handler.records.get(3).getThrown());
        assertSame(throwable, handler.records.get(4).getThrown());
        assertSame(throwable, handler.records.get(5).getThrown());
    }

    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
