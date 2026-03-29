package nl.hauntedmc.dataprovider.logging.adapters;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class Slf4jLoggerAdapterTest {

    @Test
    void forwardsAllLogCallsToSlf4jLogger() {
        Logger logger = mock(Logger.class);
        RuntimeException throwable = new RuntimeException("boom");
        Slf4jLoggerAdapter adapter = new Slf4jLoggerAdapter(logger);

        adapter.info("info");
        adapter.warn("warn");
        adapter.error("error");
        adapter.info("info+t", throwable);
        adapter.warn("warn+t", throwable);
        adapter.error("error+t", throwable);

        verify(logger).info("info");
        verify(logger).warn("warn");
        verify(logger).error("error");
        verify(logger).info("info+t", throwable);
        verify(logger).warn("warn+t", throwable);
        verify(logger).error("error+t", throwable);
    }
}
