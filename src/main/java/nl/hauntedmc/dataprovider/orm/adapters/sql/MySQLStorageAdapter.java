package nl.hauntedmc.dataprovider.orm.adapters.sql;

import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.orm.adapters.StorageAdapter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.StringJoiner;

public class MySQLStorageAdapter implements StorageAdapter {
    private final RelationalDataAccess dataAccess;

    public MySQLStorageAdapter(RelationalDataAccess dataAccess) {
        this.dataAccess = dataAccess;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> save(String table, Map<String, Object> data, String primaryKey) {
        StringJoiner columns = new StringJoiner(", ");
        StringJoiner values = new StringJoiner(", ");

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            columns.add(entry.getKey());
            values.add("'" + entry.getValue().toString() + "'");
        }

        String sql = "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ") " +
                "ON DUPLICATE KEY UPDATE " + primaryKey + " = VALUES(" + primaryKey + ")";

        return dataAccess.executeUpdate(sql);
    }

    @Override
    public CompletableFuture<Void> delete(String table, String primaryKey, Object keyValue) {
        String sql = "DELETE FROM " + table + " WHERE " + primaryKey + " = ?";
        return dataAccess.executeUpdate(sql, keyValue);
    }

    @Override
    public CompletableFuture<Map<String, Object>> findById(String table, String primaryKey, Object keyValue) {
        String sql = "SELECT * FROM " + table + " WHERE " + primaryKey + " = ?";
        return dataAccess.queryForSingle(sql, keyValue);
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> findAll(String table) {
        String sql = "SELECT * FROM " + table;
        return dataAccess.queryForList(sql);
    }
}
