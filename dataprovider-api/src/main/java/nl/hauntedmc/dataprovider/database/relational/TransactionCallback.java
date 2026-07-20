package nl.hauntedmc.dataprovider.database.relational;

import java.sql.Connection;

/**
 * A callback interface for executing code within a transaction.
 *
 * @param <T> the result type
 */
@FunctionalInterface
public interface TransactionCallback<T> {
    T doInTransaction(Connection connection) throws Exception;
}
