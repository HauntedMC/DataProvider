package nl.hauntedmc.dataprovider.core.orm;

import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.hibernate.boot.registry.StandardServiceInitiator;
import org.hibernate.engine.transaction.jta.platform.internal.NoJtaPlatform;
import org.hibernate.engine.transaction.jta.platform.spi.JtaPlatform;
import org.hibernate.service.spi.ServiceRegistryImplementor;

import java.util.Map;
import java.util.Objects;

/**
 * DataProvider-scoped JTA service initiator for the local JDBC transaction model.
 *
 * <p>Hibernate's default JTA initiator emits HHH000489 at INFO even when the selected implementation is
 * {@link NoJtaPlatform}. DataProvider never uses JTA, so its service registries install the equivalent non-JTA
 * service directly and keep that routine bootstrap fact at DEBUG instead.</p>
 */
final class DataProviderJtaPlatformInitiator implements StandardServiceInitiator<JtaPlatform> {
    private final LoggerAdapter logger;

    DataProviderJtaPlatformInitiator(LoggerAdapter logger) {
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    @Override
    public Class<JtaPlatform> getServiceInitiated() {
        return JtaPlatform.class;
    }

    @Override
    public JtaPlatform initiateService(
            Map<String, Object> configurationValues,
            ServiceRegistryImplementor registry
    ) {
        logger.debug("Hibernate JTA integration disabled; DataProvider uses local JDBC transactions.");
        return NoJtaPlatform.INSTANCE;
    }
}
