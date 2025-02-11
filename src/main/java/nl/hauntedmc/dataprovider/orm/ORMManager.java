package nl.hauntedmc.dataprovider.orm;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

import javax.sql.DataSource;

/**
 * A simple Hibernate wrapper that initializes Hibernate using a provided DataSource and a list of entity classes.
 */
public class ORMManager {

    private static SessionFactory sessionFactory;

    /**
     * Initializes Hibernate with the given DataSource and registers the provided entity classes.
     * <p>
     * This method must be called once at startup.
     *
     * @param dataSource    the DataSource to use for connections.
     * @param entityClasses one or more entity classes to register.
     */
    public static void initialize(DataSource dataSource, Class<?>... entityClasses) {
        // Build the registry from the hibernate.cfg.xml and override the connection settings with our DataSource.
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
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
    }

    /**
     * Returns the Hibernate SessionFactory.
     *
     * @return the SessionFactory
     * @throws IllegalStateException if OrmManager has not been initialized.
     */
    public static SessionFactory getSessionFactory() {
        if (sessionFactory == null) {
            throw new IllegalStateException("OrmManager is not initialized. Call initialize() first.");
        }
        return sessionFactory;
    }

    /**
     * Shuts down Hibernate by closing the SessionFactory.
     */
    public static void shutdown() {
        if (sessionFactory != null) {
            sessionFactory.close();
            sessionFactory = null;
        }
    }

    /**
     * Executes a block of work within a transaction.
     *
     * @param <T>      the return type.
     * @param callback the work to execute.
     * @return the result from the callback.
     */
    public static <T> T runInTransaction(TransactionCallback<T> callback) {
        Transaction tx = null;
        try (Session session = getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            T result = callback.execute(session);
            tx.commit();
            return result;
        } catch (Exception e) {
            if (tx != null && tx.isActive()) {
                tx.rollback();
            }
            throw new RuntimeException("Transaction failed", e);
        }
    }

    /**
     * A callback interface for transactional work.
     *
     * @param <T> the result type.
     */
    public interface TransactionCallback<T> {
        T execute(Session session);
    }
}
