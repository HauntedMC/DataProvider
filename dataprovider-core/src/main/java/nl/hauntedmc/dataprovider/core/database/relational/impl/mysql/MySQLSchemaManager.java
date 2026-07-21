package nl.hauntedmc.dataprovider.core.database.relational.impl.mysql;

import nl.hauntedmc.dataprovider.core.concurrent.AsyncTaskSupport;
import nl.hauntedmc.dataprovider.database.relational.schema.ColumnDefinition;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;
import nl.hauntedmc.dataprovider.database.relational.schema.TableDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/** Handles schema operations for MySQL. */
public class MySQLSchemaManager implements SchemaManager {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern SQL_TYPE_PATTERN = Pattern.compile("[A-Za-z0-9_(),\\s]+");

    private final DataSource dataSource;
    private final Executor executor;

    public MySQLSchemaManager(DataSource dataSource, Executor executor) {
        this.dataSource = dataSource;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> createTable(TableDefinition tableDefinition) {
        return AsyncTaskSupport.runAsync(executor, "mysql.schema.createTable", () -> {
            if (tableDefinition == null) {
                throw new IllegalArgumentException("Table definition cannot be null.");
            }
            List<ColumnDefinition> columns = tableDefinition.getColumns();
            if (columns == null || columns.isEmpty()) {
                throw new IllegalArgumentException("Table definition must include at least one column.");
            }
            StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                    .append(quoteIdentifier(tableDefinition.getTableName(), "table")).append(" (");
            for (ColumnDefinition column : columns) {
                if (column == null) {
                    throw new IllegalArgumentException("Table definition contains a null column.");
                }
                sql.append(quoteIdentifier(column.getName(), "column")).append(" ")
                        .append(validateSqlType(column.getType()));
                if (column.isNotNull()) {
                    sql.append(" NOT NULL");
                }
                if (column.isAutoIncrement()) {
                    sql.append(" AUTO_INCREMENT");
                }
                sql.append(", ");
            }
            sql.append("PRIMARY KEY (").append(quoteIdentifier(tableDefinition.getPrimaryKey(), "primary key"))
                    .append(")) ENGINE=InnoDB;");
            execute(sql.toString(), "create table");
        });
    }

    @Override
    public CompletableFuture<Void> alterTable(TableDefinition tableDefinition) {
        return AsyncTaskSupport.runAsync(executor, "mysql.schema.alterTable", () -> {
            if (tableDefinition == null || tableDefinition.getColumns() == null
                    || tableDefinition.getColumns().isEmpty()) {
                throw new IllegalArgumentException("Table definition must include at least one column.");
            }
            StringBuilder sql = new StringBuilder("ALTER TABLE ")
                    .append(quoteIdentifier(tableDefinition.getTableName(), "table")).append(" ");
            boolean first = true;
            for (ColumnDefinition column : tableDefinition.getColumns()) {
                if (!first) {
                    sql.append(", ");
                }
                sql.append("ADD COLUMN ").append(quoteIdentifier(column.getName(), "column")).append(" ")
                        .append(validateSqlType(column.getType()));
                if (column.isNotNull()) {
                    sql.append(" NOT NULL");
                }
                first = false;
            }
            execute(sql.toString(), "alter table");
        });
    }

    @Override
    public CompletableFuture<Void> dropTable(String tableName) {
        return AsyncTaskSupport.runAsync(executor, "mysql.schema.dropTable",
                () -> execute("DROP TABLE IF EXISTS " + quoteIdentifier(tableName, "table"), "drop table"));
    }

    @Override
    public CompletableFuture<Boolean> tableExists(String tableName) {
        return AsyncTaskSupport.supplyAsync(executor, "mysql.schema.tableExists", () -> {
            String query = "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_name = ?";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.setString(1, validateIdentifier(tableName, "table"));
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to check table existence", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> addIndex(String tableName, String column, boolean unique) {
        return AsyncTaskSupport.runAsync(executor, "mysql.schema.addIndex", () -> {
            String validatedColumn = validateIdentifier(column, "column");
            String query = "CREATE " + (unique ? "UNIQUE " : "") + "INDEX "
                    + quoteIdentifier(limitIdentifierLength("idx_" + validatedColumn), "index")
                    + " ON " + quoteIdentifier(tableName, "table")
                    + " (" + quoteIdentifier(validatedColumn, "column") + ")";
            execute(query, "add index");
        });
    }

    @Override
    public CompletableFuture<Void> removeIndex(String tableName, String indexName) {
        return AsyncTaskSupport.runAsync(executor, "mysql.schema.removeIndex", () -> execute(
                "DROP INDEX " + quoteIdentifier(indexName, "index") + " ON "
                        + quoteIdentifier(tableName, "table"), "remove index"));
    }

    @Override
    public CompletableFuture<Void> addForeignKey(
            String table,
            String column,
            String referenceTable,
            String referenceColumn
    ) {
        return AsyncTaskSupport.runAsync(executor, "mysql.schema.addForeignKey", () -> {
            String tableId = validateIdentifier(table, "table");
            String columnId = validateIdentifier(column, "column");
            String referenceTableId = validateIdentifier(referenceTable, "reference table");
            String referenceColumnId = validateIdentifier(referenceColumn, "reference column");
            String query = "ALTER TABLE " + quoteIdentifier(tableId, "table")
                    + " ADD CONSTRAINT " + quoteIdentifier(limitIdentifierLength("fk_" + tableId + "_" + columnId),
                    "constraint")
                    + " FOREIGN KEY (" + quoteIdentifier(columnId, "column") + ") REFERENCES "
                    + quoteIdentifier(referenceTableId, "reference table")
                    + " (" + quoteIdentifier(referenceColumnId, "reference column") + ")";
            execute(query, "add foreign key");
        });
    }

    @Override
    public CompletableFuture<Void> removeForeignKey(String table, String constraintName) {
        return AsyncTaskSupport.runAsync(executor, "mysql.schema.removeForeignKey", () -> execute(
                "ALTER TABLE " + quoteIdentifier(table, "table") + " DROP FOREIGN KEY "
                        + quoteIdentifier(constraintName, "constraint"), "remove foreign key"));
    }

    private void execute(String sql, String operation) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to " + operation, e);
        }
    }

    private static String quoteIdentifier(String identifier, String kind) {
        return "`" + validateIdentifier(identifier, kind) + "`";
    }

    private static String validateIdentifier(String identifier, String kind) {
        if (identifier == null || identifier.isBlank() || !IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsafe " + kind + " identifier: " + identifier);
        }
        return identifier;
    }

    private static String validateSqlType(String sqlType) {
        if (sqlType == null || sqlType.isBlank()) {
            throw new IllegalArgumentException("Column type cannot be null or blank.");
        }
        String normalized = sqlType.trim().replaceAll("\\s+", " ");
        String lowered = normalized.toLowerCase(Locale.ROOT);
        if (!SQL_TYPE_PATTERN.matcher(normalized).matches() || lowered.contains("--") || lowered.contains("/*")
                || lowered.contains("*/") || lowered.contains(";") || lowered.contains("'")
                || lowered.contains("\"") || lowered.contains("`")) {
            throw new IllegalArgumentException("Unsafe column type: " + sqlType);
        }
        return normalized;
    }

    private static String limitIdentifierLength(String identifier) {
        return identifier.length() > 64 ? identifier.substring(0, 64) : identifier;
    }
}
