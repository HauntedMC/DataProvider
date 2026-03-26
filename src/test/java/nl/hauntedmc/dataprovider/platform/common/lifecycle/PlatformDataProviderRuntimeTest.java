package nl.hauntedmc.dataprovider.platform.common.lifecycle;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
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
        ILoggerAdapter logger = mock(ILoggerAdapter.class);
        DataProvider previousProvider = mock(DataProvider.class);
        DataProvider replacementProvider = mock(DataProvider.class);

        runtime.start(() -> previousProvider, logger);
        runtime.start(() -> replacementProvider, logger);

        verify(logger).warn("Detected leftover DataProvider instance during enable; forcing cleanup first.");
        verify(previousProvider).shutdownAllDatabases();
    }

    @Test
    void stopShutsDownActiveProviderAndMakesApiUnavailable() {
        PlatformDataProviderRuntime runtime = new PlatformDataProviderRuntime();
        ILoggerAdapter logger = mock(ILoggerAdapter.class);
        DataProvider provider = mock(DataProvider.class);

        runtime.start(() -> provider, logger);
        runtime.stop(logger);

        verify(provider).shutdownAllDatabases();
        assertThrows(IllegalStateException.class, runtime::getDataProviderAPI);
    }

    @Test
    void getDataProviderApiReturnsFacadeForActiveProvider() {
        PlatformDataProviderRuntime runtime = new PlatformDataProviderRuntime();
        ILoggerAdapter logger = mock(ILoggerAdapter.class);
        DataProvider provider = mock(DataProvider.class);
        DataProviderHandler handler = mock(DataProviderHandler.class);
        when(provider.getDataProviderHandler()).thenReturn(handler);

        runtime.start(() -> provider, logger);
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
}
