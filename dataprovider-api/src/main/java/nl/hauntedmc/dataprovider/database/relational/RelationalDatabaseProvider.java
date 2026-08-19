package nl.hauntedmc.dataprovider.database.relational;

import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;

import javax.sql.DataSource;

/**
 * Provider contract for relational databases such as MySQL.
 * Adds relational-specific features such as schema management and JDBC data-source access.
 */
public interface RelationalDatabaseProvider extends DatabaseProvider {

    /**
     * @return the SchemaManager for performing DDL operations.
     */
    SchemaManager getSchemaManager();

    @Override
    RelationalDataAccess getDataAccess();

    @Override
    default boolean supportsDataSource() {
        return true;
    }

    @Override
    DataSource getDataSource();
}
