package nl.hauntedmc.dataprovider.database;

import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.model.DocumentQuery;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdate;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdateOptions;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.TransactionCallback;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAccessConvenienceTest {

    @Test
    void keyValueConveniencesDelegateWithSafeUnitsAndFallbacks() {
        AtomicInteger ttlSeconds = new AtomicInteger();
        KeyValueDataAccess access = new StubKeyValueDataAccess(ttlSeconds);

        assertTrue(access.getKeyOptional("missing").join().isEmpty());
        assertEquals("fallback", access.getKeyOrDefault("missing", "fallback").join());
        access.setKeyWithExpiry("key", "value", Duration.ofMillis(1)).join();
        assertEquals(1, ttlSeconds.get());
        access.setKeyWithExpiry("key", "value", Duration.ofSeconds(15)).join();
        assertEquals(15, ttlSeconds.get());

        assertThrows(IllegalArgumentException.class,
                () -> access.setKeyWithExpiry("key", "value", Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> access.setKeyWithExpiry("key", "value", Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> access.setKeyWithExpiry(
                        "key", "value", Duration.ofSeconds((long) Integer.MAX_VALUE + 1L)
                ));
    }

    @Test
    void relationalTypedAndOptionalResultsDelegateWithoutChangingExistingMethods() {
        RelationalDataAccess access = new StubRelationalDataAccess();

        assertEquals(42L, access.queryForSingleValueAs(Long.class, "SELECT 42").join());
        assertEquals(42L, access.queryForSingleValueOptionalAs(Long.class, "SELECT 42").join().orElseThrow());
        assertTrue(access.queryForSingleValueOptionalAs(Long.class, "missing").join().isEmpty());
        assertTrue(access.queryForSingleOptional("missing").join().isEmpty());
        assertEquals(7L, access.executeInsertAs(Long.class, "INSERT INTO example VALUES (?)", "value").join());
        assertEquals(7L, access.executeInsertOptionalAs(Long.class, "INSERT INTO example VALUES (?)", "value")
                .join().orElseThrow());
        assertTrue(access.executeInsertOptionalAs(Long.class, "missing").join().isEmpty());
        assertThrows(NullPointerException.class,
                () -> access.queryForSingleValueAs(null, "SELECT 42"));
        assertThrows(NullPointerException.class,
                () -> access.queryForSingleValueOptionalAs(null, "SELECT 42"));
        assertThrows(NullPointerException.class,
                () -> access.executeInsertAs(null, "INSERT INTO example VALUES (1)"));
        assertThrows(NullPointerException.class,
                () -> access.executeInsertOptionalAs(null, "INSERT INTO example VALUES (1)"));
    }

    @Test
    void documentConveniencesSupplyDefaultOptionsAndOptionalReads() {
        RecordingDocumentDataAccess access = new RecordingDocumentDataAccess();
        DocumentQuery query = new DocumentQuery().eq("id", 1);
        DocumentUpdate update = new DocumentUpdate().set("name", "example");

        assertTrue(access.findOneOptional("players", query).join().isEmpty());
        access.updateOne("players", query, update).join();
        assertFalse(access.lastOptions.get().isUpsert());
        access.updateMany("players", query, update).join();
        assertFalse(access.lastOptions.get().isUpsert());
        access.createIndex("players", Map.of("uuid", 1)).join();
        assertTrue(access.lastIndexOptions.get().isEmpty());
    }

    private static final class StubKeyValueDataAccess implements KeyValueDataAccess {
        private final AtomicInteger ttlSeconds;

        private StubKeyValueDataAccess(AtomicInteger ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        @Override public CompletableFuture<Void> setKey(String key, String value) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<String> getKey(String key) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> deleteKey(String key) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<List<Map<String, Object>>> queryByPattern(String pattern) {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletableFuture<Void> setKeyWithExpiry(String key, String value, int ttlSeconds) {
            this.ttlSeconds.set(ttlSeconds);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> pipelineSet(Map<String, String> entries) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Boolean> watchCompareAndSet(String key, String oldValue, String newValue) {
            return CompletableFuture.completedFuture(false);
        }
        @Override public CompletableFuture<Void> hset(String hashKey, Map<String, String> fields) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Map<String, String>> hgetAll(String hashKey) {
            return CompletableFuture.completedFuture(Map.of());
        }
        @Override public CompletableFuture<Void> hdel(String hashKey, String... fields) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> sadd(String key, String... members) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Set<String>> smembers(String key) {
            return CompletableFuture.completedFuture(Set.of());
        }
        @Override public CompletableFuture<Void> srem(String key, String... members) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> zadd(String key, double score, String member) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<List<String>> zrangeByScore(String key, double min, double max) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    private static final class StubRelationalDataAccess implements RelationalDataAccess {
        @Override public CompletableFuture<Void> executeUpdate(String query, Object... params) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Map<String, Object>> queryForSingle(String query, Object... params) {
            return CompletableFuture.completedFuture("missing".equals(query) ? null : Map.of("value", 42L));
        }
        @Override public CompletableFuture<List<Map<String, Object>>> queryForList(String query, Object... params) {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletableFuture<Object> queryForSingleValue(String query, Object... params) {
            return CompletableFuture.completedFuture("missing".equals(query) ? null : 42L);
        }
        @Override public CompletableFuture<Void> executeBatchUpdate(String query, List<Object[]> batchParams) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public <T> CompletableFuture<T> executeTransactionally(TransactionCallback<T> callback) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("Not used."));
        }
        @Override public CompletableFuture<Object> executeInsert(String query, Object... params) {
            return CompletableFuture.completedFuture("missing".equals(query) ? null : 7L);
        }
    }

    private static final class RecordingDocumentDataAccess implements DocumentDataAccess {
        private final AtomicReference<DocumentUpdateOptions> lastOptions = new AtomicReference<>();
        private final AtomicReference<Map<String, Object>> lastIndexOptions = new AtomicReference<>();

        @Override public CompletableFuture<Void> insertOne(String collection, Map<String, Object> document) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Map<String, Object>> findOne(String collection, DocumentQuery query) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<List<Map<String, Object>>> findMany(String collection, DocumentQuery query) {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletableFuture<Void> updateOne(
                String collection, DocumentQuery query, DocumentUpdate update, DocumentUpdateOptions options
        ) {
            lastOptions.set(options);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> updateMany(
                String collection, DocumentQuery query, DocumentUpdate update, DocumentUpdateOptions options
        ) {
            lastOptions.set(options);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> deleteOne(String collection, DocumentQuery query) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> deleteMany(String collection, DocumentQuery query) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> createIndex(
                String collection, Map<String, Object> indexSpec, Map<String, Object> indexOptions
        ) {
            lastIndexOptions.set(indexOptions);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> dropIndex(String collection, String indexName) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
