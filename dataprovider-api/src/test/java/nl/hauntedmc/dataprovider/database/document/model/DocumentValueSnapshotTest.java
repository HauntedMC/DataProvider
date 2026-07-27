package nl.hauntedmc.dataprovider.database.document.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentValueSnapshotTest {

    @Test
    void snapshotsNestedMapsListsArraysAndDatesAtInsertionTime() {
        Date date = new Date(1234L);
        int[] scores = {1, 2, 3};
        List<Object> tags = new ArrayList<>(List.of("first"));
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("date", date);
        profile.put("scores", scores);
        profile.put("tags", tags);

        DocumentQuery query = new DocumentQuery().eq("profile", profile);
        date.setTime(9999L);
        scores[0] = 99;
        tags.add("second");
        profile.put("role", "admin");

        Map<String, Object> snapshot = query.toMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> expression = (Map<String, Object>) snapshot.get("profile");
        @SuppressWarnings("unchecked")
        Map<String, Object> copiedProfile = (Map<String, Object>) expression.get("$eq");

        assertEquals(new Date(1234L), copiedProfile.get("date"));
        assertArrayEquals(new int[]{1, 2, 3}, (int[]) copiedProfile.get("scores"));
        assertEquals(List.of("first"), copiedProfile.get("tags"));
        assertThrows(UnsupportedOperationException.class, () -> copiedProfile.put("role", "admin"));
    }

    @Test
    void everyReturnedSnapshotIsIndependentFromEarlierReturnedArraysAndDates() {
        DocumentUpdate update = new DocumentUpdate()
                .set("bytes", new byte[]{1, 2})
                .set("date", new Date(10L));

        Map<String, Object> first = update.toMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> firstSet = (Map<String, Object>) first.get("$set");
        ((byte[]) firstSet.get("bytes"))[0] = 9;
        ((Date) firstSet.get("date")).setTime(99L);

        Map<String, Object> second = update.toMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> secondSet = (Map<String, Object>) second.get("$set");
        assertArrayEquals(new byte[]{1, 2}, (byte[]) secondSet.get("bytes"));
        assertEquals(new Date(10L), secondSet.get("date"));
    }

    @Test
    void repeatedReferencesAreAllowedButSnapshottedIndependently() {
        List<String> shared = new ArrayList<>(List.of("value"));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("left", shared);
        value.put("right", shared);

        DocumentQuery query = new DocumentQuery().eq("value", value);
        @SuppressWarnings("unchecked")
        Map<String, Object> expression = (Map<String, Object>) query.toMap().get("value");
        @SuppressWarnings("unchecked")
        Map<String, Object> copied = (Map<String, Object>) expression.get("$eq");

        assertEquals(List.of("value"), copied.get("left"));
        assertEquals(List.of("value"), copied.get("right"));
        assertNotSame(copied.get("left"), copied.get("right"));
    }

    @Test
    void rejectsCyclesAcrossMapsListsAndArrays() {
        Map<String, Object> mapCycle = new HashMap<>();
        mapCycle.put("self", mapCycle);
        List<Object> listCycle = new ArrayList<>();
        listCycle.add(listCycle);
        Object[] arrayCycle = new Object[1];
        arrayCycle[0] = arrayCycle;

        assertThrows(IllegalArgumentException.class, () -> new DocumentQuery().eq("value", mapCycle));
        assertThrows(IllegalArgumentException.class, () -> new DocumentQuery().eq("value", listCycle));
        assertThrows(IllegalArgumentException.class, () -> new DocumentQuery().eq("value", arrayCycle));
    }

    @Test
    void rejectsNonStringMapKeysAtAnyDepth() {
        Map<Object, Object> invalid = new LinkedHashMap<>();
        invalid.put(1, "value");

        assertThrows(IllegalArgumentException.class, () -> new DocumentUpdate().set("value", invalid));
    }

    @Test
    void enforcesTheNestedDepthLimitAtItsExactBoundary() {
        Object accepted = "leaf";
        for (int level = 0; level < 65; level++) {
            accepted = List.of(accepted);
        }
        new DocumentQuery().eq("value", accepted);

        Object rejected = "leaf";
        for (int level = 0; level < 66; level++) {
            rejected = List.of(rejected);
        }
        Object tooDeep = rejected;
        assertThrows(IllegalArgumentException.class, () -> new DocumentQuery().eq("value", tooDeep));
    }

    @Test
    void enforcesTheMutableNodeLimitAtItsExactBoundary() {
        List<Object> accepted = emptyMaps(9_999);
        new DocumentQuery().eq("value", accepted);

        List<Object> rejected = emptyMaps(10_000);
        assertThrows(IllegalArgumentException.class, () -> new DocumentQuery().eq("value", rejected));
    }

    private static List<Object> emptyMaps(int count) {
        List<Object> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(new HashMap<>());
        }
        return values;
    }
}
