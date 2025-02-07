package nl.hauntedmc.dataprovider.orm.adapters;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface StorageAdapter {
    CompletableFuture<Void> initialize();

    CompletableFuture<Void> save(String table, Map<String, Object> data, String primaryKey);

    CompletableFuture<Void> delete(String table, String primaryKey, Object keyValue);

    CompletableFuture<Map<String, Object>> findById(String table, String primaryKey, Object keyValue);

    CompletableFuture<List<Map<String, Object>>> findAll(String table);
}
