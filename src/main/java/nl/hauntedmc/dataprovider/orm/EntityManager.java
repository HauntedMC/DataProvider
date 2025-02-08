package nl.hauntedmc.dataprovider.orm;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface EntityManager {

    /**
     * Saves or updates an entity instance, based on its @Id field.
     */
    <T> CompletableFuture<Void> save(T entity);

    /**
     * Finds an entity by its primary ID.
     */
    <T> CompletableFuture<T> findById(Class<T> clazz, Object id);

    /**
     * Finds all entities of a given type.
     */
    <T> CompletableFuture<List<T>> findAll(Class<T> clazz);

    /**
     * Deletes an entity by ID.
     */
    <T> CompletableFuture<Void> deleteById(Class<T> clazz, Object id);
}
