package nl.hauntedmc.dataprovider.database;

import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseProviderInterfaceContractTest {

    @Test
    void genericProviderCanReturnTypedDataAccessWithoutManualCast() {
        TestDataAccess access = new TestDataAccess() { };
        DatabaseProvider provider = new DatabaseProvider() {
            @Override public boolean isConnected() { return true; }
            @Override public DataAccess getDataAccess() { return access; }
            @Override public DataSource getDataSource() { return null; }
        };

        assertSame(access, provider.getDataAccess(TestDataAccess.class));
        assertThrows(NullPointerException.class, () -> provider.getDataAccess(null));
        assertThrows(ClassCastException.class, () -> provider.getDataAccess(OtherDataAccess.class));
    }

    @Test
    void relationalProviderAdvertisesDataSourceSupport() {
        RelationalDatabaseProvider provider = new RelationalDatabaseProvider() {
            @Override
            public boolean isConnected() {
                return true;
            }

            @Override
            public SchemaManager getSchemaManager() {
                return null;
            }

            @Override
            public RelationalDataAccess getDataAccess() {
                return null;
            }

            @Override
            public DataSource getDataSource() {
                return null;
            }
        };

        assertTrue(provider.supportsDataSource());
    }

    @Test
    void documentProviderDoesNotExposeDataSource() {
        DocumentDatabaseProvider provider = new DocumentDatabaseProvider() {
            @Override
            public boolean isConnected() {
                return true;
            }

            @Override
            public DocumentDataAccess getDataAccess() {
                return null;
            }
        };

        assertFalse(provider.supportsDataSource());
        assertThrows(UnsupportedOperationException.class, provider::getDataSource);
    }

    @Test
    void keyValueProviderDoesNotExposeDataSource() {
        KeyValueDatabaseProvider provider = new KeyValueDatabaseProvider() {
            @Override
            public boolean isConnected() {
                return true;
            }

            @Override
            public KeyValueDataAccess getDataAccess() {
                return null;
            }
        };

        assertFalse(provider.supportsDataSource());
        assertThrows(UnsupportedOperationException.class, provider::getDataSource);
    }

    @Test
    void messagingProviderDoesNotExposeDataSource() {
        MessagingDatabaseProvider provider = new MessagingDatabaseProvider() {
            @Override
            public boolean isConnected() {
                return true;
            }

            @Override
            public MessagingDataAccess getDataAccess() {
                return null;
            }
        };

        assertFalse(provider.supportsDataSource());
        assertThrows(UnsupportedOperationException.class, provider::getDataSource);
    }

    private interface TestDataAccess extends DataAccess {
    }

    private interface OtherDataAccess extends DataAccess {
    }
}
