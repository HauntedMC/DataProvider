package nl.hauntedmc.dataprovider.database.relational;

import java.sql.Connection;

/**
 * A callback interface for executing code within a transaction.
 *
 * <p>The connection is a callback-scoped view. DataProvider retains exclusive
 * control of commit, rollback, auto-commit and physical connection lifecycle;
 * the view becomes invalid as soon as this method returns.</p>
 *
 * @param <T> the result type
 */
@FunctionalInterface
public interface TransactionCallback<T> {
    T doInTransaction(Connection connection) throws Exception;
}
