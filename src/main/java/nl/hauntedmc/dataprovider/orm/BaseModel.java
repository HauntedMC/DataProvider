package nl.hauntedmc.dataprovider.orm;

import nl.hauntedmc.dataprovider.orm.adapters.StorageAdapter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public abstract class BaseModel {
    protected static StorageAdapter storageAdapter;

    public static void setStorageAdapter(StorageAdapter adapter) {
        storageAdapter = adapter;
    }

    /**
     * Saves or updates the object.
     */
    public CompletableFuture<Void> save(Map<String, Object> data, String primaryKey) {
        String table = this.getClass().getSimpleName().toLowerCase();
        return storageAdapter.save(table, data, primaryKey);
    }

    /**
     * Finds a record by ID.
     */
    public static <T extends BaseModel> CompletableFuture<T> findById(Class<T> clazz, String primaryKey, Object keyValue) {
        String table = clazz.getSimpleName().toLowerCase();
        return storageAdapter.findById(table, primaryKey, keyValue).thenApply(result -> ORMMapper.map(result, clazz));
    }

    /**
     * Finds all records of the given class.
     */
    public static <T extends BaseModel> CompletableFuture<List<T>> findAll(Class<T> clazz) {
        String table = clazz.getSimpleName().toLowerCase();
        return storageAdapter.findAll(table).thenApply(results ->
                results.stream().map(result -> ORMMapper.map(result, clazz)).collect(Collectors.toList())
        );
    }
}
