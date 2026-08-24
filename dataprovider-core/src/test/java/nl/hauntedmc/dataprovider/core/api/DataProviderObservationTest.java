package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.api.DataProviderScope;
import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.api.observation.DataProviderObservation;
import nl.hauntedmc.dataprovider.api.observation.DataProviderObserver;
import nl.hauntedmc.dataprovider.api.observation.DataProviderOperationContext;
import nl.hauntedmc.dataprovider.api.orm.ORMContext;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityRegistry;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataProviderObservationTest {

    @Test
    void facadeRegistrationReportsOnlyStablePluginScopeAndBackendMetadata() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = identity("serverfeatures");
        DatabaseProvider provider = mock(DatabaseProvider.class);
        RecordingObserver observer = new RecordingObserver();
        when(handler.registerDatabaseOrThrow(identity, DatabaseType.MYSQL, "survival-primary"))
                .thenReturn(provider);

        boundApi(handler, identity, observer)
                .registerDatabaseOrThrow(DatabaseType.MYSQL, "survival-primary");

        RecordingObservation observation = observer.single();
        assertEquals("serverfeatures", observation.context.pluginId());
        assertEquals(OwnerScope.of("serverfeatures"), observation.context.ownerScope());
        assertEquals(DatabaseType.MYSQL, observation.context.databaseType());
        assertEquals("database.register", observation.context.operation());
        assertEquals(1, observation.succeeded);
        assertEquals(null, observation.failure);
    }

    @Test
    void scopedRegistrationExposesPublicScopeInsteadOfUniqueInternalRegistrationScope() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = identity("dataregistry");
        DatabaseProvider provider = mock(DatabaseProvider.class);
        RecordingObserver observer = new RecordingObserver();
        OwnerScope publicScope = OwnerScope.of("profiles");
        when(handler.registerDatabaseForScopeOrThrow(
                eq(identity),
                any(OwnerScope.class),
                eq(DatabaseType.REDIS),
                eq("cache")
        )).thenReturn(provider);

        DataProviderScope scope = boundApi(handler, identity, observer).scope(publicScope);
        scope.registerDatabaseOrThrow(DatabaseType.REDIS, "cache");

        ArgumentCaptor<OwnerScope> internalScope = ArgumentCaptor.forClass(OwnerScope.class);
        verify(handler).registerDatabaseForScopeOrThrow(
                eq(identity),
                internalScope.capture(),
                eq(DatabaseType.REDIS),
                eq("cache")
        );
        assertNotEquals(publicScope, internalScope.getValue());
        assertEquals(publicScope, observer.single().context.ownerScope());
    }

    @Test
    void asynchronousDataAccessObservationFinishesWithTheReturnedFuture() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = identity("serverfeatures");
        RelationalDatabaseProvider provider = mock(RelationalDatabaseProvider.class);
        RelationalDataAccess access = mock(RelationalDataAccess.class);
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        RecordingObserver observer = new RecordingObserver();
        when(handler.requireRegisteredDatabase(identity, DatabaseType.MYSQL, "primary"))
                .thenReturn(provider);
        when(provider.getDataAccess()).thenReturn(access);
        when(access.queryForSingle("SELECT 1")).thenReturn(result);

        RelationalDatabaseProvider boundProvider = boundApi(handler, identity, observer)
                .requireRegisteredDatabase(DatabaseType.MYSQL, "primary", RelationalDatabaseProvider.class);
        CompletableFuture<Map<String, Object>> observedResult = boundProvider.getDataAccess()
                .queryForSingle("SELECT 1");

        RecordingObservation observation = observer.single();
        assertEquals("relational.queryForSingle", observation.context.operation());
        assertEquals(0, observation.succeeded);
        assertEquals(null, observation.failure);

        result.complete(Map.of("value", 1));

        assertSame(result, observedResult);
        assertEquals(1, observation.succeeded);
        assertEquals(null, observation.failure);
    }

    @Test
    void asynchronousFailureIsReportedWithoutReplacingTheOriginalFailure() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = identity("serverfeatures");
        RelationalDatabaseProvider provider = mock(RelationalDatabaseProvider.class);
        RelationalDataAccess access = mock(RelationalDataAccess.class);
        CompletableFuture<Void> result = new CompletableFuture<>();
        RecordingObserver observer = new RecordingObserver();
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(handler.requireRegisteredDatabase(identity, DatabaseType.MYSQL, "primary"))
                .thenReturn(provider);
        when(provider.getDataAccess()).thenReturn(access);
        when(access.executeUpdate("UPDATE example SET value = 1")).thenReturn(result);

        RelationalDatabaseProvider boundProvider = boundApi(handler, identity, observer)
                .requireRegisteredDatabase(DatabaseType.MYSQL, "primary", RelationalDatabaseProvider.class);
        CompletableFuture<Void> observedResult = boundProvider.getDataAccess()
                .executeUpdate("UPDATE example SET value = 1");
        result.completeExceptionally(failure);

        assertSame(result, observedResult);
        assertSame(failure, observer.single().failure);
        assertSame(failure, assertThrows(Exception.class, observedResult::join).getCause());
    }

    @Test
    void observerStartFailureCannotChangeTheDataOperationOutcome() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = identity("serverfeatures");
        DatabaseProvider provider = mock(DatabaseProvider.class);
        DataProviderObserver observer = context -> {
            throw new IllegalStateException("observer failed");
        };
        when(handler.registerDatabaseOrThrow(identity, DatabaseType.REDIS, "cache"))
                .thenReturn(provider);

        DatabaseProvider result = boundApi(handler, identity, observer)
                .registerDatabaseOrThrow(DatabaseType.REDIS, "cache");

        verify(handler).registerDatabaseOrThrow(identity, DatabaseType.REDIS, "cache");
        assertEquals(DatabaseProvider.class, result.getClass().getInterfaces()[0]);
    }

    @Test
    void observerTerminalFailureCannotChangeTheDataOperationOutcome() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = identity("serverfeatures");
        DatabaseProvider provider = mock(DatabaseProvider.class);
        DataProviderObserver observer = context -> new DataProviderObservation() {
            @Override
            public void succeeded() {
                throw new IllegalStateException("observer completion failed");
            }

            @Override
            public void failed(Throwable failure) {
                throw new IllegalStateException("observer completion failed");
            }
        };
        when(handler.registerDatabaseOrThrow(identity, DatabaseType.MONGODB, "documents"))
                .thenReturn(provider);

        boundApi(handler, identity, observer)
                .registerDatabaseOrThrow(DatabaseType.MONGODB, "documents");

        verify(handler).registerDatabaseOrThrow(identity, DatabaseType.MONGODB, "documents");
    }

    @Test
    void ormWrapperReportsTransactionCompletion() {
        ORMContext delegate = mock(ORMContext.class);
        RecordingObserver observer = new RecordingObserver();
        DataProviderOperationContext context = new DataProviderOperationContext(
                "dataregistry",
                OwnerScope.of("dataregistry"),
                DatabaseType.MYSQL,
                "orm.runInTransaction"
        );
        when(delegate.runInTransaction(any())).thenReturn("result");
        ORMContext observed = new ObservedOrmContext(delegate, observer, context);

        String result = observed.runInTransaction(session -> "ignored");

        assertEquals("result", result);
        assertEquals("orm.runInTransaction", observer.single().context.operation());
        assertEquals(1, observer.single().succeeded);
    }

    @Test
    void operationContextRejectsUnboundedIdentityFields() {
        OwnerScope ownerScope = OwnerScope.of("scope");
        assertThrows(IllegalArgumentException.class,
                () -> new DataProviderOperationContext(" ", ownerScope, DatabaseType.REDIS, "keyvalue.getKey"));
        assertThrows(IllegalArgumentException.class,
                () -> new DataProviderOperationContext("plugin", ownerScope, DatabaseType.REDIS, " "));
    }

    private PluginIdentity identity(String pluginId) {
        return new PluginIdentityRegistry().register(pluginId, getClass().getClassLoader());
    }

    private static DataProviderAPI boundApi(
            DataProviderHandler handler,
            PluginIdentity identity,
            DataProviderObserver observer
    ) {
        when(handler.issuePluginIdentity(any())).thenReturn(identity);
        when(handler.getPluginId(identity)).thenReturn(identity.pluginId());
        return new DefaultDataProviderApi(handler)
                .withObserver(observer)
                .forPlugin(new Object());
    }

    private static final class RecordingObserver implements DataProviderObserver {
        private final List<RecordingObservation> observations = new ArrayList<>();

        @Override
        public synchronized DataProviderObservation start(DataProviderOperationContext context) {
            RecordingObservation observation = new RecordingObservation(context);
            observations.add(observation);
            return observation;
        }

        synchronized RecordingObservation single() {
            assertEquals(1, observations.size());
            return observations.get(0);
        }
    }

    private static final class RecordingObservation implements DataProviderObservation {
        private final DataProviderOperationContext context;
        private int succeeded;
        private Throwable failure;

        private RecordingObservation(DataProviderOperationContext context) {
            this.context = context;
        }

        @Override
        public synchronized void succeeded() {
            succeeded++;
        }

        @Override
        public synchronized void failed(Throwable throwable) {
            failure = throwable;
        }
    }
}
