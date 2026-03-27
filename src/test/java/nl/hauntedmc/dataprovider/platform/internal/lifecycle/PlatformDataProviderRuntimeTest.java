package nl.hauntedmc.dataprovider.platform.internal.lifecycle;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
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
    void getDataProviderApiReturnsFacadeForActiveProvider() {
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
            api.unregisterAllDatabases();
            verify(handler).unregisterAllDatabases();
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
}
