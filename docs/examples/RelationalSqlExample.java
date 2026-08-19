import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;

import java.util.concurrent.CompletableFuture;

/**
 * Example: asynchronous relational SQL without ORM.
 */
public final class RelationalSqlExample {

    private RelationalDataAccess sql;

    public void onEnable(DataProviderAPI api) {
        RelationalDatabaseProvider provider = api.registerDatabaseOrThrow(
                DatabaseType.MYSQL, "example", RelationalDatabaseProvider.class
        );
        sql = provider.getDataAccess();
    }

    public CompletableFuture<Void> savePlayerName(String uuid, String name) {
        return dataAccess().executeUpdate(
                "INSERT INTO player_profile (uuid, name) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE name = VALUES(name)",
                uuid,
                name
        );
    }

    public CompletableFuture<String> loadPlayerName(String uuid) {
        return dataAccess().queryForSingleValueAs(
                String.class,
                "SELECT name FROM player_profile WHERE uuid = ?",
                uuid
        );
    }

    public void onDisable(DataProviderAPI api) {
        sql = null;
        api.unregisterDatabase(DatabaseType.MYSQL, "example");
    }

    private RelationalDataAccess dataAccess() {
        if (sql == null) {
            throw new IllegalStateException("MySQL is not registered.");
        }
        return sql;
    }
}
