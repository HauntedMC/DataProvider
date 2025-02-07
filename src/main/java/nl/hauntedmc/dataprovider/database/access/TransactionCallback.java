package nl.hauntedmc.dataprovider.database.access;

import java.sql.Connection;

@FunctionalInterface
public interface TransactionCallback<T> {
    /**
     * Perform operations with the given Connection (within a transaction).
     *
     * @param connection the SQL connection (autoCommit = false)
     * @return T result of the transaction
     * @throws Exception if something goes wrong, triggers a rollback
     */
    T doInTransaction(Connection connection) throws Exception;
}
