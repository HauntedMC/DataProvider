package nl.hauntedmc.dataprovider.database.access;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface DataAccess {
    CompletableFuture<Void> executeUpdate(String query, Object... params);
    CompletableFuture<List<Map<String, Object>>> queryForList(String query, Object... params);
}
