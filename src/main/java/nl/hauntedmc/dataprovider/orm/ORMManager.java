package nl.hauntedmc.dataprovider.orm;

import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.orm.adapters.StorageAdapter;
import nl.hauntedmc.dataprovider.orm.adapters.StorageAdapterFactory;
import nl.hauntedmc.dataprovider.orm.annotations.*;

import java.lang.reflect.Field;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class ORMManager {
    private final StorageAdapter storageAdapter;
    private final Logger logger;

    /**
     * Initializes the ORMManager and automatically binds the correct StorageAdapter.
     * @param databaseProvider The DatabaseProvider object from DataProvider.
     * @param databaseType The type of database (MYSQL, MONGODB, etc.).
     * @param logger Bukkit/Java Logger for logging.
     */
    public ORMManager(BaseDatabaseProvider databaseProvider, DatabaseType databaseType, Logger logger) {
        this.logger = logger;

        // Automatically determine the correct StorageAdapter
        this.storageAdapter = StorageAdapterFactory.createAdapter(databaseProvider, databaseType, logger);

        if (this.storageAdapter == null) {
            throw new IllegalStateException("No valid StorageAdapter found for " + databaseType.name());
        }

        BaseModel.setStorageAdapter(storageAdapter);
    }

    /**
     * Creates a table in SQL-based databases. Skipped for NoSQL.
     */
    public <T extends BaseModel> CompletableFuture<Void> createTable(Class<T> modelClass) {
        return CompletableFuture.runAsync(() -> {
            if (!(storageAdapter instanceof nl.hauntedmc.dataprovider.orm.adapters.sql.MySQLStorageAdapter)) {
                logger.info("Skipping table creation for non-SQL database.");
                return;
            }

            try {
                Table tableAnnotation = modelClass.getAnnotation(Table.class);
                String tableName = (tableAnnotation != null) ? tableAnnotation.name() : modelClass.getSimpleName().toLowerCase();

                StringJoiner columns = new StringJoiner(", ");
                String primaryKeyColumn = null;

                for (Field field : modelClass.getDeclaredFields()) {
                    Column column = field.getAnnotation(Column.class);
                    if (column != null) {
                        StringBuilder columnDef = new StringBuilder(column.name() + " " + column.type());

                        if (column.notNull()) columnDef.append(" NOT NULL");
                        if (!column.defaultValue().isEmpty()) columnDef.append(" DEFAULT ").append(column.defaultValue());

                        if (field.isAnnotationPresent(PrimaryKey.class)) {
                            primaryKeyColumn = column.name();
                            columnDef.append(" PRIMARY KEY");
                            if (field.getAnnotation(PrimaryKey.class).autoIncrement()) {
                                columnDef.append(" AUTO_INCREMENT");
                            }
                        }

                        columns.add(columnDef.toString());
                    }
                }

                if (primaryKeyColumn == null) {
                    throw new IllegalStateException("No primary key defined in class " + modelClass.getSimpleName());
                }

                String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" + columns + ")";
                logger.info("Creating table: " + tableName);
                String finalPrimaryKeyColumn = primaryKeyColumn;
                storageAdapter.initialize().thenRun(() -> storageAdapter.save(tableName, null, finalPrimaryKeyColumn));
            } catch (Exception e) {
                logger.severe("Failed to create table: " + modelClass.getSimpleName() + " - " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }
}
