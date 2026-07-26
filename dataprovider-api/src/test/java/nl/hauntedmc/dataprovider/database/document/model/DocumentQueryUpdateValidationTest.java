package nl.hauntedmc.dataprovider.database.document.model;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;

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
    }

    @Test
    void gteRejectsNullValue() {
        DocumentQuery query = new DocumentQuery();
        assertThrows(NullPointerException.class, () -> query.gte("score", null));
    }

    @Test
    void rawRejectsNullExpression() {
        DocumentQuery query = new DocumentQuery();
        assertThrows(NullPointerException.class, () -> query.raw("meta", null));
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
    void validQueryAndUpdateBuildersProduceExpectedMaps() {
        DocumentQuery query = new DocumentQuery()
                .eq("uuid", "abc")
                .gte("score", 10)
                .raw("active", true);

        DocumentUpdate update = new DocumentUpdate()
                .set("name", "Remy")
                .inc("score", 2);

        Map<String, Object> queryMap = query.toMap();
        Map<String, Object> updateMap = update.toMap();

        assertEquals("abc", queryMap.get("uuid"));
        assertEquals(true, queryMap.get("active"));
        assertTrue(queryMap.containsKey("score"));
        assertTrue(queryMap.containsKey("active"));
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
        Map<String, Object> profile = (Map<String, Object>) snapshot.get("profile");
        assertEquals(List.of("first"), profile.get("tags"));
        assertFalse(profile.containsKey("role"));
        assertThrows(UnsupportedOperationException.class, () -> profile.put("role", "admin"));
        @SuppressWarnings("unchecked")
        List<String> snapshotTags = (List<String>) profile.get("tags");
        assertThrows(UnsupportedOperationException.class, () -> snapshotTags.add("third"));
    }
}
