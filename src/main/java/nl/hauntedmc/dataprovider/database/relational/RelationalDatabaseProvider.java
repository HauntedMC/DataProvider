package nl.hauntedmc.dataprovider.database.relational;

import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;

/**
 * A database provider interface for relational databases (e.g. MySQL, PostgreSQL).
 * It extends BaseDatabaseProvider and adds relational-specific features like a SchemaManager.
 */
public interface RelationalDatabaseProvider extends BaseDatabaseProvider {

    /**
     * Relational providers always have a SchemaManager for DDL operations, etc.
     */
    SchemaManager getSchemaManager();

    /**
     * Override the parent type to RelationalDataAccess so consumers
     * don't have to cast it.
     */
    @Override
    RelationalDataAccess getDataAccess();
}
