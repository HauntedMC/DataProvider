package nl.hauntedmc.dataprovider.database.impl;

import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.database.schema.ColumnDefinition;
import nl.hauntedmc.dataprovider.database.schema.SchemaManager;
import nl.hauntedmc.dataprovider.database.schema.TableDefinition;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
            sb.append("CREATE TABLE IF NOT EXISTS ")
                    .append(tableDefinition.getTableName())
                    .append(" (");

            List<String> columnDefinitions = new ArrayList<>();
            for (ColumnDefinition column : tableDefinition.getColumns()) {
                StringBuilder colDef = new StringBuilder();
                colDef.append(column.getName())
                        .append(" ")
                        .append(column.getType());

                if (column.isNotNull()) colDef.append(" NOT NULL");
                if (column.isAutoIncrement()) colDef.append(" AUTO_INCREMENT");
                if (column.getDefaultValue() != null) colDef.append(" DEFAULT ").append(column.getDefaultValue());

                columnDefinitions.add(colDef.toString());
            }

            sb.append(String.join(", ", columnDefinitions));

            if (tableDefinition.getPrimaryKey() != null) {
                sb.append(", PRIMARY KEY (").append(tableDefinition.getPrimaryKey()).append(")");
            }

            sb.append(") ENGINE=InnoDB;");

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sb.toString())) {
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to create table: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> updateTable(TableDefinition tableDefinition) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Table updates not yet implemented."));
    }
}
