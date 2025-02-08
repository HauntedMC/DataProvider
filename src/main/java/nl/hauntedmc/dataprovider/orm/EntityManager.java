package nl.hauntedmc.dataprovider.orm;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Common interface for storing & retrieving entities, either relational or doc-based.
 */
public interface EntityManager {

    /**
     * Save or update the entity.
     */
    <T> CompletableFuture<Void> save(T entity);

    /**
     * Find by the primary key / ID. Returns null if not found.
     */
    <T> CompletableFuture<T> findById(Class<T> clazz, Object id);

    /**
     * Find all entities of this class.
     * For large sets, be mindful of performance or add filtering.
     */
    <T> CompletableFuture<List<T>> findAll(Class<T> clazz);

    /**
     * Delete an entity by ID.
     */
    <T> CompletableFuture<Void> deleteById(Class<T> clazz, Object id);
}
