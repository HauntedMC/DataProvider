import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.database.document.model.DocumentQuery;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Example: MongoDB document operations.
 */
public final class MongoDocumentExample {

    private DocumentDataAccess documents;

    public void onEnable(DataProviderAPI api) {
        DocumentDatabaseProvider provider = (DocumentDatabaseProvider) api.registerDatabaseOrThrow(
                DatabaseType.MONGODB, "default"
        );
        documents = provider.getDataAccess();
    }

    public CompletableFuture<Void> createProfile(String uuid, String name) {
        return dataAccess().insertOne("profiles", Map.of(
                "uuid", uuid,
                "name", name
        ));
    }

    public CompletableFuture<Map<String, Object>> findProfile(String uuid) {
        return dataAccess().findOne("profiles", new DocumentQuery().eq("uuid", uuid));
    }

    public void onDisable(DataProviderAPI api) {
        documents = null;
        api.unregisterDatabase(DatabaseType.MONGODB, "default");
    }

    private DocumentDataAccess dataAccess() {
        if (documents == null) {
            throw new IllegalStateException("MongoDB is not registered.");
        }
        return documents;
    }
}
