package nl.hauntedmc.dataprovider.database.document.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentUpdateOptionsTest {

    @Test
    void defaultsToNonUpsertAndSupportsFluentMutation() {
        DocumentUpdateOptions options = new DocumentUpdateOptions();
        assertFalse(options.isUpsert());

        DocumentUpdateOptions returned = options.setUpsert(true);
        assertSame(options, returned);
        assertTrue(options.isUpsert());

        options.setUpsert(false);
        assertFalse(options.isUpsert());
    }
}
