package nl.hauntedmc.dataprovider.core.concurrent;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionDataSourceTest {

    @Test
    void jdbcAcquisitionUsesScopedExecutionAndHonoursClosure() throws Exception {
        DataSource physical = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        AtomicReference<String> acquisitionThread = new AtomicReference<>();
        when(physical.getConnection()).thenAnswer(ignored -> {
            acquisitionThread.set(Thread.currentThread().getName());
            return connection;
        });

        try (DataProviderExecutionRuntime runtime = runtime()) {
            ExecutionHandle scope = runtime.openScope("orm-plugin", DatabaseType.MYSQL, "main");
            ExecutionDataSource source = new ExecutionDataSource(physical, scope);

            assertSame(connection, source.getConnection());
            assertEquals("dataprovider-relational-1", acquisitionThread.get());
            assertThrows(SQLException.class, () -> source.unwrap(Connection.class));

            scope.close();
            assertThrows(SQLException.class, source::getConnection);
        }
    }

    private static DataProviderExecutionRuntime runtime() {
        EnumMap<ExecutionLane, ExecutionRuntimeConfig.LaneConfig> lanes = new EnumMap<>(ExecutionLane.class);
        for (ExecutionLane lane : ExecutionLane.values()) {
            lanes.put(lane, new ExecutionRuntimeConfig.LaneConfig(1, 4, 4, 4));
        }
        return new DataProviderExecutionRuntime(new ExecutionRuntimeConfig(
                Map.copyOf(lanes), Duration.ZERO, Duration.ofMillis(100), 4, 2, 1));
    }
}
