package nl.hauntedmc.dataprovider.api.orm;

import org.hibernate.Session;

/**
 * Public lifecycle contract for a plugin-owned Hibernate context.
 *
 * <p>Create contexts through {@link nl.hauntedmc.dataprovider.api.DataProviderAPI};
 * the server-hosted DataProvider runtime supplies the implementation.</p>
 */
public interface ORMContext extends AutoCloseable {

    Session openSession();

    <T> T runInTransaction(TransactionCallback<T> callback);

    void shutdown();

    @Override
    default void close() {
        shutdown();
    }

    /**
     * Work executed within a callback-scoped Hibernate Session.
     *
     * <p>DataProvider owns transaction completion and Session lifecycle. The
     * callback view cannot commit, roll back, close, unwrap, or outlive the
     * transaction. JDBC work performed through the view receives the same
     * callback-scoped Connection protection.</p>
     *
     * @param <T> result type
     */
    @FunctionalInterface
    interface TransactionCallback<T> {
        T execute(Session session) throws Exception;
    }
}
