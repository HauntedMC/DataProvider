package nl.hauntedmc.dataprovider.core.orm;

import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.hibernate.engine.jdbc.connections.spi.DatabaseConnectionInfo;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataProviderJdbcEnvironmentInitiatorTest {

    @Test
    void routesHibernateConnectionInfoToDebug() {
        LoggerAdapter logger = mock(LoggerAdapter.class);
        DatabaseConnectionInfo connectionInfo = mock(DatabaseConnectionInfo.class);
        when(connectionInfo.toInfoString()).thenReturn("database metadata");

        new DataProviderJdbcEnvironmentInitiator(logger).logConnectionInfo(connectionInfo);

        verify(logger).debug("Hibernate database info:\ndatabase metadata");
    }
}
