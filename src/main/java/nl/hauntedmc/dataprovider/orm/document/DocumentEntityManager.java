package nl.hauntedmc.dataprovider.orm.document;

import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.model.DocumentQuery;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdate;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdateOptions;
import nl.hauntedmc.dataprovider.orm.EntityManager;
import nl.hauntedmc.dataprovider.orm.introspection.EntityIntrospector;
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
            var meta = EntityIntrospector.introspect(entity.getClass());
            String collection = meta.getEntityName();

            // Convert entity -> Map
            Map<String, Object> doc = null;
            try {
                doc = EntityMapper.entityToMap(entity, meta);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }

            // figure out _id or ID
            Field idField = meta.getIdField();
            try {
                Object idValue = idField.get(entity);
                if (idValue == null && idField.getAnnotation(nl.hauntedmc.dataprovider.orm.annotations.Id.class).autoGenerate()) {
                    // we might generate an ID if doc DB doesn't do it automatically, e.g. a UUID
                    idValue = UUID.randomUUID().toString();
                    idField.set(entity, idValue);
                    doc.put(idField.getName(), idValue);
                }
                // Check if doc already exists
                var query = new DocumentQuery().eq(idField.getName(), idValue);
                var existing = dataAccess.findOne(collection, query).join();
                if (existing == null) {
                    // Insert
                    dataAccess.insertOne(collection, doc).join();
                } else {
                    // Update
                    var updateMap = new HashMap<String, Object>();
                    updateMap.put("$set", doc);
                    // or build a DocumentUpdate DSL 
                    DocumentUpdate documentUpdate = new DocumentUpdate();
                    doc.forEach((k,v)-> documentUpdate.set(k,v));
                    DocumentUpdateOptions opts = new DocumentUpdateOptions().upsert(true);
                    dataAccess.updateOne(collection, query, documentUpdate, opts).join();
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public <T> CompletableFuture<T> findById(Class<T> clazz, Object id) {
        return CompletableFuture.supplyAsync(() -> {
            var meta = EntityIntrospector.introspect(clazz);
            var query = new DocumentQuery().eq(meta.getIdField().getName(), id);
            var doc = dataAccess.findOne(meta.getEntityName(), query).join();
            if (doc == null) return null;
            return EntityMapper.mapDocumentToEntity(doc, clazz, meta);
        });
    }

    @Override
    public <T> CompletableFuture<List<T>> findAll(Class<T> clazz) {
        return CompletableFuture.supplyAsync(() -> {
            var meta = EntityIntrospector.introspect(clazz);
            var docs = dataAccess.findMany(meta.getEntityName(), new DocumentQuery()).join();
            List<T> result = new ArrayList<>();
            for (var d : docs) {
                result.add(EntityMapper.mapDocumentToEntity(d, clazz, meta));
            }
            return result;
        });
    }

    @Override
    public <T> CompletableFuture<Void> deleteById(Class<T> clazz, Object id) {
        return CompletableFuture.runAsync(() -> {
            var meta = EntityIntrospector.introspect(clazz);
            var query = new DocumentQuery().eq(meta.getIdField().getName(), id);
            dataAccess.deleteOne(meta.getEntityName(), query).join();
        });
    }
}
