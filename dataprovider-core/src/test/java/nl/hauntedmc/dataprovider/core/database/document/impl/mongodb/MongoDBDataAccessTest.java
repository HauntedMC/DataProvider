package nl.hauntedmc.dataprovider.core.database.document.impl.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import nl.hauntedmc.dataprovider.core.testutil.DirectExecutorService;
import nl.hauntedmc.dataprovider.database.document.model.DocumentQuery;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdate;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdateOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoDBDataAccessTest {

    @Test
    void insertOnePreservesNullAndNestedValuesWithoutRetainingCallerCollections() {
        MongoCollection<Document> collection = mockCollection();
        MongoDBDataAccess dataAccess = createDataAccess(collection);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("status", "active");
        List<Object> tags = new ArrayList<>(List.of("first", nested));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("nullable", null);
        document.put("profile", nested);
        document.put("tags", tags);

        dataAccess.insertOne("profiles", document).join();

        nested.put("status", "changed");
        tags.add("later");
        document.put("nullable", "changed");

        ArgumentCaptor<Document> captured = ArgumentCaptor.forClass(Document.class);
        verify(collection).insertOne(captured.capture());
        Document stored = captured.getValue();
        assertNull(stored.get("nullable"));
        assertEquals("active", stored.getEmbedded(List.of("profile", "status"), String.class));
        assertEquals(List.of("first", new Document("status", "active")), stored.getList("tags", Object.class));
    }

    @Test
    void insertOneRejectsUnsupportedValuesAtTheirNestedPath() {
        MongoDBDataAccess dataAccess = createDataAccess(mockCollection());
        Map<String, Object> document = Map.of("profile", List.of(new Object()));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dataAccess.insertOne("profiles", document)
        );

        assertTrue(exception.getMessage().contains("document.profile[0]"));
    }

    @Test
    void updateSnapshotsMutableInputsBeforeScheduling() {
        MongoCollection<Document> collection = mockCollection();
        List<Runnable> queued = new ArrayList<>();
        MongoDBDataAccess dataAccess = createDataAccess(collection, queued::add);
        DocumentQuery query = new DocumentQuery().eq("status", "pending");
        DocumentUpdate update = new DocumentUpdate().set("attempt", 1);
        DocumentUpdateOptions options = new DocumentUpdateOptions().setUpsert(false);

        var completion = dataAccess.updateOne("profiles", query, update, options);
        query.eq("status", "changed");
        update.set("attempt", 2);
        options.setUpsert(true);
        queued.getFirst().run();
        completion.join();

        ArgumentCaptor<Bson> queryCapture = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> updateCapture = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<UpdateOptions> optionsCapture = ArgumentCaptor.forClass(UpdateOptions.class);
        verify(collection).updateOne(queryCapture.capture(), updateCapture.capture(), optionsCapture.capture());
        assertEquals("pending", ((Document) queryCapture.getValue()).getString("status"));
        assertEquals(1, ((Document) updateCapture.getValue()).get("$set", Document.class).getInteger("attempt"));
        assertEquals(false, optionsCapture.getValue().isUpsert());
    }

    @SuppressWarnings("unchecked")
    private static MongoCollection<Document> mockCollection() {
        return mock(MongoCollection.class);
    }

    private static MongoDBDataAccess createDataAccess(MongoCollection<Document> collection) {
        return createDataAccess(collection, new DirectExecutorService());
    }

    private static MongoDBDataAccess createDataAccess(
            MongoCollection<Document> collection,
            Executor executor
    ) {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        when(client.getDatabase("test")).thenReturn(database);
        when(database.getCollection("profiles")).thenReturn(collection);
        return new MongoDBDataAccess(client, "test", executor);
    }
}
