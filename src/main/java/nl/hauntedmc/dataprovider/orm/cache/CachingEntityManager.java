package nl.hauntedmc.dataprovider.orm.cache;

import nl.hauntedmc.dataprovider.orm.EntityManager;
import nl.hauntedmc.dataprovider.orm.introspection.EntityIntrospector;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class CachingEntityManager implements EntityManager {

    private final EntityManager delegate;
    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    public CachingEntityManager(EntityManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public <T> CompletableFuture<Void> save(T entity) {
        return delegate.save(entity).thenRun(() -> {
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
            return CompletableFuture.completedFuture((T) cache.get(key));
        }
        return delegate.findById(clazz, id).thenApply(entity -> {
            if (entity != null) {
                cache.put(key, entity);
            }
            return entity;
        });
    }

    @Override
    public <T> CompletableFuture<java.util.List<T>> findAll(Class<T> clazz) {
        // Currently, findAll queries the DB directly. Modify as needed.
        return delegate.findAll(clazz);
    }

    @Override
    public <T> CompletableFuture<Void> deleteById(Class<T> clazz, Object id) {
        return delegate.deleteById(clazz, id).thenRun(() -> {
            String key = cacheKey(clazz, id);
            cache.remove(key);
        });
    }

    /**
     * Clears the entire entity cache.
     */
    public void clearCache() {
        cache.clear();
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
