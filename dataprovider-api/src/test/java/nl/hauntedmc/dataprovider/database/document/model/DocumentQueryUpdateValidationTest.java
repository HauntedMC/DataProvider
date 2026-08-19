package nl.hauntedmc.dataprovider.database.document.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentQueryUpdateValidationTest {

    @Test
    void eqRejectsBlankFieldName() {
        DocumentQuery query = new DocumentQuery();
        assertThrows(IllegalArgumentException.class, () -> query.eq(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> query.eq(null, 1));
        assertThrows(IllegalArgumentException.class, () -> query.eq("$where", "unsafe"));
    }

    @Test
    void gteRejectsNullValue() {
        DocumentQuery query = new DocumentQuery();
        assertThrows(NullPointerException.class, () -> query.gte("score", null));
    }

    @Test
    void setRejectsBlankFieldName() {
        DocumentUpdate update = new DocumentUpdate();
        assertThrows(IllegalArgumentException.class, () -> update.set(" ", "value"));
        assertThrows(IllegalArgumentException.class, () -> update.set(null, "value"));
    }

    @Test
    void incRejectsNullAmount() {
        DocumentUpdate update = new DocumentUpdate();
        assertThrows(NullPointerException.class, () -> update.inc("score", null));
    }

    @Test
    void comparisonConveniencesProduceExpectedOperators() {
        assertEquals(Map.of("$ne", "banned"), new DocumentQuery().ne("status", "banned").toMap().get("status"));
        assertEquals(Map.of("$gt", 10), new DocumentQuery().gt("score", 10).toMap().get("score"));
        assertEquals(Map.of("$lt", 20), new DocumentQuery().lt("score", 20).toMap().get("score"));
        assertEquals(Map.of("$lte", 30), new DocumentQuery().lte("score", 30).toMap().get("score"));
        assertThrows(NullPointerException.class, () -> new DocumentQuery().gt("score", null));
        assertThrows(NullPointerException.class, () -> new DocumentQuery().lt("score", null));
        assertThrows(NullPointerException.class, () -> new DocumentQuery().lte("score", null));
    }

    @Test
    void bulkSetAndUpsertFactoryReduceBuilderBoilerplate() {
        DocumentUpdate update = new DocumentUpdate().setAll(Map.of("name", "Remy", "level", 5));

        assertTrue(update.hasOperations());
        @SuppressWarnings("unchecked")
        Map<String, Object> setMap = (Map<String, Object>) update.toMap().get("$set");
        assertEquals("Remy", setMap.get("name"));
        assertEquals(5, setMap.get("level"));
        assertTrue(DocumentUpdateOptions.upsert().isUpsert());
        assertThrows(NullPointerException.class, () -> new DocumentUpdate().setAll(null));
    }

    @Test
    void validQueryAndUpdateBuildersProduceExpectedMaps() {
        DocumentQuery query = new DocumentQuery()
                .eq("uuid", "abc")
                .gte("score", 10)
                .eq("active", true);

        DocumentUpdate update = new DocumentUpdate()
                .set("name", "Remy")
                .inc("score", 2);

        Map<String, Object> queryMap = query.toMap();
        Map<String, Object> updateMap = update.toMap();

        @SuppressWarnings("unchecked")
        Map<String, Object> uuidExpression = (Map<String, Object>) queryMap.get("uuid");
        @SuppressWarnings("unchecked")
        Map<String, Object> activeExpression = (Map<String, Object>) queryMap.get("active");
        assertEquals("abc", uuidExpression.get("$eq"));
        assertEquals(true, activeExpression.get("$eq"));
        assertTrue(query.hasCriteria());
        assertTrue(queryMap.containsKey("score"));
        assertTrue(queryMap.containsKey("active"));
        assertTrue(update.hasOperations());
        assertTrue(updateMap.containsKey("$set"));
        assertTrue(updateMap.containsKey("$inc"));

        @SuppressWarnings("unchecked")
        Map<String, Object> setMap = (Map<String, Object>) updateMap.get("$set");
        @SuppressWarnings("unchecked")
        Map<String, Object> incMap = (Map<String, Object>) updateMap.get("$inc");
        assertEquals("Remy", setMap.get("name"));
        assertEquals(2, incMap.get("score"));

        assertThrows(UnsupportedOperationException.class, () -> queryMap.put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> updateMap.put("x", "y"));
    }

    @Test
    void nestedValuesAndReturnedMapsAreDefensiveSnapshots() {
        List<String> tags = new ArrayList<>(List.of("first"));
        Map<String, Object> nested = new java.util.LinkedHashMap<>();
        nested.put("tags", tags);
        DocumentQuery query = new DocumentQuery().eq("profile", nested);
        tags.add("second");
        nested.put("role", "admin");

        Map<String, Object> snapshot = query.toMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> profileExpression = (Map<String, Object>) snapshot.get("profile");
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) profileExpression.get("$eq");
        assertEquals(List.of("first"), profile.get("tags"));
        assertFalse(profile.containsKey("role"));
        assertThrows(UnsupportedOperationException.class, () -> profile.put("role", "admin"));
        @SuppressWarnings("unchecked")
        List<String> snapshotTags = (List<String>) profile.get("tags");
        assertThrows(UnsupportedOperationException.class, () -> snapshotTags.add("third"));
    }

    @Test
    void matchAllMustBeExplicitAndIsClearedByCriteria() {
        DocumentQuery empty = new DocumentQuery();
        DocumentQuery all = DocumentQuery.all();

        assertFalse(empty.hasCriteria());
        assertFalse(empty.isExplicitMatchAll());
        assertTrue(all.isExplicitMatchAll());
        all.eq("status", "active");
        assertFalse(all.isExplicitMatchAll());
        assertTrue(all.hasCriteria());
    }
}
