package nl.hauntedmc.dataprovider.database.document.model;

/**
 * DocumentUpdateOptions is a vendor-agnostic set of options for updates.
 * For example, "upsert".
 */
public class DocumentUpdateOptions {

    private boolean upsert = false;

    public boolean isUpsert() {
        return upsert;
    }

    public DocumentUpdateOptions upsert(boolean upsert) {
        this.upsert = upsert;
        return this;
    }

    // Add more flags if needed (bypassValidation, etc.)
}
