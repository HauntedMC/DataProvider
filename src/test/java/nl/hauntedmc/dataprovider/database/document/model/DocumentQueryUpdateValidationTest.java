package nl.hauntedmc.dataprovider.database.document.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentQueryUpdateValidationTest {

    @Test
    void eqRejectsBlankFieldName() {
        DocumentQuery query = new DocumentQuery();
        assertThrows(IllegalArgumentException.class, () -> query.eq(" ", 1));
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
        assertTrue(queryMap.containsKey("score"));
        assertTrue(queryMap.containsKey("active"));
        assertTrue(updateMap.containsKey("$set"));
        assertTrue(updateMap.containsKey("$inc"));
    }
}
