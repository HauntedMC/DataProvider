package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.api.DataProviderScope;
import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.database.DataAccess;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataProviderScopeTest {

    @Test
    void validatesOwnerScopeInput() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderAPI api = new DefaultDataProviderApi(handler);

        assertThrows(NullPointerException.class, () -> api.scope((String) null));
        assertThrows(IllegalArgumentException.class, () -> api.scope(" "));
        assertThrows(IllegalArgumentException.class, () -> api.scope("bad scope"));
    }

    @Test
    void typedScopeMethodsDelegateAndWrapResults() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        StubDatabaseProvider provider = new StubDatabaseProvider(new StubDataAccess());
        when(handler.registerDatabaseForScope(OwnerScope.of("component.scope"), DatabaseType.MYSQL, "default"))
                .thenReturn(provider);

        DataProviderScope scope = new DefaultDataProviderApi(handler).scope("component.scope");

        Optional<DatabaseProvider> optional = scope.registerDatabaseOptional(DatabaseType.MYSQL, "default");
        Optional<StubDatabaseProvider> typedProvider = scope.registerDatabaseAs(
                DatabaseType.MYSQL,
                "default",
                StubDatabaseProvider.class
        );
        Optional<StubDataAccess> typedDataAccess = scope.registerDataAccess(
                DatabaseType.MYSQL,
                "default",
                StubDataAccess.class
        );

        assertTrue(optional.isPresent());
        assertFalse(typedProvider.isPresent());
        assertTrue(typedDataAccess.isPresent());
    }

    @Test
    void unregisterAndCloseDelegateToScopedLifecycleMethods() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderScope scope = new DefaultDataProviderApi(handler).scope("component.scope");

        scope.unregisterDatabase(DatabaseType.MYSQL, "default");
        scope.unregisterAllDatabases();
        scope.close();

        verify(handler).unregisterDatabaseForScope(OwnerScope.of("component.scope"), DatabaseType.MYSQL, "default");
        verify(handler, times(2)).unregisterAllDatabasesForScope(OwnerScope.of("component.scope"));
    }

    @Test
    void closeIsIdempotentAndMakesScopeTerminal() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderScope scope = new DefaultDataProviderApi(handler).scope("component.scope");

        scope.close();
        scope.close();

        assertEquals(DataProviderScope.LifecycleState.CLOSED, scope.lifecycleState());
        assertThrows(IllegalStateException.class, () -> scope.registerDatabase(DatabaseType.MYSQL, "default"));
        assertThrows(IllegalStateException.class, () -> scope.getRegisteredDatabase(DatabaseType.MYSQL, "default"));
        assertThrows(IllegalStateException.class, () -> scope.unregisterDatabase(DatabaseType.MYSQL, "default"));
        assertThrows(IllegalStateException.class, scope::unregisterAllDatabases);
        verify(handler).unregisterAllDatabasesForScope(OwnerScope.of("component.scope"));
    }

    @Test
    void closeExposesClosingStateUntilScopeRegistrationsAreReleased() throws Exception {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        CountDownLatch releaseStarted = new CountDownLatch(1);
        CountDownLatch allowRelease = new CountDownLatch(1);
        doAnswer(invocation -> {
            releaseStarted.countDown();
            assertTrue(allowRelease.await(5, TimeUnit.SECONDS));
            return null;
        }).when(handler).unregisterAllDatabasesForScope(OwnerScope.of("component.scope"));
        DataProviderScope scope = new DefaultDataProviderApi(handler).scope("component.scope");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> close = executor.submit(scope::close);
            assertTrue(releaseStarted.await(5, TimeUnit.SECONDS));
            assertEquals(DataProviderScope.LifecycleState.CLOSING, scope.lifecycleState());

            allowRelease.countDown();
            close.get(5, TimeUnit.SECONDS);

            assertEquals(DataProviderScope.LifecycleState.CLOSED, scope.lifecycleState());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void scopedLookupsDelegateAndReturnOnlyScopedProviderViews() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        StubDatabaseProvider provider = new StubDatabaseProvider(new StubDataAccess());
        when(handler.getRegisteredDatabaseForScope(OwnerScope.of("component.scope"), DatabaseType.MYSQL, "default"))
                .thenReturn(provider);
        DataProviderScope scope = new DefaultDataProviderApi(handler).scope("component.scope");

        Optional<DatabaseProvider> optional = scope.getRegisteredDatabaseOptional(DatabaseType.MYSQL, "default");
        Optional<StubDataAccess> dataAccess = scope.getRegisteredDataAccess(
                DatabaseType.MYSQL,
                "default",
                StubDataAccess.class
        );

        assertTrue(optional.isPresent());
        assertTrue(dataAccess.isPresent());
        verify(handler, times(2)).getRegisteredDatabaseForScope(
                OwnerScope.of("component.scope"),
                DatabaseType.MYSQL,
                "default"
        );
    }

    @Test
    void closeWaitsForInFlightRegistrationThenReleasesScopeExactlyOnce() throws Exception {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        StubDatabaseProvider provider = new StubDatabaseProvider(new StubDataAccess());
        CountDownLatch registrationStarted = new CountDownLatch(1);
        CountDownLatch allowRegistration = new CountDownLatch(1);
        when(handler.registerDatabaseForScope(OwnerScope.of("component.scope"), DatabaseType.MYSQL, "default"))
                .thenAnswer(invocation -> {
                    registrationStarted.countDown();
                    assertTrue(allowRegistration.await(5, TimeUnit.SECONDS));
                    return provider;
                });
        DataProviderScope scope = new DefaultDataProviderApi(handler).scope("component.scope");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DatabaseProvider> registration = executor.submit(
                    () -> scope.registerDatabase(DatabaseType.MYSQL, "default")
            );
            assertTrue(registrationStarted.await(5, TimeUnit.SECONDS));
            Future<?> close = executor.submit(scope::close);

            allowRegistration.countDown();
            registration.get(5, TimeUnit.SECONDS);
            close.get(5, TimeUnit.SECONDS);

            assertEquals(DataProviderScope.LifecycleState.CLOSED, scope.lifecycleState());
            verify(handler).unregisterAllDatabasesForScope(OwnerScope.of("component.scope"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void exposesNormalizedOwnerScope() {
        DataProviderScope scope = new DefaultDataProviderApi(mock(DataProviderHandler.class)).scope(" component.scope ");
        assertEquals(OwnerScope.of("component.scope"), scope.ownerScope());
    }

    private static final class StubDataAccess implements DataAccess {
    }

    private static final class StubDatabaseProvider implements DatabaseProvider {
        private final DataAccess dataAccess;

        private StubDatabaseProvider(DataAccess dataAccess) {
            this.dataAccess = dataAccess;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public DataAccess getDataAccess() {
            return dataAccess;
        }

        @Override
        public DataSource getDataSource() {
            return null;
        }
    }
}
