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
 * A simple Hibernate wrapper that initializes Hibernate using a supplied DataSource.
 * It provides a singleton SessionFactory and a helper method to run transactional work.
 */
public class ORMManager {

    private static SessionFactory sessionFactory;

    /**
     * Initializes Hibernate with the given DataSource.
     * <p>
     * This method should be called once at startup.
     *
     * @param dataSource the DataSource to use for obtaining connections.
     */
    public static void initialize(DataSource dataSource) {
        // Build the service registry using settings from hibernate.cfg.xml
        // and override the connection setting with our DataSource.
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .configure() // Loads hibernate.cfg.xml from the classpath.
                .applySetting("hibernate.connection.datasource", dataSource)
                .build();

        Metadata metadata = new MetadataSources(registry)
                .getMetadataBuilder()
                .build();

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
     * Shuts down Hibernate, closing the SessionFactory.
     */
    public static void shutdown() {
        if (sessionFactory != null) {
            sessionFactory.close();
            sessionFactory = null;
        }
    }

    /**
     * Executes the given transactional work.
     *
     * @param <T>      the result type
     * @param callback the work to execute in a transaction.
     * @return the result of the work
     */
    public static <T> T runInTransaction(TransactionCallback<T> callback) {
        Session session = getSessionFactory().openSession();
        Transaction tx = null;
        try {
            tx = session.beginTransaction();
            T result = callback.execute(session);
            tx.commit();
            return result;
        } catch (Exception e) {
            if (tx != null && tx.isActive()) {
                tx.rollback();
            }
            throw new RuntimeException("Transaction failed", e);
        } finally {
            session.close();
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
