package nl.hauntedmc.dataprovider.core.orm;

import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.hibernate.engine.transaction.jta.platform.internal.NoJtaPlatform;
import org.hibernate.service.spi.ServiceRegistryImplementor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DataProviderJtaPlatformInitiatorTest {

    @Test
    void suppliesNonJtaPlatformWithoutUsingHibernateDefaultInitiator() {
        LoggerAdapter logger = mock(LoggerAdapter.class);
        ServiceRegistryImplementor registry = mock(ServiceRegistryImplementor.class);
        DataProviderJtaPlatformInitiator initiator = new DataProviderJtaPlatformInitiator(logger);

        assertSame(NoJtaPlatform.INSTANCE, initiator.initiateService(Map.of(), registry));
        verify(logger).debug("Hibernate JTA integration disabled; DataProvider uses local JDBC transactions.");
    }
}
