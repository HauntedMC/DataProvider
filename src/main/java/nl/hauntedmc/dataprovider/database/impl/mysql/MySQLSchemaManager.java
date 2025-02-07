package nl.hauntedmc.dataprovider.database.impl.mysql;

import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.database.schema.ColumnDefinition;
import nl.hauntedmc.dataprovider.database.schema.SchemaManager;
import nl.hauntedmc.dataprovider.database.schema.TableDefinition;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

public class MySQLSchemaManager implements SchemaManager {
    private final HikariDataSource dataSource;

    public MySQLSchemaManager(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public CompletableFuture<Void> createTable(TableDefinition tableDefinition) {
        return CompletableFuture.runAsync(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("CREATE TABLE IF NOT EXISTS ").append(tableDefinition.getTableName()).append(" (");

            for (ColumnDefinition column : tableDefinition.getColumns()) {
                sb.append(column.getName()).append(" ").append(column.getType());
                if (column.isNotNull()) sb.append(" NOT NULL");
                if (column.isAutoIncrement()) sb.append(" AUTO_INCREMENT");
                sb.append(", ");
            }

            sb.append("PRIMARY KEY (").append(tableDefinition.getPrimaryKey()).append(")) ENGINE=InnoDB;");

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sb.toString())) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to create table: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> alterTable(TableDefinition tableDefinition) {
        return CompletableFuture.runAsync(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("ALTER TABLE ").append(tableDefinition.getTableName()).append(" ");

            for (ColumnDefinition column : tableDefinition.getColumns()) {
                sb.append("ADD COLUMN ").append(column.getName()).append(" ").append(column.getType());
                if (column.isNotNull()) sb.append(" NOT NULL");
                sb.append(", ");
            }

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sb.toString())) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to alter table: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> dropTable(String tableName) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement("DROP TABLE IF EXISTS " + tableName)) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to drop table: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> tableExists(String tableName) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(
                         "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?")) {
                stmt.setString(1, tableName);
                return stmt.executeQuery().next();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to check table existence: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> addIndex(String tableName, String column, boolean unique) {
        return CompletableFuture.runAsync(() -> {
            String query = "CREATE " + (unique ? "UNIQUE " : "") + "INDEX idx_" + column + " ON " + tableName + " (" + column + ")";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to add index: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> removeIndex(String tableName, String indexName) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement("DROP INDEX " + indexName + " ON " + tableName)) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to remove index: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> addForeignKey(String table, String column, String referenceTable, String referenceColumn) {
        return CompletableFuture.runAsync(() -> {
            String query = "ALTER TABLE " + table + " ADD CONSTRAINT fk_" + column +
                    " FOREIGN KEY (" + column + ") REFERENCES " + referenceTable + " (" + referenceColumn + ")";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to add foreign key: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> removeForeignKey(String table, String constraintName) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement("ALTER TABLE " + table + " DROP FOREIGN KEY " + constraintName)) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to remove foreign key: " + e.getMessage(), e);
            }
        });
    }
}
