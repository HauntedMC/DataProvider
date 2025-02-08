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
        this.dialect = new DefaultSQLDialect(); // Can be injected/configured as needed.
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

        // Use the centralized method to obtain the correct column name.
        String pkCol = quote(EntityMapper.getDatabaseFieldName(idField));
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
            // ID is null; assume insert.
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
        String pkCol = quote(EntityMapper.getDatabaseFieldName(idField));

        String sql = "SELECT * FROM " + quote(meta.getEntityName()) +
                " WHERE " + pkCol + "=? " + dialect.getLimitClause(1);
        return dataAccess.queryForSingle(sql, id)
                .thenCompose(row -> {
                    if (row == null) return CompletableFuture.completedFuture(null);
                    return EntityMapper.mapRowToEntity(row, clazz, meta, this);
                });
    }

    @Override
    public <T> CompletableFuture<List<T>> findAll(Class<T> clazz) {
        EntityMetadata meta = EntityIntrospector.introspect(clazz);
        String sql = "SELECT * FROM " + quote(meta.getEntityName());
        return dataAccess.queryForList(sql)
                .thenCompose(rows -> {
                    List<CompletableFuture<T>> futures = new ArrayList<>();
                    for (Map<String, Object> row : rows) {
                        futures.add(EntityMapper.mapRowToEntity(row, clazz, meta, this));
                    }
                    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
                });
    }

    @Override
    public <T> CompletableFuture<Void> deleteById(Class<T> clazz, Object id) {
        return findById(clazz, id).thenCompose(existing -> {
            if (existing == null) return CompletableFuture.completedFuture(null);
            EntityLifecycle.callPreDelete(existing);
            EntityMetadata meta = EntityIntrospector.introspect(clazz);
            Field idField = meta.getIdField();
            String pkCol = quote(EntityMapper.getDatabaseFieldName(idField));
            String sql = "DELETE FROM " + quote(meta.getEntityName()) +
                    " WHERE " + pkCol + "=?";
            return dataAccess.executeUpdate(sql, id)
                    .thenRun(() -> EntityLifecycle.callPostDelete(existing));
        });
    }

    /**
     * Runs multiple ORM operations within one transaction.
     * Example:
     * <pre>
     *   entityManager.runInTransaction(em -> {
     *       return em.save(player1)
     *                .thenCompose(v -> em.save(player2));
     *   });
     * </pre>
     * @param work A function accepting this EntityManager and returning a CompletableFuture.
     * @param <T>  The type of the result.
     * @return A CompletableFuture with the result.
     */
    public <T> CompletableFuture<T> runInTransaction(Function<EntityManager, CompletableFuture<T>> work) {
        return dataAccess.executeTransactionally(connection -> work.apply(this))
                .thenCompose(Function.identity());
    }

    /**
     * Finds all entities of the given class where the specified column matches the provided value.
     * This method is primarily used for OneToMany relationship loading.
     *
     * @param clazz  The entity class.
     * @param column The column name to filter on.
     * @param value  The value to match.
     * @param <T>    The type of the entity.
     * @return A CompletableFuture with the list of matching entities.
     */
    public <T> CompletableFuture<List<T>> findByColumn(Class<T> clazz, String column, Object value) {
        EntityIntrospector.EntityMetadata meta = EntityIntrospector.introspect(clazz);
        String sql = "SELECT * FROM " + quote(meta.getEntityName()) +
                " WHERE " + quote(column) + "=?";
        return dataAccess.queryForList(sql, value)
                .thenCompose(rows -> {
                    List<CompletableFuture<T>> futures = new ArrayList<>();
                    for (Map<String, Object> row : rows) {
                        futures.add(EntityMapper.mapRowToEntity(row, clazz, meta, this));
                    }
                    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
                });
    }

    // ----------------------------------------------------------------
    // Helper methods to build SQL queries
    // ----------------------------------------------------------------

    private CompletableFuture<Void> insertRow(EntityIntrospector.EntityMetadata meta, Map<String, Object> row) {
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
    private CompletableFuture<Void> updateRow(EntityIntrospector.EntityMetadata meta, Map<String, Object> row) {
        String tableName = quote(meta.getEntityName());
        Field idField = meta.getIdField();
        String idKey = EntityMapper.getDatabaseFieldName(idField);
        String idCol = quote(idKey);
        Object idVal = row.remove(idKey); // Remove ID from update values

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
