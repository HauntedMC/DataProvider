package nl.hauntedmc.dataprovider.orm.relational;

import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.orm.EntityManager;
import nl.hauntedmc.dataprovider.orm.introspection.EntityIntrospector;
import nl.hauntedmc.dataprovider.orm.introspection.EntityIntrospector.EntityMetadata;
import nl.hauntedmc.dataprovider.orm.lifecycle.EntityLifecycle;
import nl.hauntedmc.dataprovider.orm.util.EntityMapper;
import nl.hauntedmc.dataprovider.orm.dialect.SQLDialect;
import nl.hauntedmc.dataprovider.orm.dialect.DefaultSQLDialect;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

public class RelationalEntityManager implements EntityManager {

    private final RelationalDataAccess dataAccess;
    private final SQLDialect dialect;

    public RelationalEntityManager(RelationalDataAccess dataAccess) {
        this.dataAccess = dataAccess;
        this.dialect = new DefaultSQLDialect();
    }

    @Override
    public <T> CompletableFuture<Void> save(T entity) {
        EntityMetadata meta = EntityIntrospector.introspect(entity.getClass());
        Field idField = meta.getIdField();
        Object idValue;
        try {
            idValue = idField.get(entity);
        } catch (IllegalAccessException e) {
            return CompletableFuture.failedFuture(e);
        }

        String pkCol = quote(idField.getName().toLowerCase());
        String checkSql = "SELECT 1 FROM " + quote(meta.getEntityName()) +
                " WHERE " + pkCol + "=? " + dialect.getLimitClause(1);

        if (idValue != null) {
            return dataAccess.queryForSingle(checkSql, idValue)
                    .thenCompose(found -> {
                        boolean isInsert = (found == null);
                        if (isInsert) {
                            EntityLifecycle.callPreInsert(entity);
                        } else {
                            EntityLifecycle.callPreUpdate(entity);
                        }
                        Map<String, Object> row = EntityMapper.entityToMap(entity, meta);
                        CompletableFuture<Void> future;
                        if (isInsert) {
                            future = insertRow(meta, row)
                                    .thenRun(() -> EntityLifecycle.callPostInsert(entity));
                        } else {
                            future = updateRow(meta, row)
                                    .thenRun(() -> EntityLifecycle.callPostUpdate(entity));
                        }
                        return future;
                    });
        } else {
            // id is null, assume insert
            EntityLifecycle.callPreInsert(entity);
            Map<String, Object> row = EntityMapper.entityToMap(entity, meta);
            return insertRow(meta, row)
                    .thenRun(() -> EntityLifecycle.callPostInsert(entity));
        }
    }

    @Override
    public <T> CompletableFuture<T> findById(Class<T> clazz, Object id) {
        EntityMetadata meta = EntityIntrospector.introspect(clazz);
        Field idField = meta.getIdField();
        String pkCol = quote(idField.getName().toLowerCase());

        String sql = "SELECT * FROM " + quote(meta.getEntityName()) +
                " WHERE " + pkCol + "=? " + dialect.getLimitClause(1);
        return dataAccess.queryForSingle(sql, id)
                .thenApply(row -> {
                    if (row == null) return null;
                    T instance = EntityMapper.mapRowToEntity(row, clazz, meta);
                    EntityLifecycle.callPostLoad(instance);
                    return instance;
                });
    }

    @Override
    public <T> CompletableFuture<List<T>> findAll(Class<T> clazz) {
        EntityMetadata meta = EntityIntrospector.introspect(clazz);
        String sql = "SELECT * FROM " + quote(meta.getEntityName());
        return dataAccess.queryForList(sql)
                .thenApply(rows -> {
                    List<T> result = new ArrayList<>();
                    for (Map<String, Object> row : rows) {
                        T instance = EntityMapper.mapRowToEntity(row, clazz, meta);
                        EntityLifecycle.callPostLoad(instance);
                        result.add(instance);
                    }
                    return result;
                });
    }

    @Override
    public <T> CompletableFuture<Void> deleteById(Class<T> clazz, Object id) {
        return findById(clazz, id).thenCompose(existing -> {
            if (existing == null) return CompletableFuture.completedFuture(null);
            EntityLifecycle.callPreDelete(existing);
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            Field idField = meta.getIdField();
            String pkCol = quote(idField.getName().toLowerCase());
            String sql = "DELETE FROM " + quote(meta.getEntityName()) +
                    " WHERE " + pkCol + "=?";
            return dataAccess.executeUpdate(sql, id)
                    .thenRun(() -> EntityLifecycle.callPostDelete(existing));
        });
    }

    /**
     * Runs multiple ORM operations within one transaction.
     * The work function now returns a CompletableFuture so that the entire chain stays asynchronous.
     *
     * Example:
     * <pre>
     *   entityManager.runInTransaction(em -> {
     *       return em.save(player1)
     *                .thenCompose(v -> em.save(player2));
     *   });
     * </pre>
     *
     * @param work A function accepting this EntityManager and returning a CompletableFuture.
     * @param <T>  The type of the result.
     * @return A CompletableFuture with the result.
     */
    public <T> CompletableFuture<T> runInTransaction(Function<EntityManager, CompletableFuture<T>> work) {
        return dataAccess.executeTransactionally(connection -> work.apply(this))
                .thenCompose(Function.identity());
    }

    // ----------------------------------------------------------------
    // Helper methods to build SQL queries
    // ----------------------------------------------------------------

    private CompletableFuture<Void> insertRow(EntityMetadata meta, Map<String, Object> row) {
        String tableName = quote(meta.getEntityName());
        List<Object> params = new ArrayList<>();
        StringJoiner columns = new StringJoiner(", ");
        StringJoiner placeholders = new StringJoiner(", ");

        for (Map.Entry<String, Object> entry : row.entrySet()) {
            columns.add(quote(entry.getKey()));
            placeholders.add("?");
            params.add(entry.getValue());
        }
        String sql = "INSERT INTO " + tableName +
                " (" + columns + ") VALUES (" + placeholders + ")";
        return dataAccess.executeUpdate(sql, params.toArray());
    }

    /**
     * Updates a row based on the provided metadata and row map.
     * The primary key is extracted from the row map.
     */
    private CompletableFuture<Void> updateRow(EntityMetadata meta, Map<String, Object> row) {
        String tableName = quote(meta.getEntityName());
        Field idField = meta.getIdField();
        String idCol = quote(idField.getName().toLowerCase());
        Object idVal = row.remove(idField.getName().toLowerCase()); // Remove ID from update values

        List<Object> params = new ArrayList<>();
        String setClause = row.entrySet().stream()
                .map(e -> {
                    params.add(e.getValue());
                    return quote(e.getKey()) + "=?";
                })
                .collect(Collectors.joining(", "));

        String sql = "UPDATE " + tableName +
                " SET " + setClause +
                " WHERE " + idCol + "=?";
        params.add(idVal);
        return dataAccess.executeUpdate(sql, params.toArray());
    }

    private String quote(String identifier) {
        return dialect.getIdentifierQuoteString() + identifier + dialect.getIdentifierQuoteString();
    }
}
