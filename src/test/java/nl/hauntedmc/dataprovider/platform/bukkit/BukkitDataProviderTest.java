package nl.hauntedmc.dataprovider.platform.bukkit;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
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
        DataProvider original = swapStaticDataProvider(null);
        try {
            assertThrows(IllegalStateException.class, BukkitDataProvider::getDataProviderAPI);
        } finally {
            swapStaticDataProvider(original);
        }
    }

    @Test
    void getDataProviderApiReturnsFacadeWhenInitialized() throws ReflectiveOperationException {
        DataProvider provider = mock(DataProvider.class);
        DataProviderHandler handler = mock(DataProviderHandler.class);
        when(provider.getDataProviderHandler()).thenReturn(handler);

        DataProvider original = swapStaticDataProvider(provider);
        try {
            DataProviderAPI api = BukkitDataProvider.getDataProviderAPI();
            assertNotNull(api);
            api.unregisterAllDatabases();
            verify(handler).unregisterAllDatabases();
        } finally {
            swapStaticDataProvider(original);
        }
    }

    private static DataProvider swapStaticDataProvider(DataProvider replacement) throws ReflectiveOperationException {
        Field field = BukkitDataProvider.class.getDeclaredField("dataProvider");
        field.setAccessible(true);
        DataProvider previous = (DataProvider) field.get(null);
        field.set(null, replacement);
        return previous;
    }
}
