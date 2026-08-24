package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.api.observation.DataProviderObserver;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityRegistry;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BoundDataSourceObservationMetadataTest {

    @Test
    void boundDataSourceRetainsItsPublicOwnerScopeForOrmObservation() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = new PluginIdentityRegistry().register(
                "dataregistry",
                getClass().getClassLoader()
        );
        RelationalDatabaseProvider provider = mock(RelationalDatabaseProvider.class);
        DataSource dataSource = mock(DataSource.class);
        OwnerScope ownerScope = OwnerScope.of("profiles");
        when(provider.getDataSource()).thenReturn(dataSource);

        RelationalDatabaseProvider bound = (RelationalDatabaseProvider) IdentityBoundDatabaseProvider.wrap(
                handler,
                identity,
                provider,
                DataProviderObserver.noop(),
                identity.pluginId(),
                ownerScope,
                DatabaseType.MYSQL
        );

        DataSource boundDataSource = bound.getDataSource();

        assertEquals(ownerScope, IdentityBoundDatabaseProvider.boundOwnerScope(boundDataSource));
        assertEquals(DatabaseType.MYSQL, IdentityBoundDatabaseProvider.boundDatabaseType(boundDataSource));
    }
}
