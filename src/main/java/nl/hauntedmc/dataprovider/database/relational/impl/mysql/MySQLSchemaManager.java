package nl.hauntedmc.dataprovider.database.relational.impl.mysql;

import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.database.relational.schema.ColumnDefinition;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;
import nl.hauntedmc.dataprovider.database.relational.schema.TableDefinition;

import java.sql.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Handles schema operations (DDL) for MySQL.
 */
public class MySQLSchemaManager implements SchemaManager {

    private final HikariDataSource dataSource;
    private final ExecutorService executor;

    public MySQLSchemaManager(HikariDataSource dataSource, ExecutorService executor) {
        this.dataSource = dataSource;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> createTable(TableDefinition tableDefinition) {
        return CompletableFuture.runAsync(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("CREATE TABLE IF NOT EXISTS ").append(tableDefinition.getTableName()).append(" (");

            for (ColumnDefinition column : tableDefinition.getColumns()) {
                sb.append(column.getName()).append(" ").append(column.getType());
                if (column.isNotNull()) {
                    sb.append(" NOT NULL");
                }
                if (column.isAutoIncrement()) {
                    sb.append(" AUTO_INCREMENT");
                }
                sb.append(", ");
            }
            sb.append("PRIMARY KEY (").append(tableDefinition.getPrimaryKey()).append(")) ENGINE=InnoDB;");

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
            StringBuilder sb = new StringBuilder();
            sb.append("ALTER TABLE ").append(tableDefinition.getTableName()).append(" ");

            boolean first = true;
            for (ColumnDefinition column : tableDefinition.getColumns()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append("ADD COLUMN ").append(column.getName())
                        .append(" ").append(column.getType());
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
            String query = "DROP TABLE IF EXISTS " + tableName;
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
            String query = """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name = ?
                    """;
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.setString(1, tableName);
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
            String indexType = unique ? "UNIQUE " : "";
            String indexName = "idx_" + column;
            String query = "CREATE " + indexType + "INDEX " + indexName + " ON " + tableName + " (" + column + ")";
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
            String query = "DROP INDEX " + indexName + " ON " + tableName;
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
            String constraintName = "fk_" + table + "_" + column;
            String query = "ALTER TABLE " + table + " ADD CONSTRAINT " + constraintName +
                    " FOREIGN KEY (" + column + ") REFERENCES " + referenceTable + " (" + referenceColumn + ")";
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
            String query = "ALTER TABLE " + table + " DROP FOREIGN KEY " + constraintName;
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to remove foreign key: " + e.getMessage(), e);
            }
        }, executor);
    }
}
