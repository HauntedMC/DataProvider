package nl.hauntedmc.dataprovider.database.document;

import nl.hauntedmc.dataprovider.database.DatabaseProvider;

/**
 * DocumentDatabaseProvider is the parent interface for
 * document‐based databases (e.g. MongoDB, CouchDB).
 */
public interface DocumentDatabaseProvider extends DatabaseProvider {

    @Override
    DocumentDataAccess getDataAccess();
}
