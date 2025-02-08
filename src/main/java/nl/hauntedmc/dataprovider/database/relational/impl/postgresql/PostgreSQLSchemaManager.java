package nl.hauntedmc.dataprovider.database.relational.impl.postgresql;

import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.database.relational.schema.ColumnDefinition;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;
import nl.hauntedmc.dataprovider.database.relational.schema.TableDefinition;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * PostgreSQLSchemaManager implements SchemaManager for PostgreSQL.
 * It translates auto–increment columns to use PostgreSQL’s SERIAL type
 * and uses PostgreSQL–friendly DDL (without MySQL–specific options).
 */
public class PostgreSQLSchemaManager implements SchemaManager {

    private final HikariDataSource dataSource;
    private final ExecutorService executor;

    public PostgreSQLSchemaManager(HikariDataSource dataSource, ExecutorService executor) {
        this.dataSource = dataSource;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> createTable(TableDefinition tableDefinition) {
        return CompletableFuture.runAsync(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("CREATE TABLE IF NOT EXISTS ").append(tableDefinition.getTableName()).append(" (");

            for (ColumnDefinition column : tableDefinition.getColumns()) {
                sb.append(column.getName()).append(" ");
                if (column.isAutoIncrement()) {
                    // Use SERIAL for auto–increment columns.
                    sb.append("SERIAL");
                } else {
                    sb.append(column.getType());
                }
                if (column.isNotNull()) {
                    sb.append(" NOT NULL");
                }
                sb.append(", ");
            }
            sb.append("PRIMARY KEY (").append(tableDefinition.getPrimaryKey()).append("));");

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
                sb.append("ADD COLUMN ").append(column.getName()).append(" ");
                if (column.isAutoIncrement()) {
                    sb.append("SERIAL");
                } else {
                    sb.append(column.getType());
                }
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
            final String query = "DROP TABLE IF EXISTS " + tableName;
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
            final String query = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?";
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
            final String indexType = unique ? "UNIQUE " : "";
            final String indexName = "idx_" + column;
            final String query = "CREATE " + indexType + "INDEX " + indexName + " ON " + tableName + " (" + column + ")";
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
            final String query = "DROP INDEX IF EXISTS " + indexName;
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
            final String constraintName = "fk_" + table + "_" + column;
            final String query = "ALTER TABLE " + table + " ADD CONSTRAINT " + constraintName +
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
            final String query = "ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraintName;
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to remove foreign key: " + e.getMessage(), e);
            }
        }, executor);
    }
}
