import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.database.document.model.DocumentQuery;

import java.util.Map;

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

    public void createProfile(String uuid, String name) {
        if (documents == null) {
            return;
        }
        documents.insertOne("profiles", Map.of(
                "uuid", uuid,
                "name", name
        ));
    }

    public void findProfile(String uuid) {
        if (documents == null) {
            return;
        }
        documents.findOne("profiles", new DocumentQuery().eq("uuid", uuid))
                .thenAccept(profile -> System.out.println("Profile: " + profile));
    }

    public void onDisable(DataProviderAPI api) {
        api.unregisterDatabase(DatabaseType.MONGODB, "default");
    }
}
