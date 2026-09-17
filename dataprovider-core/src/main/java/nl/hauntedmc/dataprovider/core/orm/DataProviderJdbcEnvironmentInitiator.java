package nl.hauntedmc.dataprovider.core.orm;

import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.hibernate.engine.jdbc.connections.spi.DatabaseConnectionInfo;
import org.hibernate.engine.jdbc.env.internal.JdbcEnvironmentInitiator;

import java.util.Objects;

/**
 * DataProvider-scoped JDBC environment initiator that keeps Hibernate connection metadata out of normal startup
 * output while preserving it for DEBUG diagnostics.
 *
 * <p>Hibernate exposes {@link #logConnectionInfo(DatabaseConnectionInfo)} as a protected customization hook for
 * integrations that need to disable or redirect its standard INFO log. DataProvider installs this initiator only in
 * the feature-owned service registries it creates; global Hibernate logging remains untouched.</p>
 */
final class DataProviderJdbcEnvironmentInitiator extends JdbcEnvironmentInitiator {
    private final LoggerAdapter logger;

    DataProviderJdbcEnvironmentInitiator(LoggerAdapter logger) {
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    @Override
    protected void logConnectionInfo(DatabaseConnectionInfo databaseConnectionInfo) {
        logger.debug("Hibernate database info:\n" + databaseConnectionInfo.toInfoString());
    }
}
