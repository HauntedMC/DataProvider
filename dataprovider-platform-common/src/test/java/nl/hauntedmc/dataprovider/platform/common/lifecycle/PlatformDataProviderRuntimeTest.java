package nl.hauntedmc.dataprovider.platform.common.lifecycle;

import nl.hauntedmc.dataprovider.core.DataProvider;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformDataProviderRuntimeTest {

    @Test
    void startShutsDownLeftoverProviderBeforeReplacing() {
        PlatformDataProviderRuntime runtime = new PlatformDataProviderRuntime();
        LoggerAdapter logger = mock(LoggerAdapter.class);
        DataProvider previousProvider = mock(DataProvider.class);
        DataProvider replacementProvider = mock(DataProvider.class);

        runtime.start(() -> previousProvider, provider -> {
        }, logger);
        runtime.start(() -> replacementProvider, provider -> {
        }, logger);

        verify(logger).warn("Detected leftover DataProvider instance during enable; forcing cleanup first.");
        verify(previousProvider).shutdownAllDatabases();
    }

    @Test
    void stopShutsDownActiveProviderAndMakesApiUnavailable() {
        PlatformDataProviderRuntime runtime = new PlatformDataProviderRuntime();
        LoggerAdapter logger = mock(LoggerAdapter.class);
        DataProvider provider = mock(DataProvider.class);

        runtime.start(() -> provider, created -> {
        }, logger);
        runtime.stop(logger);

        verify(provider).shutdownAllDatabases();
        assertThrows(IllegalStateException.class, runtime::getDataProviderAPI);
    }

    @Test
    void getDataProviderApiReturnsUnboundGatewayForActiveProvider() {
        PlatformDataProviderRuntime runtime = new PlatformDataProviderRuntime();
        LoggerAdapter logger = mock(LoggerAdapter.class);
        DataProvider provider = mock(DataProvider.class);
        DataProviderHandler handler = mock(DataProviderHandler.class);
        when(provider.getDataProviderHandler()).thenReturn(handler);

        runtime.start(() -> provider, created -> {
        }, logger);
        try {
            DataProviderAPI api = runtime.getDataProviderAPI();
            assertNotNull(api);
            assertThrows(IllegalStateException.class, api::unregisterAllDatabases);
        } finally {
            runtime.stop(logger);
        }
    }

    @Test
    void getDataProviderApiThrowsWhenNotStarted() {
        PlatformDataProviderRuntime runtime = new PlatformDataProviderRuntime();
        assertThrows(IllegalStateException.class, runtime::getDataProviderAPI);
    }

    @Test
    void startRollsBackProviderWhenInitializerFails() {
        PlatformDataProviderRuntime runtime = new PlatformDataProviderRuntime();
        LoggerAdapter logger = mock(LoggerAdapter.class);
        DataProvider provider = mock(DataProvider.class);

        assertThrows(
                IllegalStateException.class,
                () -> runtime.start(
                        () -> provider,
                        created -> {
                            throw new IllegalStateException("startup failed");
                        },
                        logger
                )
        );

        verify(provider).shutdownAllDatabases();
        assertThrows(IllegalStateException.class, runtime::getDataProviderAPI);
    }

    @Test
    void failedStopRetainsTheProviderSoCleanupCanBeRetried() {
        PlatformDataProviderRuntime runtime = new PlatformDataProviderRuntime();
        LoggerAdapter logger = mock(LoggerAdapter.class);
        DataProvider provider = mock(DataProvider.class);
        doThrow(new IllegalStateException("cleanup failed"))
                .doNothing()
                .when(provider).shutdownAllDatabases();

        runtime.start(() -> provider, created -> {
        }, logger);
        assertThrows(IllegalStateException.class, () -> runtime.stop(logger));
        runtime.stop(logger);

        verify(provider, times(2)).shutdownAllDatabases();
    }

    @Test
    void failedLeftoverCleanupAbortsReplacementStartup() {
        PlatformDataProviderRuntime runtime = new PlatformDataProviderRuntime();
        LoggerAdapter logger = mock(LoggerAdapter.class);
        DataProvider previous = mock(DataProvider.class);
        DataProvider replacement = mock(DataProvider.class);
        AtomicBoolean replacementCreated = new AtomicBoolean();
        doThrow(new AssertionError("cleanup failed")).when(previous).shutdownAllDatabases();

        runtime.start(() -> previous, created -> {
        }, logger);
        assertThrows(AssertionError.class, () -> runtime.start(() -> {
            replacementCreated.set(true);
            return replacement;
        }, created -> {
        }, logger));

        assertFalse(replacementCreated.get());
    }

    @Test
    void failedStartupRollbackRetainsThePartialProviderForLaterCleanup() {
        PlatformDataProviderRuntime runtime = new PlatformDataProviderRuntime();
        LoggerAdapter logger = mock(LoggerAdapter.class);
        DataProvider provider = mock(DataProvider.class);
        doThrow(new IllegalStateException("rollback failed"))
                .doNothing()
                .when(provider).shutdownAllDatabases();

        IllegalStateException startupFailure = assertThrows(IllegalStateException.class, () ->
                runtime.start(() -> provider, created -> {
                    throw new IllegalStateException("startup failed");
                }, logger));
        assertEquals(1, startupFailure.getSuppressed().length);

        runtime.stop(logger);
        verify(provider, times(2)).shutdownAllDatabases();
    }
}
