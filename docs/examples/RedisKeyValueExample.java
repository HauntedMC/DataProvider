import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Example: Redis key-value access.
 */
public final class RedisKeyValueExample {

    private static final Duration LANGUAGE_CACHE_TTL = Duration.ofHours(12);

    private KeyValueDataAccess keyValue;

    public void onEnable(DataProviderAPI api) {
        KeyValueDatabaseProvider provider = api.registerDatabaseOrThrow(
                DatabaseType.REDIS, "example", KeyValueDatabaseProvider.class
        );
        keyValue = provider.getDataAccess();
    }

    public CompletableFuture<Void> cachePlayerLanguage(String playerUuid, String languageCode) {
        return dataAccess().setKeyWithExpiry("player:lang:" + playerUuid, languageCode, LANGUAGE_CACHE_TTL);
    }

    public CompletableFuture<String> loadPlayerLanguage(String playerUuid) {
        return dataAccess().getKeyOrDefault("player:lang:" + playerUuid, "en");
    }

    public CompletableFuture<Optional<String>> findCachedPlayerLanguage(String playerUuid) {
        return dataAccess().getKeyOptional("player:lang:" + playerUuid);
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
