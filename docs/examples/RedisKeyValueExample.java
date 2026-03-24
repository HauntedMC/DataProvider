import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;

import java.util.Optional;

/**
 * Example: Redis key-value access.
 */
public final class RedisKeyValueExample {

    private KeyValueDataAccess keyValue;

    public void onEnable(DataProviderAPI api) {
        Optional<KeyValueDataAccess> optAccess = api.registerDataAccess(
                DatabaseType.REDIS,
                "default",
                KeyValueDataAccess.class
        );
        keyValue = optAccess.orElse(null);
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
