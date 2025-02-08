package nl.hauntedmc.dataprovider.orm.document;

import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.model.DocumentQuery;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdate;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdateOptions;
import nl.hauntedmc.dataprovider.orm.EntityManager;
import nl.hauntedmc.dataprovider.orm.introspection.EntityIntrospector;
import nl.hauntedmc.dataprovider.orm.introspection.EntityIntrospector.EntityMetadata;
import nl.hauntedmc.dataprovider.orm.lifecycle.EntityLifecycle;
import nl.hauntedmc.dataprovider.orm.util.EntityMapper;

import java.lang.reflect.Field;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DocumentEntityManager implements EntityManager {

    private final DocumentDataAccess dataAccess;

    public DocumentEntityManager(DocumentDataAccess dataAccess) {
        this.dataAccess = dataAccess;
    }

    @Override
    public <T> CompletableFuture<Void> save(T entity) {
        EntityMetadata meta = EntityIntrospector.introspect(entity.getClass());
        Field idField = meta.getIdField();
        Object idVal;
        try {
            idVal = idField.get(entity);
        } catch (IllegalAccessException e) {
            return CompletableFuture.failedFuture(e);
        }
        boolean isInsert = (idVal == null);
        CompletableFuture<Boolean> existenceFuture;
        if (!isInsert) {
            DocumentQuery query = new DocumentQuery().eq(idField.getName().toLowerCase(), idVal);
            existenceFuture = dataAccess.findOne(meta.getEntityName(), query)
                    .thenApply(doc -> doc == null);
        } else {
            existenceFuture = CompletableFuture.completedFuture(true);
        }

        return existenceFuture.thenCompose(insert -> {
            final Map<String, Object> doc = EntityMapper.entityToMap(entity, meta);
            if (insert) {
                EntityLifecycle.callPreInsert(entity);
                final Object effectiveId;
                if (idVal == null) {
                    effectiveId = UUID.randomUUID().toString();
                    try {
                        idField.set(entity, effectiveId);
                    } catch (IllegalAccessException e) {
                        return CompletableFuture.failedFuture(e);
                    }
                    doc.put(idField.getName().toLowerCase(), effectiveId);
                } else {
                    effectiveId = idVal;
                }
                return dataAccess.insertOne(meta.getEntityName(), doc)
                        .thenRun(() -> EntityLifecycle.callPostInsert(entity));
            } else {
                EntityLifecycle.callPreUpdate(entity);
                DocumentQuery query = new DocumentQuery().eq(idField.getName().toLowerCase(), idVal);
                DocumentUpdate update = new DocumentUpdate();
                doc.forEach(update::set);
                DocumentUpdateOptions opts = new DocumentUpdateOptions().upsert(false);
                return dataAccess.updateOne(meta.getEntityName(), query, update, opts)
                        .thenRun(() -> EntityLifecycle.callPostUpdate(entity));
            }
        });
    }

    @Override
    public <T> CompletableFuture<T> findById(Class<T> clazz, Object id) {
        EntityMetadata meta = EntityIntrospector.introspect(clazz);
        String idFieldName = meta.getIdField().getName().toLowerCase();
        DocumentQuery query = new DocumentQuery().eq(idFieldName, id);
        return dataAccess.findOne(meta.getEntityName(), query)
                .thenApply(doc -> {
                    if (doc == null) return null;
                    T entity = EntityMapper.mapRowToEntity(doc, clazz, meta);
                    EntityLifecycle.callPostLoad(entity);
                    return entity;
                });
    }

    @Override
    public <T> CompletableFuture<List<T>> findAll(Class<T> clazz) {
        EntityMetadata meta = EntityIntrospector.introspect(clazz);
        DocumentQuery query = new DocumentQuery();
        return dataAccess.findMany(meta.getEntityName(), query)
                .thenApply(docs -> {
                    List<T> results = new ArrayList<>();
                    for (Map<String, Object> doc : docs) {
                        T entity = EntityMapper.mapRowToEntity(doc, clazz, meta);
                        EntityLifecycle.callPostLoad(entity);
                        results.add(entity);
                    }
                    return results;
                });
    }

    @Override
    public <T> CompletableFuture<Void> deleteById(Class<T> clazz, Object id) {
        return findById(clazz, id).thenCompose(entity -> {
            if (entity == null) return CompletableFuture.completedFuture(null);
            EntityLifecycle.callPreDelete(entity);
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            String idFieldName = meta.getIdField().getName().toLowerCase();
            DocumentQuery query = new DocumentQuery().eq(idFieldName, id);
            return dataAccess.deleteOne(meta.getEntityName(), query)
                    .thenRun(() -> EntityLifecycle.callPostDelete(entity));
        });
    }
}
