package nl.hauntedmc.dataprovider.core.orm;

import nl.hauntedmc.dataprovider.core.config.ConfigHandler;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import nl.hauntedmc.dataprovider.api.orm.ORMContext.TransactionCallback;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.transaction.jta.platform.internal.NoJtaPlatform;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Internal Hibernate-backed implementation of the public ORM context contract. It encapsulates
 * a plugin-specific {@link SessionFactory} and {@link StandardServiceRegistry}.
 */
public class ORMContext implements nl.hauntedmc.dataprovider.api.orm.ORMContext {

    private static final String DEFAULT_SCHEMA_MODE = "validate";
    private static final Set<String> SUPPORTED_SCHEMA_MODES = Set.of("validate", "none", "update", "create");

    private final DataSource dataSource;
    private final String plugin;
    private final LoggerAdapter logger;
    private final String schemaMode;
    private SessionFactory sessionFactory;
    private StandardServiceRegistry registry;

    /**
     * Constructs and initializes the ORMContext for the given plugin.
     *
     * @param plugin        The plugin for which this ORMContext is created.
     * @param dataSource    The DataSource to be used for database connections.
     * @param configHandler Main config handler used to resolve orm schema mode.
     * @param logger        Logger instance.
     * @param entityClasses One or more annotated entity classes to register.
     * @throws IllegalArgumentException if plugin, dataSource, or entityClasses are null/empty.
     */
    public ORMContext(
            String plugin,
            DataSource dataSource,
            ConfigHandler configHandler,
            LoggerAdapter logger,
            Class<?>... entityClasses
    ) {
        this(plugin, dataSource, logger, resolveSchemaMode(configHandler), entityClasses);
    }

    /**
     * Constructs and initializes the ORMContext with an explicit schema mode.
     *
     * @param plugin        Plugin name for logging context.
     * @param dataSource    The DataSource to be used for database connections.
     * @param logger        Logger instance.
     * @param schemaMode    Hibernate schema mode: validate, none, update, create.
     * @param entityClasses One or more annotated entity classes to register.
     */
    public ORMContext(
            String plugin,
            DataSource dataSource,
            LoggerAdapter logger,
            String schemaMode,
            Class<?>... entityClasses
    ) {
        this.plugin = plugin;
        this.dataSource = Objects.requireNonNull(dataSource, "DataSource cannot be null");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null");
        this.schemaMode = normalizeSchemaMode(schemaMode, this.logger);
        if (entityClasses == null || entityClasses.length == 0) {
            throw new IllegalArgumentException("At least one entity class must be provided");
        }
        initialize(entityClasses);
    }

    ORMContext(String plugin, LoggerAdapter logger, SessionFactory sessionFactory) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.dataSource = null;
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null");
        this.schemaMode = DEFAULT_SCHEMA_MODE;
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "SessionFactory cannot be null");
    }

    /**
     * Initializes Hibernate using the provided DataSource and entity classes.
     *
     * @param entityClasses The entity classes to register.
     */
    private void initialize(Class<?>... entityClasses) {
        try {
            // DataProvider owns local JDBC transactions; explicitly selecting the non-JTA platform avoids
            // repeated Hibernate JTA auto-discovery for every feature-scoped SessionFactory. The custom JDBC
            // environment initiator only redirects Hibernate's routine connection-info block to our DEBUG logger.
            registry = new StandardServiceRegistryBuilder()
                    .addInitiator(new DataProviderJdbcEnvironmentInitiator(logger))
                    .applySetting("hibernate.connection.datasource", dataSource)
                    .applySetting("hibernate.hbm2ddl.auto", schemaMode)
                    .applySetting("hibernate.show_sql", "false")
                    .applySetting("hibernate.format_sql", "false")
                    .applySetting("hibernate.use_sql_comments", "false")
                    .applySetting("hibernate.transaction.jta.platform", NoJtaPlatform.INSTANCE)
                    .build();

            MetadataSources metadataSources = new MetadataSources(registry);

            for (Class<?> entityClass : entityClasses) {
                metadataSources.addAnnotatedClass(entityClass);
                logger.debug("Initializing annotated class: " + entityClass.getName());
            }

            Metadata metadata = metadataSources.getMetadataBuilder().build();

            if (metadata.getEntityBindings().isEmpty()) {
                logger.warn("No entity bindings were found in metadata");
            } else {
                metadata.getEntityBindings().forEach(
                        entityBinding -> logger.debug("Entity binding: " + entityBinding.getEntityName())
                );
            }

            sessionFactory = metadata.getSessionFactoryBuilder().build();

            logger.debug("Hibernate schema mode for plugin " + plugin + ": " + schemaMode);
            logger.debug("Hibernate ORMContext initialized successfully for plugin: " + plugin);
        } catch (Exception e) {
            logger.error("Failed to initialize Hibernate ORMContext for plugin: " + plugin, e);
            throw new RuntimeException("ORMContext initialization failed", e);
        }
    }

    private static String resolveSchemaMode(ConfigHandler configHandler) {
        Objects.requireNonNull(configHandler, "ConfigHandler cannot be null.");
        return configHandler.getOrmSchemaMode();
    }

    private static String normalizeSchemaMode(String schemaMode, LoggerAdapter logger) {
        if (schemaMode == null || schemaMode.isBlank()) {
            return DEFAULT_SCHEMA_MODE;
        }
        String normalized = schemaMode.trim().toLowerCase(Locale.ROOT);
        if (SUPPORTED_SCHEMA_MODES.contains(normalized)) {
            return normalized;
        }
        logger.warn("Invalid orm schema mode '" + schemaMode + "', falling back to '" + DEFAULT_SCHEMA_MODE + "'.");
        return DEFAULT_SCHEMA_MODE;
    }

    private SessionFactory requireSessionFactory() {
        if (sessionFactory == null) {
            throw new IllegalStateException("SessionFactory is not initialized for plugin: " + plugin);
        }
        return sessionFactory;
    }

    private Session openManagedSession() {
        return requireSessionFactory().openSession();
    }

    /**
     * Executes a block of work within a transaction.
     *
     * @param callback The transactional work to execute.
     * @param <T>      The return type of the work.
     * @return The result of the work.
     * @throws RuntimeException if the transaction fails.
     */
    public <T> T runInTransaction(TransactionCallback<T> callback) {
        Objects.requireNonNull(callback, "Transaction callback cannot be null.");
        try (Session session = openManagedSession()) {
            Transaction tx = session.beginTransaction();
            TransactionalSession callbackSession = TransactionalSession.create(session);
            try {
                T result = callback.execute(callbackSession.view());
                callbackSession.expire();
                tx.commit();
                return result;
            } catch (Exception e) {
                callbackSession.expire();
                rollback(tx, e);
                throw e;
            } catch (Error fatal) {
                callbackSession.expire();
                rollback(tx, fatal);
                throw fatal;
            }
        } catch (Exception e) {
            logger.error("Transaction failed in plugin: " + plugin + " - " + e.getMessage(), e);
            throw new RuntimeException("Transaction failed", e);
        }
    }

    private void rollback(Transaction tx, Throwable transactionException) {
        try {
            if (tx.isActive()) {
                tx.rollback();
            }
        } catch (Exception rollbackException) {
            transactionException.addSuppressed(rollbackException);
            logger.error(
                    "Transaction rollback failed in plugin: " + plugin + " - " + rollbackException.getMessage(),
                    rollbackException
            );
        }
    }

    /**
     * Shuts down the ORMContext by closing the SessionFactory and destroying the StandardServiceRegistry.
     * This should be called during the plugin's disable phase.
     */
    public void shutdown() {
        if (sessionFactory != null) {
            sessionFactory.close();
            sessionFactory = null;
        }
        if (registry != null) {
            StandardServiceRegistryBuilder.destroy(registry);
            registry = null;
        }
        logger.debug("Hibernate ORMContext shut down for plugin: " + plugin);
    }

    /**
     * A callback interface for executing work within a Hibernate Session.
     *
     * @param <T> The return type.
     */
}
