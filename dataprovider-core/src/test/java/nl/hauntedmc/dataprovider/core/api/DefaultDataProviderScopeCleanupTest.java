package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityRegistry;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DefaultDataProviderScopeCleanupTest {

    @Test
    void disablingIdentityCannotStartWorkButCanCloseItsScope() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = new PluginIdentityRegistry().register(
                "owner",
                getClass().getClassLoader()
        );
        doThrow(new SecurityException("new work rejected"))
                .when(handler).requireIdentity(identity);
        DefaultDataProviderScope scope = new DefaultDataProviderScope(
                handler,
                OwnerScope.of("feature"),
                identity
        );

        assertThrows(SecurityException.class,
                () -> scope.registerDatabaseOrThrow(DatabaseType.MYSQL, "primary"));
        assertDoesNotThrow(scope::close);

        verify(handler).requireIdentityForCleanup(identity);
        verify(handler).unregisterAllDatabasesForScope(eq(identity), any(OwnerScope.class));
    }
}
