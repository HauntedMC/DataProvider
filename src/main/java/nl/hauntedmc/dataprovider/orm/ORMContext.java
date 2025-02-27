package nl.hauntedmc.dataprovider.orm;

import nl.hauntedmc.dataprovider.DataProviderApp;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * ORMContext is an instantiable class that encapsulates the Hibernate SessionFactory
 * and its associated StandardServiceRegistry for a given plugin. This allows each plugin to
 * have its own isolated ORM configuration and lifecycle.
 */
public class ORMContext {

    private final DataSource dataSource;
    private final String plugin;
    private SessionFactory sessionFactory;
    private StandardServiceRegistry registry;

    /**
     * Constructs and initializes the ORMContext for the given plugin.
     *
     * @param plugin        The plugin for which this ORMContext is created.
     * @param dataSource    The DataSource to be used for database connections.
     * @param entityClasses One or more annotated entity classes to register.
     * @throws IllegalArgumentException if plugin, dataSource, or entityClasses are null/empty.
     */
    public ORMContext(String plugin, DataSource dataSource, Class<?>... entityClasses) {
        this.plugin = plugin;
        this.dataSource = Objects.requireNonNull(dataSource, "DataSource cannot be null");
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
                    .configure() // Loads hibernate.cfg.xml from the classpath.
                    .applySetting("hibernate.connection.datasource", dataSource)
                    .build();

            // Create MetadataSources and register each provided entity class.
            MetadataSources metadataSources = new MetadataSources(registry);
            for (Class<?> entityClass : entityClasses) {
                metadataSources.addAnnotatedClass(entityClass);
            }

            // Build the Metadata and SessionFactory.
            Metadata metadata = metadataSources.getMetadataBuilder().build();
            sessionFactory = metadata.getSessionFactoryBuilder().build();

            DataProviderApp.getLogger().info("Hibernate ORMContext initialized successfully for plugin: " + plugin);
        } catch (Exception e) {
            DataProviderApp.getLogger().error("Failed to initialize Hibernate ORMContext for plugin: " + plugin);
            throw new RuntimeException("ORMContext initialization failed", e);
        }
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
            DataProviderApp.getLogger().error("Transaction failed in plugin: " + plugin + " - " + e.getMessage());
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
        DataProviderApp.getLogger().info("Hibernate ORMContext shut down for plugin: " + plugin);
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
