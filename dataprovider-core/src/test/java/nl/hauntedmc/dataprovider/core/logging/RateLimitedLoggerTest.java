package nl.hauntedmc.dataprovider.core.logging;

import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitedLoggerTest {

    @Test
    void limitsRepeatedOutageMessagesUsingMonotonicTime() {
        AtomicLong now = new AtomicLong(100);
        RateLimitedLogger logger = new RateLimitedLogger(Duration.ofNanos(10), now::get);
        RecordingLoggerAdapter sink = new RecordingLoggerAdapter();

        logger.error(sink, "backend unavailable");
        logger.error(sink, "backend unavailable");
        now.addAndGet(9);
        logger.error(sink, "backend unavailable");
        now.incrementAndGet();
        logger.error(sink, "backend unavailable");

        assertEquals(2, sink.errorMessages().size());
    }
}
