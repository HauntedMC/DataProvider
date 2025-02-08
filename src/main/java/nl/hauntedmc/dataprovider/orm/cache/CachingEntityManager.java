package nl.hauntedmc.dataprovider.orm.cache;

import nl.hauntedmc.dataprovider.orm.EntityManager;
import nl.hauntedmc.dataprovider.orm.introspection.EntityIntrospector;
import java.util.Map;
import java.util.concurrent.*;

public class CachingEntityManager implements EntityManager {

    private final EntityManager delegate;
    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    public CachingEntityManager(EntityManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public <T> CompletableFuture<Void> save(T entity) {
        return delegate.save(entity).thenRun(() -> {
            // after saving, store in cache
            Object idVal = getIdValue(entity);
            if (idVal != null) {
                String key = cacheKey(entity.getClass(), idVal);
                cache.put(key, entity);
            }
        });
    }

    @Override
    public <T> CompletableFuture<T> findById(Class<T> clazz, Object id) {
        String key = cacheKey(clazz, id);
        if (cache.containsKey(key)) {
            // found in cache
            return CompletableFuture.completedFuture((T) cache.get(key));
        }
        // else load from DB
        return delegate.findById(clazz, id).thenApply(entity -> {
            if (entity != null) {
                cache.put(key, entity);
            }
            return entity;
        });
    }

    @Override
    public <T> CompletableFuture<java.util.List<T>> findAll(Class<T> clazz) {
        // Optional: choose if you want to read from cache or always query
        // For now we always query from DB
        return delegate.findAll(clazz);
    }

    @Override
    public <T> CompletableFuture<Void> deleteById(Class<T> clazz, Object id) {
        return delegate.deleteById(clazz, id).thenRun(() -> {
            // remove from cache if it existed
            String key = cacheKey(clazz, id);
            cache.remove(key);
        });
    }

    private String cacheKey(Class<?> clazz, Object id) {
        return clazz.getName() + "::" + id;
    }

    private Object getIdValue(Object entity) {
        var meta = EntityIntrospector.introspect(entity.getClass());
        try {
            return meta.getIdField().get(entity);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
