package nl.hauntedmc.dataprovider.orm.relational;

import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.orm.EntityManager;
import nl.hauntedmc.dataprovider.orm.introspection.EntityIntrospector;
import nl.hauntedmc.dataprovider.orm.util.EntityMapper;
import org.bukkit.configuration.file.FileConfiguration;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class RelationalEntityManager implements EntityManager {

    private final RelationalDataAccess dataAccess;
    // You might store a config if needed for table creation logic
    private final FileConfiguration config; // optional

    public RelationalEntityManager(RelationalDataAccess dataAccess, FileConfiguration config) {
        this.dataAccess = dataAccess;
        this.config = config;
    }

    @Override
    public <T> CompletableFuture<Void> save(T entity) {
        return CompletableFuture.runAsync(() -> {
            try {
                var meta = EntityIntrospector.introspect(entity.getClass());
                // build an INSERT or UPDATE
                Map<String, Object> columnValues = EntityMapper.entityToMap(entity, meta);

                // figure out ID value
                Field idField = meta.getIdField();
                Object idValue = idField.get(entity);

                // build an upsert style SQL, for example:
                // INSERT INTO table (col1, col2, ...) VALUES (...)
                // ON DUPLICATE KEY UPDATE colX=VALUES(colX), ...
                // simplified example:
                StringJoiner cols = new StringJoiner(", ");
                StringJoiner placeholders = new StringJoiner(", ");
                List<Object> params = new ArrayList<>();

                for (var entry : columnValues.entrySet()) {
                    cols.add(entry.getKey());
                    placeholders.add("?");
                    params.add(entry.getValue());
                }

                String tableName = meta.getEntityName();
                // Possibly you read config to prefix table or something

                String sql = "INSERT INTO " + tableName +
                        " (" + cols + ") VALUES (" + placeholders + ") " +
                        " ON DUPLICATE KEY UPDATE " + buildUpdatePart(columnValues);

                dataAccess.executeUpdate(sql, params.toArray()).join();

            } catch (Exception e) {
                throw new RuntimeException("Save failed", e);
            }
        });
    }

    private String buildUpdatePart(Map<String, Object> columns) {
        StringJoiner joiner = new StringJoiner(", ");
        for (String col : columns.keySet()) {
            joiner.add(col + " = VALUES(" + col + ")");
        }
        return joiner.toString();
    }

    @Override
    public <T> CompletableFuture<T> findById(Class<T> clazz, Object id) {
        return CompletableFuture.supplyAsync(() -> {
            var meta = EntityIntrospector.introspect(clazz);
            String tableName = meta.getEntityName();
            String primaryKey = meta.getIdField().getAnnotation(nl.hauntedmc.dataprovider.orm.annotations.Id.class).autoGenerate()
                    ? meta.getIdField().getName().toLowerCase()
                    : meta.getIdField().getName().toLowerCase(); // or we store the mapped column name

            // e.g. SELECT * FROM table WHERE pk = ?
            String sql = "SELECT * FROM " + tableName + " WHERE " + primaryKey + " = ?";
            Map<String, Object> row = dataAccess.queryForSingle(sql, id).join();
            if (row == null) return null;

            return EntityMapper.mapRowToEntity(row, clazz, meta);
        });
    }

    @Override
    public <T> CompletableFuture<List<T>> findAll(Class<T> clazz) {
        return CompletableFuture.supplyAsync(() -> {
            var meta = EntityIntrospector.introspect(clazz);
            String sql = "SELECT * FROM " + meta.getEntityName();
            List<Map<String, Object>> results = dataAccess.queryForList(sql).join();
            List<T> entities = new ArrayList<>();
            for (var row : results) {
                entities.add(EntityMapper.mapRowToEntity(row, clazz, meta));
            }
            return entities;
        });
    }

    @Override
    public <T> CompletableFuture<Void> deleteById(Class<T> clazz, Object id) {
        return CompletableFuture.runAsync(() -> {
            var meta = EntityIntrospector.introspect(clazz);
            // figure out pk name
            String pkCol = meta.getIdField().getName().toLowerCase();
            String sql = "DELETE FROM " + meta.getEntityName() + " WHERE " + pkCol + " = ?";
            dataAccess.executeUpdate(sql, id).join();
        });
    }
}
