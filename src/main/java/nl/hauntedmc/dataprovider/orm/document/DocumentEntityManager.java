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
            // call PreSave
            EntityLifecycle.callPreSave(entity);

            Map<String, Object> doc = EntityMapper.entityToMap(entity, meta);

            // get ID
            Field idField = meta.getIdField();
            Object idValue;
            try {
                idValue = idField.get(entity);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            if (idValue == null) {
                // auto-generate if needed
                if (idField.getAnnotation(nl.hauntedmc.dataprovider.orm.annotations.Id.class).autoGenerate()) {
                    idValue = UUID.randomUUID().toString();
                    try {
                        idField.set(entity, idValue);
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                    doc.put(idField.getName().toLowerCase(), idValue);
                }
            }

            // see if doc exists
            DocumentQuery query = new DocumentQuery().eq(idField.getName().toLowerCase(), idValue);
            Map<String, Object> existing = dataAccess.findOne(meta.getEntityName(), query).join();
            if (existing == null) {
                // insert
                dataAccess.insertOne(meta.getEntityName(), doc).join();
            } else {
                // update
                DocumentUpdate update = new DocumentUpdate();
                doc.forEach((k, v) -> update.set(k, v));
                DocumentUpdateOptions opts = new DocumentUpdateOptions().upsert(true);
                dataAccess.updateOne(meta.getEntityName(), query, update, opts).join();
            }
        });
    }

    @Override
    public <T> CompletableFuture<T> findById(Class<T> clazz, Object id) {
        return CompletableFuture.supplyAsync(() -> {
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            String idFieldName = meta.getIdField().getName().toLowerCase();
            DocumentQuery query = new DocumentQuery().eq(idFieldName, id);

            Map<String, Object> found = dataAccess.findOne(meta.getEntityName(), query).join();
            if (found == null) return null;
            T entity = EntityMapper.mapRowToEntity(found, clazz, meta);
            EntityLifecycle.callPostLoad(entity);
            return entity;
        });
    }

    @Override
    public <T> CompletableFuture<List<T>> findAll(Class<T> clazz) {
        return CompletableFuture.supplyAsync(() -> {
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            // findAll => we do an empty query
            List<Map<String,Object>> docs = dataAccess.findMany(meta.getEntityName(), new DocumentQuery()).join();
            List<T> result = new ArrayList<>();
            for (var doc : docs) {
                T entity = EntityMapper.mapRowToEntity(doc, clazz, meta);
                EntityLifecycle.callPostLoad(entity);
                result.add(entity);
            }
            return result;
        });
    }

    @Override
    public <T> CompletableFuture<Void> deleteById(Class<T> clazz, Object id) {
        return CompletableFuture.runAsync(() -> {
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            String idFieldName = meta.getIdField().getName().toLowerCase();
            DocumentQuery query = new DocumentQuery().eq(idFieldName, id);
            dataAccess.deleteOne(meta.getEntityName(), query).join();
        });
    }
}
