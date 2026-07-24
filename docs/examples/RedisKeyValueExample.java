import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;

/**
 * Example: Redis key-value access.
 */
public final class RedisKeyValueExample {

    private KeyValueDataAccess keyValue;

    public void onEnable(DataProviderAPI api) {
        KeyValueDatabaseProvider provider = (KeyValueDatabaseProvider) api.registerDatabaseOrThrow(
                DatabaseType.REDIS, "default"
        );
        keyValue = provider.getDataAccess();
    }

    public void cachePlayerLanguage(String playerUuid, String languageCode) {
        if (keyValue == null) {
            return;
        }
        keyValue.setKey("player:lang:" + playerUuid, languageCode);
    }

    public void loadPlayerLanguage(String playerUuid) {
        if (keyValue == null) {
            return;
        }
        keyValue.getKey("player:lang:" + playerUuid)
                .thenAccept(language -> System.out.println("Language for " + playerUuid + ": " + language));
    }

    public void onDisable(DataProviderAPI api) {
        api.unregisterDatabase(DatabaseType.REDIS, "default");
    }
}
