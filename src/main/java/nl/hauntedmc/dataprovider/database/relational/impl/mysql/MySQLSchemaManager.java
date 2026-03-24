package nl.hauntedmc.dataprovider.database.relational.impl.mysql;

import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.database.relational.schema.ColumnDefinition;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;
import nl.hauntedmc.dataprovider.database.relational.schema.TableDefinition;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * Handles schema (DDL) operations for MySQL.
 */
public class MySQLSchemaManager implements SchemaManager {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern SQL_TYPE_PATTERN = Pattern.compile("[A-Za-z0-9_(),\\s]+");

    private final HikariDataSource dataSource;
    private final ExecutorService executor;

    public MySQLSchemaManager(HikariDataSource dataSource, ExecutorService executor) {
        this.dataSource = dataSource;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> createTable(TableDefinition tableDefinition) {
        return CompletableFuture.runAsync(() -> {
            if (tableDefinition == null) {
                throw new IllegalArgumentException("Table definition cannot be null.");
            }
            List<ColumnDefinition> columns = tableDefinition.getColumns();
            if (columns == null || columns.isEmpty()) {
                throw new IllegalArgumentException("Table definition must include at least one column.");
            }

            String tableName = quoteIdentifier(tableDefinition.getTableName(), "table");
            String primaryKey = quoteIdentifier(tableDefinition.getPrimaryKey(), "primary key");

            StringBuilder sb = new StringBuilder();
            sb.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (");

            for (ColumnDefinition column : columns) {
                if (column == null) {
                    throw new IllegalArgumentException("Table definition contains a null column.");
                }
                sb.append(quoteIdentifier(column.getName(), "column"))
                        .append(" ")
                        .append(validateSqlType(column.getType()));
                if (column.isNotNull()) {
                    sb.append(" NOT NULL");
                }
                if (column.isAutoIncrement()) {
                    sb.append(" AUTO_INCREMENT");
                }
                sb.append(", ");
            }
            sb.append("PRIMARY KEY (").append(primaryKey).append(")) ENGINE=InnoDB;");

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sb.toString())) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to create table: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> alterTable(TableDefinition tableDefinition) {
        return CompletableFuture.runAsync(() -> {
            if (tableDefinition == null) {
                throw new IllegalArgumentException("Table definition cannot be null.");
            }
            List<ColumnDefinition> columns = tableDefinition.getColumns();
            if (columns == null || columns.isEmpty()) {
                throw new IllegalArgumentException("Table definition must include at least one column.");
            }

            String tableName = quoteIdentifier(tableDefinition.getTableName(), "table");
            StringBuilder sb = new StringBuilder();
            sb.append("ALTER TABLE ").append(tableName).append(" ");

            boolean first = true;
            for (ColumnDefinition column : columns) {
                if (column == null) {
                    throw new IllegalArgumentException("Table definition contains a null column.");
                }
                if (!first) {
                    sb.append(", ");
                }
                sb.append("ADD COLUMN ").append(quoteIdentifier(column.getName(), "column"))
                        .append(" ").append(validateSqlType(column.getType()));
                if (column.isNotNull()) {
                    sb.append(" NOT NULL");
                }
                first = false;
            }

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sb.toString())) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to alter table: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> dropTable(String tableName) {
        return CompletableFuture.runAsync(() -> {
            final String query = "DROP TABLE IF EXISTS " + quoteIdentifier(tableName, "table");
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to drop table: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> tableExists(String tableName) {
        return CompletableFuture.supplyAsync(() -> {
            final String query = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.setString(1, validateIdentifier(tableName, "table"));
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        int count = rs.getInt(1);
                        return count > 0;
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to check table existence: " + e.getMessage(), e);
            }
            return false;
        }, executor);
    }

    @Override
    public CompletableFuture<Void> addIndex(String tableName, String column, boolean unique) {
        return CompletableFuture.runAsync(() -> {
            String validatedColumn = validateIdentifier(column, "column");
            final String indexType = unique ? "UNIQUE " : "";
            final String indexName = quoteIdentifier(limitIdentifierLength("idx_" + validatedColumn), "index");
            final String query = "CREATE " + indexType + "INDEX " + indexName
                    + " ON " + quoteIdentifier(tableName, "table")
                    + " (" + quoteIdentifier(validatedColumn, "column") + ")";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to add index: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeIndex(String tableName, String indexName) {
        return CompletableFuture.runAsync(() -> {
            final String query = "DROP INDEX " + quoteIdentifier(indexName, "index")
                    + " ON " + quoteIdentifier(tableName, "table");
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to remove index: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> addForeignKey(String table, String column, String referenceTable, String referenceColumn) {
        return CompletableFuture.runAsync(() -> {
            String tableId = validateIdentifier(table, "table");
            String columnId = validateIdentifier(column, "column");
            String referenceTableId = validateIdentifier(referenceTable, "reference table");
            String referenceColumnId = validateIdentifier(referenceColumn, "reference column");
            final String constraintName = quoteIdentifier(limitIdentifierLength("fk_" + tableId + "_" + columnId), "constraint");
            final String query = "ALTER TABLE " + quoteIdentifier(tableId, "table")
                    + " ADD CONSTRAINT " + constraintName
                    + " FOREIGN KEY (" + quoteIdentifier(columnId, "column") + ")"
                    + " REFERENCES " + quoteIdentifier(referenceTableId, "reference table")
                    + " (" + quoteIdentifier(referenceColumnId, "reference column") + ")";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to add foreign key: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeForeignKey(String table, String constraintName) {
        return CompletableFuture.runAsync(() -> {
            final String query = "ALTER TABLE " + quoteIdentifier(table, "table")
                    + " DROP FOREIGN KEY " + quoteIdentifier(constraintName, "constraint");
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to remove foreign key: " + e.getMessage(), e);
            }
        }, executor);
    }

    private static String quoteIdentifier(String identifier, String kind) {
        return "`" + validateIdentifier(identifier, kind) + "`";
    }

    private static String validateIdentifier(String identifier, String kind) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException(kind + " identifier cannot be null or blank.");
        }
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsafe " + kind + " identifier: " + identifier);
        }
        return identifier;
    }

    private static String validateSqlType(String sqlType) {
        if (sqlType == null || sqlType.isBlank()) {
            throw new IllegalArgumentException("Column type cannot be null or blank.");
        }

        String normalizedType = sqlType.trim().replaceAll("\\s+", " ");
        if (!SQL_TYPE_PATTERN.matcher(normalizedType).matches()) {
            throw new IllegalArgumentException("Unsafe column type: " + sqlType);
        }

        String loweredType = normalizedType.toLowerCase(Locale.ROOT);
        if (loweredType.contains("--")
                || loweredType.contains("/*")
                || loweredType.contains("*/")
                || loweredType.contains(";")
                || loweredType.contains("'")
                || loweredType.contains("\"")
                || loweredType.contains("`")) {
            throw new IllegalArgumentException("Unsafe column type: " + sqlType);
        }

        return normalizedType;
    }

    private static String limitIdentifierLength(String identifier) {
        return identifier.length() > 64 ? identifier.substring(0, 64) : identifier;
    }
}
