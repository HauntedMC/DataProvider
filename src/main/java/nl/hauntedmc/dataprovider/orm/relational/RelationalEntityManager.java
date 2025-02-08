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
import java.util.function.Function;
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

            // Check if the row already exists (if idValue != null, do a SELECT)
            boolean isInsert = true;
            if (idValue != null) {
                String pkCol = idField.getName().toLowerCase(); // or use a helper to get mapped name
                String checkSql = "SELECT 1 FROM " + meta.getEntityName() + " WHERE " + pkCol + "=? LIMIT 1";
                Map<String, Object> found = dataAccess.queryForSingle(checkSql, idValue).join();
                if (found != null) {
                    isInsert = false;
                }
            }

            // Call the appropriate lifecycle hooks
            if (isInsert) {
                EntityLifecycle.callPreInsert(entity);
            } else {
                EntityLifecycle.callPreUpdate(entity);
            }

            // Build the row map to store in the database
            Map<String, Object> row = EntityMapper.entityToMap(entity, meta);

            if (isInsert) {
                insertRow(meta, row);
                EntityLifecycle.callPostInsert(entity);
            } else {
                updateRow(meta, row);
                EntityLifecycle.callPostUpdate(entity);
            }
        });
    }

    @Override
    public <T> CompletableFuture<T> findById(Class<T> clazz, Object id) {
        return CompletableFuture.supplyAsync(() -> {
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            Field idField = meta.getIdField();
            String pkCol = idField.getName().toLowerCase();

            String sql = "SELECT * FROM " + meta.getEntityName() +
                    " WHERE " + pkCol + "=? LIMIT 1";
            Map<String, Object> row = dataAccess.queryForSingle(sql, id).join();
            if (row == null) return null;

            T instance = EntityMapper.mapRowToEntity(row, clazz, meta);
            EntityLifecycle.callPostLoad(instance);
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
            for (Map<String, Object> row : rows) {
                T instance = EntityMapper.mapRowToEntity(row, clazz, meta);
                EntityLifecycle.callPostLoad(instance); // if desired
                result.add(instance);
            }
            return result;
        });
    }

    @Override
    public <T> CompletableFuture<Void> deleteById(Class<T> clazz, Object id) {
        return CompletableFuture.runAsync(() -> {
            T existing = findById(clazz, id).join();
            if (existing == null) return;
            EntityLifecycle.callPreDelete(existing);

            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            Field idField = meta.getIdField();
            String pkCol = idField.getName().toLowerCase();

            String sql = "DELETE FROM " + meta.getEntityName() +
                    " WHERE " + pkCol + "=?";
            dataAccess.executeUpdate(sql, id).join();

            EntityLifecycle.callPostDelete(existing);
        });
    }

    /**
     * Runs multiple ORM operations within one transaction.
     * The provided lambda receives this EntityManager so that all its operations
     * run using the same underlying connection.
     *
     * Example:
     * <pre>
     *   entityManager.runInTransaction(em -> {
     *       em.save(player1).join();
     *       em.save(player2).join();
     *       return null;
     *   });
     * </pre>
     *
     * @param work A function accepting this EntityManager and returning a result.
     * @param <T>  The type of the result.
     * @return A CompletableFuture with the result.
     */
    public <T> CompletableFuture<T> runInTransaction(Function<EntityManager, T> work) {
        return dataAccess.executeTransactionally(connection -> work.apply(this));
    }

    // ----------------------------------------------------------------
    // Helper methods to build SQL queries
    // ----------------------------------------------------------------

    private void insertRow(EntityIntrospector.EntityMetadata meta, Map<String, Object> row) {
        String tableName = meta.getEntityName();
        List<Object> params = new ArrayList<>();
        StringJoiner columns = new StringJoiner(", ");
        StringJoiner placeholders = new StringJoiner(", ");

        for (Map.Entry<String, Object> entry : row.entrySet()) {
            columns.add(entry.getKey());
            placeholders.add("?");
            params.add(entry.getValue());
        }
        String sql = "INSERT INTO " + tableName +
                " (" + columns + ") VALUES (" + placeholders + ")";
        dataAccess.executeUpdate(sql, params.toArray()).join();
    }

    /**
     * Updates a row based on the provided metadata and row map.
     * This method now extracts the primary key from the row itself.
     */
    private void updateRow(EntityMetadata meta, Map<String, Object> row) {
        String tableName = meta.getEntityName();
        Field idField = meta.getIdField();
        // We assume the id is stored in the row map under the key equal to the field name (lowercase)
        String idCol = idField.getName().toLowerCase();
        Object idVal = row.remove(idCol); // Remove ID from the update values

        List<Object> params = new ArrayList<>();
        String setClause = row.entrySet().stream()
                .map(e -> {
                    params.add(e.getValue());
                    return e.getKey() + "=?";
                })
                .collect(Collectors.joining(", "));

        String sql = "UPDATE " + tableName +
                " SET " + setClause +
                " WHERE " + idCol + "=?";
        params.add(idVal);
        dataAccess.executeUpdate(sql, params.toArray()).join();
    }
}
