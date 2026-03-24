package nl.hauntedmc.dataprovider.database.relational.impl.mysql;

import nl.hauntedmc.dataprovider.database.relational.schema.ColumnDefinition;
import nl.hauntedmc.dataprovider.database.relational.schema.TableDefinition;
import nl.hauntedmc.dataprovider.testutil.DirectExecutorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MySQLSchemaManagerTest {

    @Test
    void createTableBuildsSafeSqlAndExecutesUpdate() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        MySQLSchemaManager manager = new MySQLSchemaManager(dataSource, new DirectExecutorService());
        TableDefinition table = new TableDefinition(
                "players",
                List.of(
                        new ColumnDefinition("id", "BIGINT", true, true),
                        new ColumnDefinition("name", "VARCHAR(64)", false, false)
                ),
                "id"
        );

        manager.createTable(table).join();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.startsWith("CREATE TABLE IF NOT EXISTS `players`"));
        assertTrue(sql.contains("`id` BIGINT NOT NULL AUTO_INCREMENT"));
        assertTrue(sql.contains("`name` VARCHAR(64)"));
        assertTrue(sql.contains("PRIMARY KEY (`id`)"));
        verify(statement).executeUpdate();
    }

    @Test
    void createTableValidatesDefinitionAndRejectsUnsafeTypes() {
        MySQLSchemaManager manager = new MySQLSchemaManager(mock(DataSource.class), new DirectExecutorService());
        assertFutureFailure(IllegalArgumentException.class, manager.createTable(null));
        assertFutureFailure(
                IllegalArgumentException.class,
                manager.createTable(new TableDefinition("players", List.of(), "id"))
        );
        assertFutureFailure(
                IllegalArgumentException.class,
                manager.createTable(new TableDefinition("players", listContainingNullColumn(), "id"))
        );
        assertFutureFailure(
                IllegalArgumentException.class,
                manager.createTable(new TableDefinition(
                        "players",
                        List.of(new ColumnDefinition("id", "VARCHAR(64); DROP TABLE test", true, false)),
                        "id"
                ))
        );
    }

    @Test
    void alterDropIndexAndForeignKeyOperationsBuildExpectedQueries() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        MySQLSchemaManager manager = new MySQLSchemaManager(dataSource, new DirectExecutorService());

        manager.alterTable(new TableDefinition(
                "players",
                List.of(new ColumnDefinition("score", "INT", true, false)),
                "id"
        )).join();
        manager.dropTable("players").join();
        manager.addIndex("players", "score", true).join();
        manager.removeIndex("players", "idx_score").join();
        manager.addForeignKey("players", "team_id", "teams", "id").join();
        manager.removeForeignKey("players", "fk_players_team_id").join();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, org.mockito.Mockito.atLeast(6)).prepareStatement(sqlCaptor.capture());
        List<String> sqls = sqlCaptor.getAllValues();
        assertTrue(sqls.stream().anyMatch(sql -> sql.startsWith("ALTER TABLE `players` ADD COLUMN `score` INT NOT NULL")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.equals("DROP TABLE IF EXISTS `players`")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("CREATE UNIQUE INDEX")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("DROP INDEX `idx_score` ON `players`")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("ADD CONSTRAINT `fk_players_team_id`")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("DROP FOREIGN KEY `fk_players_team_id`")));
    }

    @Test
    void tableExistsUsesPreparedStatementParameterAndReturnsResult() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(1);

        MySQLSchemaManager manager = new MySQLSchemaManager(dataSource, new DirectExecutorService());
        boolean exists = manager.tableExists("players").join();

        assertTrue(exists);
        verify(statement).setString(1, "players");
    }

    @Test
    void rejectsUnsafeIdentifiersBeforeQueryExecution() {
        MySQLSchemaManager manager = new MySQLSchemaManager(mock(DataSource.class), new DirectExecutorService());
        assertFutureFailure(IllegalArgumentException.class, manager.dropTable("bad-name"));
        assertFutureFailure(IllegalArgumentException.class, manager.addIndex("players", "bad-name", false));
        assertFutureFailure(IllegalArgumentException.class, manager.removeIndex("players", "bad-name"));
        assertFutureFailure(IllegalArgumentException.class, manager.addForeignKey("players", "team-id", "teams", "id"));
        assertFutureFailure(IllegalArgumentException.class, manager.removeForeignKey("players", "bad-name"));
    }

    private static void assertFutureFailure(Class<? extends Throwable> expected, CompletableFuture<?> future) {
        CompletionException ex = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(expected, ex.getCause());
    }

    private static List<ColumnDefinition> listContainingNullColumn() {
        List<ColumnDefinition> columns = new ArrayList<>();
        columns.add(null);
        return columns;
    }
}
