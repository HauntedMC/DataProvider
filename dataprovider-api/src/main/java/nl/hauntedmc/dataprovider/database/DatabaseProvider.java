package nl.hauntedmc.dataprovider.database;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only database handle exposed to plugin consumers.
 * Lifecycle operations are owned by DataProvider internals.
 */
public interface DatabaseProvider {

    /**
     * Check if the database is currently connected.
     *
     * @return true if connected, false otherwise.
     */
    boolean isConnected();

    /**
     * Returns a BaseDataAccess object for this database.
     *
     * @return the data access object
     */
    DataAccess getDataAccess();

    /**
     * Returns a BaseDataAccess object for this database.
     *
     * @return the data access object
     */
    DataSource getDataSource();

    /**
     * Returns the provider data access cast to the expected type.
     *
     * @param expectedType required data access subtype
     * @param <T>          expected data access subtype
     * @return optional containing the casted data access if compatible
     */
    default <T extends DataAccess> Optional<T> getDataAccessOptional(Class<T> expectedType) {
        Objects.requireNonNull(expectedType, "Expected data access type cannot be null.");
        DataAccess dataAccess;
        try {
            dataAccess = getDataAccess();
        } catch (IllegalStateException | UnsupportedOperationException ignored) {
            return Optional.empty();
        }
        if (dataAccess == null) {
            return Optional.empty();
        }
        if (expectedType.isInstance(dataAccess)) {
            return Optional.of(expectedType.cast(dataAccess));
        }
        return Optional.empty();
    }

    /**
     * Returns the provider data access cast to the expected type or throws with a descriptive message.
     *
     * @param expectedType required data access subtype
     * @param <T>          expected data access subtype
     * @return casted data access
     */
    default <T extends DataAccess> T requireDataAccess(Class<T> expectedType) {
        Objects.requireNonNull(expectedType, "Expected data access type cannot be null.");
        DataAccess dataAccess = getDataAccess();
        if (dataAccess == null) {
            throw new IllegalStateException(
                    "Expected data access type " + expectedType.getName()
                            + " but provider returned null."
            );
        }
        if (expectedType.isInstance(dataAccess)) {
            return expectedType.cast(dataAccess);
        }
        throw new IllegalStateException(
                "Expected data access type " + expectedType.getName()
                        + " but got " + dataAccess.getClass().getName()
        );
    }

    /**
     * Returns a DataSource when supported by the provider.
     *
     * @return optional DataSource (empty for non-relational providers)
     */
    default Optional<DataSource> getDataSourceOptional() {
        try {
            return Optional.ofNullable(getDataSource());
        } catch (UnsupportedOperationException | IllegalStateException ignored) {
            return Optional.empty();
        }
    }
}
