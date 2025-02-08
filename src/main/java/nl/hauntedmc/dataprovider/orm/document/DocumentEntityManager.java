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
import java.util.concurrent.CompletableFuture;

public class DocumentEntityManager implements EntityManager {

    private final DocumentDataAccess dataAccess;

    public DocumentEntityManager(DocumentDataAccess dataAccess) {
        this.dataAccess = dataAccess;
    }

    @Override
    public <T> CompletableFuture<Void> save(T entity) {
        return CompletableFuture.runAsync(() -> {
            EntityMetadata meta = EntityIntrospector.introspect(entity.getClass());
            Field idField = meta.getIdField();
            Object idVal;
            try {
                idVal = idField.get(entity);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            boolean isInsert = true;
            if (idVal != null) {
                DocumentQuery query = new DocumentQuery().eq(idField.getName().toLowerCase(), idVal);
                Map<String, Object> found = dataAccess.findOne(meta.getEntityName(), query).join();
                if (found != null) {
                    isInsert = false;
                }
            }
            if (isInsert) {
                EntityLifecycle.callPreInsert(entity);
            } else {
                EntityLifecycle.callPreUpdate(entity);
            }
            Map<String, Object> doc = EntityMapper.entityToMap(entity, meta);
            if (isInsert) {
                if (idVal == null) {
                    idVal = UUID.randomUUID().toString();
                    try {
                        idField.set(entity, idVal);
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                    doc.put(idField.getName().toLowerCase(), idVal);
                }
                dataAccess.insertOne(meta.getEntityName(), doc).join();
                EntityLifecycle.callPostInsert(entity);
            } else {
                DocumentQuery query = new DocumentQuery().eq(idField.getName().toLowerCase(), idVal);
                DocumentUpdate update = new DocumentUpdate();
                doc.forEach(update::set);
                DocumentUpdateOptions opts = new DocumentUpdateOptions().upsert(false);
                dataAccess.updateOne(meta.getEntityName(), query, update, opts).join();
                EntityLifecycle.callPostUpdate(entity);
            }
        });
    }

    @Override
    public <T> CompletableFuture<T> findById(Class<T> clazz, Object id) {
        return CompletableFuture.supplyAsync(() -> {
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            String idFieldName = meta.getIdField().getName().toLowerCase();
            DocumentQuery query = new DocumentQuery().eq(idFieldName, id);
            Map<String, Object> doc = dataAccess.findOne(meta.getEntityName(), query).join();
            if (doc == null) return null;
            T entity = EntityMapper.mapRowToEntity(doc, clazz, meta);
            EntityLifecycle.callPostLoad(entity);
            return entity;
        });
    }

    @Override
    public <T> CompletableFuture<List<T>> findAll(Class<T> clazz) {
        return CompletableFuture.supplyAsync(() -> {
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            DocumentQuery query = new DocumentQuery();
            List<Map<String, Object>> docs = dataAccess.findMany(meta.getEntityName(), query).join();
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
        return CompletableFuture.runAsync(() -> {
            T entity = findById(clazz, id).join();
            if (entity == null) return;
            EntityLifecycle.callPreDelete(entity);
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            String idFieldName = meta.getIdField().getName().toLowerCase();
            DocumentQuery query = new DocumentQuery().eq(idFieldName, id);
            dataAccess.deleteOne(meta.getEntityName(), query).join();
            EntityLifecycle.callPostDelete(entity);
        });
    }
}
