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
            // 1) call @PreSave
            EntityLifecycle.callPreSave(entity);

            Map<String,Object> row = EntityMapper.entityToMap(entity, meta);

            // 2) build an "INSERT... ON DUPLICATE KEY UPDATE" style or do a separate logic if ID is null
            List<Object> paramValues = new ArrayList<>();
            StringJoiner columns = new StringJoiner(", ");
            StringJoiner placeholders = new StringJoiner(", ");
            for (Map.Entry<String, Object> e : row.entrySet()) {
                columns.add(e.getKey());
                placeholders.add("?");
                paramValues.add(e.getValue());
            }
            String tableName = meta.getEntityName();
            // Build the "ON DUPLICATE KEY UPDATE" part
            String updatePart = row.keySet().stream()
                    .map(k -> k + " = VALUES(" + k + ")")
                    .collect(Collectors.joining(", "));

            String sql = "INSERT INTO " + tableName +
                    " (" + columns + ") VALUES (" + placeholders + ") " +
                    "ON DUPLICATE KEY UPDATE " + updatePart;

            // 3) execute
            dataAccess.executeUpdate(sql, paramValues.toArray()).join();
        });
    }

    @Override
    public <T> CompletableFuture<T> findById(Class<T> clazz, Object id) {
        return CompletableFuture.supplyAsync(() -> {
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            Field idField = meta.getIdField();
            String idCol = (idField.getName().toLowerCase());
            // or read from FieldMapping if needed

            String sql = "SELECT * FROM " + meta.getEntityName() +
                    " WHERE " + idCol + " = ? LIMIT 1";

            Map<String, Object> row = dataAccess.queryForSingle(sql, id).join();
            if (row == null) return null;

            T entity = EntityMapper.mapRowToEntity(row, clazz, meta);
            // call @PostLoad
            EntityLifecycle.callPostLoad(entity);
            return entity;
        });
    }

    @Override
    public <T> CompletableFuture<List<T>> findAll(Class<T> clazz) {
        return CompletableFuture.supplyAsync(() -> {
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            String sql = "SELECT * FROM " + meta.getEntityName();
            List<Map<String,Object>> rows = dataAccess.queryForList(sql).join();
            List<T> result = new ArrayList<>();
            for (Map<String,Object> row : rows) {
                T entity = EntityMapper.mapRowToEntity(row, clazz, meta);
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
            Field idField = meta.getIdField();
            String idCol = idField.getName().toLowerCase();

            String sql = "DELETE FROM " + meta.getEntityName() + " WHERE " + idCol + " = ?";
            dataAccess.executeUpdate(sql, id).join();
        });
    }
}
