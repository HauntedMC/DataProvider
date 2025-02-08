package nl.hauntedmc.dataprovider.database.document;

import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;

/**
 * DocumentDatabaseProvider is the parent interface for
 * document‐based databases (e.g. MongoDB, CouchDB).
 */
public interface DocumentDatabaseProvider extends BaseDatabaseProvider {

    @Override
    DocumentDataAccess getDataAccess();
}
