package nl.hauntedmc.dataprovider.database.document.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentUpdateFieldValidationTest {

    @Test
    void setAndIncrementNormalizeFieldNamesConsistentlyWithQueries() {
        DocumentUpdate update = new DocumentUpdate()
                .set("  display_name  ", "Haunty")
                .inc("  score  ", 2);

        Map<String, Object> operations = update.toMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> set = (Map<String, Object>) operations.get("$set");
        @SuppressWarnings("unchecked")
        Map<String, Object> increment = (Map<String, Object>) operations.get("$inc");

        assertEquals("Haunty", set.get("display_name"));
        assertEquals(2, increment.get("score"));
        assertFalse(set.containsKey("  display_name  "));
        assertFalse(increment.containsKey("  score  "));
    }

    @Test
    void setRejectsOperatorAndNullCharacterFieldNames() {
        DocumentUpdate update = new DocumentUpdate();

        assertThrows(IllegalArgumentException.class, () -> update.set("$where", "unsafe"));
        assertThrows(IllegalArgumentException.class, () -> update.set("profile\0name", "unsafe"));
    }

    @Test
    void incrementRejectsOperatorAndNullCharacterFieldNames() {
        DocumentUpdate update = new DocumentUpdate();

        assertThrows(IllegalArgumentException.class, () -> update.inc("$inc", 1));
        assertThrows(IllegalArgumentException.class, () -> update.inc("score\0hidden", 1));
    }
}
