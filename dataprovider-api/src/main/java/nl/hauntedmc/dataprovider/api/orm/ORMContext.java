package nl.hauntedmc.dataprovider.api.orm;

import org.hibernate.Session;
import org.hibernate.SessionFactory;

/**
 * Public lifecycle contract for a plugin-owned Hibernate context.
 *
 * <p>Create contexts through {@link nl.hauntedmc.dataprovider.api.DataProviderAPI};
 * the server-hosted DataProvider runtime supplies the implementation.</p>
 */
public interface ORMContext extends AutoCloseable {

    SessionFactory getSessionFactory();

    Session openSession();

    <T> T runInTransaction(TransactionCallback<T> callback);

    void shutdown();

    @Override
    default void close() {
        shutdown();
    }

    /**
     * Work executed within a Hibernate transaction.
     *
     * @param <T> result type
     */
    @FunctionalInterface
    interface TransactionCallback<T> {
        T execute(Session session) throws Exception;
    }
}
