package nl.hauntedmc.dataprovider.api.observation;

import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.database.DatabaseType;

import java.util.Objects;

/**
 * Stable, payload-free metadata describing one observed DataProvider operation.
 *
 * <p>The operation name comes from DataProvider's bounded public operation vocabulary, for example
 * {@code database.register}, {@code relational.queryForSingle}, or {@code keyvalue.getKey}.
 * Connection identifiers, SQL text, keys, destinations, payloads, credentials, and player data are
 * deliberately excluded from this contract.</p>
 *
 * @param pluginId platform-derived plugin identity that owns the API facade
 * @param ownerScope public lifecycle owner scope; internal unique registration scopes are never exposed
 * @param databaseType backend used by the operation
 * @param operation stable DataProvider operation name
 */
public record DataProviderOperationContext(
        String pluginId,
        OwnerScope ownerScope,
        DatabaseType databaseType,
        String operation
) {

    public DataProviderOperationContext {
        Objects.requireNonNull(pluginId, "Plugin id cannot be null.");
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        Objects.requireNonNull(databaseType, "Database type cannot be null.");
        Objects.requireNonNull(operation, "Operation cannot be null.");
        if (pluginId.isBlank()) {
            throw new IllegalArgumentException("Plugin id cannot be blank.");
        }
        if (operation.isBlank()) {
            throw new IllegalArgumentException("Operation cannot be blank.");
        }
    }
}
