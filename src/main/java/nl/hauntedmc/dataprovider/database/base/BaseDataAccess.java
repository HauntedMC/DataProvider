package nl.hauntedmc.dataprovider.database.base;

/**
 * A root/marker interface for data-access objects.
 * Both relational and NoSQL DataAccess interfaces extend this.
 *
 * If you have shared methods for all DBs (e.g., "ping"), you can place them here.
 */
public interface BaseDataAccess {
    // Optionally define common methods that are truly universal, or keep empty.
}
