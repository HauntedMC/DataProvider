package nl.hauntedmc.dataprovider.api.orm;

import nl.hauntedmc.dataprovider.config.ConfigHandler;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * ORMContext is an instantiable class that encapsulates the Hibernate SessionFactory
 * and its associated StandardServiceRegistry for a given plugin. This allows each plugin to
 * have its own isolated ORM configuration and lifecycle.
 */
public class ORMContext {

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

    /**
     * Initializes Hibernate using the provided DataSource and entity classes.
     *
     * @param entityClasses The entity classes to register.
     */
    private void initialize(Class<?>... entityClasses) {
        try {
            // Build the StandardServiceRegistry using hibernate.cfg.xml and override the connection settings with our DataSource.
            registry = new StandardServiceRegistryBuilder()
                    .applySetting("hibernate.connection.datasource", dataSource)
                    .applySetting("hibernate.hbm2ddl.auto", schemaMode)
                    .applySetting("hibernate.show_sql", "false")
                    .applySetting("hibernate.format_sql", "false")
                    .applySetting("hibernate.use_sql_comments", "false")
                    .build();

            // Create MetadataSources and register each provided entity class.
            MetadataSources metadataSources = new MetadataSources(registry);

            for (Class<?> entityClass : entityClasses) {
                metadataSources.addAnnotatedClass(entityClass);
                logger.info("Initializing Annotated Class: " + entityClass.getName());
            }

            // Build the Metadata and SessionFactory.
            Metadata metadata = metadataSources.getMetadataBuilder().build();

            if (metadata.getEntityBindings().isEmpty()) {
                logger.warn("No entity bindings were found in metadata");
            } else {
                metadata.getEntityBindings().forEach(
                        entityBinding -> logger.info("Entity binding: " + entityBinding.getEntityName())
                );
            }

            sessionFactory = metadata.getSessionFactoryBuilder().build();

            logger.info("Hibernate schema mode for plugin " + plugin + ": " + schemaMode);
            logger.info("Hibernate ORMContext initialized successfully for plugin: " + plugin);
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

    /**
     * Returns the SessionFactory associated with this ORMContext.
     *
     * @return The SessionFactory.
     * @throws IllegalStateException if the SessionFactory is not initialized.
     */
    public SessionFactory getSessionFactory() {
        if (sessionFactory == null) {
            throw new IllegalStateException("SessionFactory is not initialized for plugin: " + plugin);
        }
        return sessionFactory;
    }

    /**
     * Opens and returns a new Hibernate Session.
     *
     * @return A new Session.
     */
    public Session openSession() {
        return getSessionFactory().openSession();
    }

    /**
     * Executes a block of work within a transaction.
     *
     * @param callback The transactional work to execute.
     * @param <T>      The return type of the work.
     * @return The result of the transactional work.
     * @throws RuntimeException if the transaction fails.
     */
    public <T> T runInTransaction(TransactionCallback<T> callback) {
        Transaction tx = null;
        try (Session session = openSession()) {
            tx = session.beginTransaction();
            T result = callback.execute(session);
            tx.commit();
            return result;
        } catch (Exception e) {
            if (tx != null && tx.isActive()) {
                tx.rollback();
            }
            logger.error("Transaction failed in plugin: " + plugin + " - " + e.getMessage(), e);
            throw new RuntimeException("Transaction failed", e);
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
        logger.info("Hibernate ORMContext shut down for plugin: " + plugin);
    }

    /**
     * A callback interface for executing work within a Hibernate Session.
     *
     * @param <T> The return type.
     */
    public interface TransactionCallback<T> {
        T execute(Session session);
    }
}
