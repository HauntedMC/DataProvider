import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;

import java.util.concurrent.CompletableFuture;

/**
 * Example: Redis key-value access.
 */
public final class RedisKeyValueExample {

    private KeyValueDataAccess keyValue;

    public void onEnable(DataProviderAPI api) {
        KeyValueDatabaseProvider provider = api.registerDatabaseOrThrow(
                DatabaseType.REDIS, "example", KeyValueDatabaseProvider.class
        );
        keyValue = provider.getDataAccess();
    }

    public CompletableFuture<Void> cachePlayerLanguage(String playerUuid, String languageCode) {
        return dataAccess().setKey("player:lang:" + playerUuid, languageCode);
    }

    public CompletableFuture<String> loadPlayerLanguage(String playerUuid) {
        return dataAccess().getKey("player:lang:" + playerUuid);
    }

    public void onDisable(DataProviderAPI api) {
        keyValue = null;
        api.unregisterDatabase(DatabaseType.REDIS, "example");
    }

    private KeyValueDataAccess dataAccess() {
        if (keyValue == null) {
            throw new IllegalStateException("Redis is not registered.");
        }
        return keyValue;
    }
}
