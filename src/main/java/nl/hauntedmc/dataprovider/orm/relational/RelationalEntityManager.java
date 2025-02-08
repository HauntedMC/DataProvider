package nl.hauntedmc.dataprovider.orm.relational;

import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.orm.EntityManager;
import nl.hauntedmc.dataprovider.orm.introspection.EntityIntrospector;
import nl.hauntedmc.dataprovider.orm.introspection.EntityIntrospector.EntityMetadata;
import nl.hauntedmc.dataprovider.orm.lifecycle.EntityLifecycle;
import nl.hauntedmc.dataprovider.orm.util.EntityMapper;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class RelationalEntityManager implements EntityManager {

    private final RelationalDataAccess dataAccess;

    public RelationalEntityManager(RelationalDataAccess dataAccess) {
        this.dataAccess = dataAccess;
    }

    @Override
    public <T> CompletableFuture<Void> save(T entity) {
        return CompletableFuture.runAsync(() -> {
            EntityMetadata meta = EntityIntrospector.introspect(entity.getClass());
            Field idField = meta.getIdField();
            Object idValue;
            try {
                idValue = idField.get(entity);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }

            // Step 1) Check if row already exists in DB
            boolean isInsert = true;
            if (idValue != null) {
                // e.g. SELECT 1 FROM table WHERE pk=? limit 1
                String pkCol = idField.getName().toLowerCase(); // or parse from FieldMapping
                String checkSql = "SELECT 1 FROM " + meta.getEntityName() + " WHERE " + pkCol + "=? LIMIT 1";
                Map<String,Object> found = dataAccess.queryForSingle(checkSql, idValue).join();
                if (found != null) {
                    isInsert = false;
                }
            }

            // Step 2) Call the appropriate pre-lifecycle
            if (isInsert) {
                EntityLifecycle.callPreInsert(entity);
            } else {
                EntityLifecycle.callPreUpdate(entity);
            }

            // (Optionally also call PreSave if you like the old hook)
            // EntityLifecycle.callPreSave(entity);

            // Step 3) Build the row map to store
            Map<String,Object> row = EntityMapper.entityToMap(entity, meta);

            // 4) Insert or Update logic
            if (isInsert) {
                // Insert
                insertRow(meta, row);
                // PostInsert
                EntityLifecycle.callPostInsert(entity);
            } else {
                // Update
                updateRow(meta, row);
                // PostUpdate
                EntityLifecycle.callPostUpdate(entity);
            }
        });
    }

    /**
     * Utility to do an INSERT from a row map.
     */
    private void insertRow(EntityMetadata meta, Map<String,Object> row) {
        String tableName = meta.getEntityName();

        List<Object> params = new ArrayList<>();
        StringJoiner cols = new StringJoiner(", ");
        StringJoiner placeholders = new StringJoiner(", ");

        for (var entry : row.entrySet()) {
            cols.add(entry.getKey());
            placeholders.add("?");
            params.add(entry.getValue());
        }
        String sql = "INSERT INTO " + tableName + " (" + cols + ") VALUES (" + placeholders + ")";
        dataAccess.executeUpdate(sql, params.toArray()).join();
    }

    /**
     * Utility to do an UPDATE from a row map, using ID as the WHERE condition.
     */
    private void updateRow(EntityMetadata meta, Map<String,Object> row) {
        String tableName = meta.getEntityName();
        Field idField = meta.getIdField();
        Object idVal;
        try {
            idVal = idField.getType().cast(row.remove(idField.getName().toLowerCase()));
        } catch (Exception e) {
            throw new RuntimeException("No ID found in row for update!", e);
        }
        // Build set clause
        List<Object> params = new ArrayList<>();
        String setPart = row.entrySet().stream()
                .map(e -> e.getKey() + "=?")
                .collect(Collectors.joining(", "));
        // gather param values in order
        row.forEach((k,v)-> params.add(v));

        // where pk=?
        String pkCol = idField.getName().toLowerCase();
        String sql = "UPDATE " + tableName + " SET " + setPart + " WHERE " + pkCol + "=?";

        params.add(idVal); // last param is the ID
        dataAccess.executeUpdate(sql, params.toArray()).join();
    }

    @Override
    public <T> CompletableFuture<T> findById(Class<T> clazz, Object id) {
        return CompletableFuture.supplyAsync(() -> {
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            Field idField = meta.getIdField();
            String pkCol = idField.getName().toLowerCase();

            String sql = "SELECT * FROM " + meta.getEntityName() + " WHERE " + pkCol + "=? LIMIT 1";
            Map<String,Object> row = dataAccess.queryForSingle(sql, id).join();
            if (row == null) return null;

            T instance = EntityMapper.mapRowToEntity(row, clazz, meta);
            // call postLoad if desired
            // EntityLifecycle.callPostLoad(instance);
            return instance;
        });
    }

    @Override
    public <T> CompletableFuture<List<T>> findAll(Class<T> clazz) {
        return CompletableFuture.supplyAsync(() -> {
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            String sql = "SELECT * FROM " + meta.getEntityName();
            List<Map<String, Object>> rows = dataAccess.queryForList(sql).join();

            List<T> result = new ArrayList<>();
            for (var row : rows) {
                T inst = EntityMapper.mapRowToEntity(row, clazz, meta);
                // EntityLifecycle.callPostLoad(inst);
                result.add(inst);
            }
            return result;
        });
    }

    @Override
    public <T> CompletableFuture<Void> deleteById(Class<T> clazz, Object id) {
        return CompletableFuture.runAsync(() -> {
            // 1) load the entity if we want to call preDelete / postDelete
            T existing = findById(clazz, id).join();
            if (existing == null) {
                // no entity found; no lifecycle calls
                return;
            }
            // PreDelete
            EntityLifecycle.callPreDelete(existing);

            // 2) do the delete
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            Field idField = meta.getIdField();
            String pkCol = idField.getName().toLowerCase();

            String sql = "DELETE FROM " + meta.getEntityName() + " WHERE " + pkCol + "=?";

            dataAccess.executeUpdate(sql, id).join();

            // PostDelete
            EntityLifecycle.callPostDelete(existing);
        });
    }
}
