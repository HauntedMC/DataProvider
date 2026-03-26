package nl.hauntedmc.dataprovider.platform.bukkit;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.platform.common.lifecycle.PlatformDataProviderRuntime;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BukkitDataProviderTest {

    @Test
    void getDataProviderApiThrowsWhenNotInitialized() throws ReflectiveOperationException {
        PlatformDataProviderRuntime runtime = resolveRuntime();
        runtime.stop(mock(ILoggerAdapter.class));

        assertThrows(IllegalStateException.class, BukkitDataProvider::getDataProviderAPI);
    }

    @Test
    void getDataProviderApiReturnsFacadeWhenInitialized() throws ReflectiveOperationException {
        DataProvider provider = mock(DataProvider.class);
        DataProviderHandler handler = mock(DataProviderHandler.class);
        when(provider.getDataProviderHandler()).thenReturn(handler);

        PlatformDataProviderRuntime runtime = resolveRuntime();
        runtime.stop(mock(ILoggerAdapter.class));
        runtime.start(() -> provider, mock(ILoggerAdapter.class));
        try {
            DataProviderAPI api = BukkitDataProvider.getDataProviderAPI();
            assertNotNull(api);
            api.unregisterAllDatabases();
            verify(handler).unregisterAllDatabases();
        } finally {
            runtime.stop(mock(ILoggerAdapter.class));
        }
    }

    private static PlatformDataProviderRuntime resolveRuntime() throws ReflectiveOperationException {
        Field field = BukkitDataProvider.class.getDeclaredField("RUNTIME");
        field.setAccessible(true);
        return (PlatformDataProviderRuntime) field.get(null);
    }
}
