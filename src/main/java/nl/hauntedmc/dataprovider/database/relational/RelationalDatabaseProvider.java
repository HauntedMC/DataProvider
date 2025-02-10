package nl.hauntedmc.dataprovider.database.relational;

import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;

import java.sql.Connection;

/**
 * A database provider interface for relational databases (e.g. MySQL, PostgreSQL).
 * Extends BaseDatabaseProvider and adds relational–specific features like a SchemaManager.
 */
public interface RelationalDatabaseProvider extends BaseDatabaseProvider {

    /**
     * @return the SchemaManager for performing DDL operations.
     */
    SchemaManager getSchemaManager();

    @Override
    RelationalDataAccess getDataAccess();

    Connection getConnection();
}
