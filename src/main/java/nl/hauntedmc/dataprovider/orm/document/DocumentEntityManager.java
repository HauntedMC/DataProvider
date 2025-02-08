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
            // 1) introspect
            EntityMetadata meta = EntityIntrospector.introspect(entity.getClass());
            Field idField = meta.getIdField();

            Object idVal;
            try {
                idVal = idField.get(entity);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }

            // 2) see if doc exists
            boolean isInsert = true;
            if (idVal != null) {
                DocumentQuery query = new DocumentQuery().eq(idField.getName().toLowerCase(), idVal);
                Map<String,Object> existing = dataAccess.findOne(meta.getEntityName(), query).join();
                if (existing != null) {
                    isInsert = false;
                }
            }

            // 3) call pre-lifecycle
            if (isInsert) {
                EntityLifecycle.callPreInsert(entity);
            } else {
                EntityLifecycle.callPreUpdate(entity);
            }
            // or also callPreSave if you keep that

            // 4) build doc from entity
            Map<String, Object> doc = EntityMapper.entityToMap(entity, meta);

            // 5) insert or update
            if (isInsert) {
                // if idVal == null && autoGenerate => generate a UUID
                if (idVal == null) {
                    // check if Id annotation says autoGenerate
                    // for brevity, we assume yes
                    idVal = UUID.randomUUID().toString();
                    try { idField.set(entity, idVal); } catch (Exception e) {}
                    doc.put(idField.getName().toLowerCase(), idVal);
                }
                dataAccess.insertOne(meta.getEntityName(), doc).join();
                EntityLifecycle.callPostInsert(entity);
            } else {
                DocumentQuery query = new DocumentQuery().eq(idField.getName().toLowerCase(), idVal);
                DocumentUpdate update = new DocumentUpdate();
                doc.forEach((k,v)-> update.set(k, v));
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
            Map<String,Object> found = dataAccess.findOne(meta.getEntityName(), query).join();
            if (found == null) return null;

            T entity = EntityMapper.mapRowToEntity(found, clazz, meta);
            // call PostLoad if you like
            // EntityLifecycle.callPostLoad(entity);
            return entity;
        });
    }

    @Override
    public <T> CompletableFuture<List<T>> findAll(Class<T> clazz) {
        return CompletableFuture.supplyAsync(() -> {
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            // empty query => all docs
            Map<String, Object> emptyFilter = Collections.emptyMap();
            DocumentQuery query = new DocumentQuery(); // no filter
            List<Map<String,Object>> docs = dataAccess.findMany(meta.getEntityName(), query).join();

            List<T> results = new ArrayList<>();
            for (var d : docs) {
                T entity = EntityMapper.mapRowToEntity(d, clazz, meta);
                // EntityLifecycle.callPostLoad(entity);
                results.add(entity);
            }
            return results;
        });
    }

    @Override
    public <T> CompletableFuture<Void> deleteById(Class<T> clazz, Object id) {
        return CompletableFuture.runAsync(() -> {
            // load entity so we can call preDelete / postDelete
            T existing = findById(clazz, id).join();
            if (existing == null) return;

            EntityLifecycle.callPreDelete(existing);

            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            String idFieldName = meta.getIdField().getName().toLowerCase();

            DocumentQuery query = new DocumentQuery().eq(idFieldName, id);
            dataAccess.deleteOne(meta.getEntityName(), query).join();

            EntityLifecycle.callPostDelete(existing);
        });
    }
}
