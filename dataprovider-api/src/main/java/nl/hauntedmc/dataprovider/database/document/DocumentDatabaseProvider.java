package nl.hauntedmc.dataprovider.database.document;

import nl.hauntedmc.dataprovider.database.DatabaseProvider;

import javax.sql.DataSource;

/**
 * DocumentDatabaseProvider is the parent interface for
 * document‐based databases (e.g. MongoDB, CouchDB).
 */
public interface DocumentDatabaseProvider extends DatabaseProvider {

    @Override
    DocumentDataAccess getDataAccess();

    @Override
    default DataSource getDataSource() {
        throw new UnsupportedOperationException("Document databases do not provide a DataSource");
    }
}
