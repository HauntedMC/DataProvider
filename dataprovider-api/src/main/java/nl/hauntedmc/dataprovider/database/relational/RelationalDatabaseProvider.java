package nl.hauntedmc.dataprovider.database.relational;

import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;

import javax.sql.DataSource;

/**
 * A database provider interface for relational databases (e.g. MySQL).
 * Extends BaseDatabaseProvider and adds relational–specific features like a SchemaManager.
 */
public interface RelationalDatabaseProvider extends DatabaseProvider {

    /**
     * @return the SchemaManager for performing DDL operations.
     */
    SchemaManager getSchemaManager();

    @Override
    RelationalDataAccess getDataAccess();

    DataSource getDataSource();
}
