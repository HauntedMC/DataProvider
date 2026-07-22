package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.core.identity.CallerContext;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.exception.ProviderClosedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HandlerLifecycleExceptionTest {

    @Test
    void strictMethodsUseStructuredClosureWhileLegacyMethodsRemainCompatible() {
        DataProviderRegistry registry = mock(DataProviderRegistry.class);
        when(registry.isClosed()).thenReturn(true);
        ClassLoader pluginLoader = new ClassLoader() { };
        DataProviderHandler handler = new DataProviderHandler(
                registry,
                () -> new CallerContext("plugin", pluginLoader),
                new RecordingLoggerAdapter(),
                getClass().getClassLoader()
        );

        assertThrows(IllegalStateException.class,
                () -> handler.registerDatabase(DatabaseType.MYSQL, "default"));
        assertThrows(IllegalStateException.class,
                () -> handler.getRegisteredDatabase(DatabaseType.MYSQL, "default"));

        ProviderClosedException registration = assertThrows(ProviderClosedException.class,
                () -> handler.registerDatabaseOrThrow(DatabaseType.MYSQL, "default"));
        assertEquals("registerDatabase", registration.operationName());

        ProviderClosedException lookup = assertThrows(ProviderClosedException.class,
                () -> handler.requireRegisteredDatabase(DatabaseType.MYSQL, "default"));
        assertEquals("requireRegisteredDatabase", lookup.operationName());
    }
}
