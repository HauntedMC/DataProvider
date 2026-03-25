package nl.hauntedmc.dataprovider.database;

import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseProviderInterfaceContractTest {

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

        assertThrows(UnsupportedOperationException.class, provider::getDataSource);
    }
}
