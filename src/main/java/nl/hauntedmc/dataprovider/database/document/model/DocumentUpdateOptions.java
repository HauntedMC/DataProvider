package nl.hauntedmc.dataprovider.database.document.model;

/**
 * DocumentUpdateOptions is a vendor–agnostic set of options for updates.
 * For example, "upsert".
 */
public class DocumentUpdateOptions {

    private boolean upsert;

    public boolean isUpsert() {
        return upsert;
    }

    /**
     * Sets the upsert flag.
     *
     * @param upsert true if the update should upsert
     * @return this DocumentUpdateOptions instance for chaining
     */
    public DocumentUpdateOptions setUpsert(boolean upsert) {
        this.upsert = upsert;
        return this;
    }
}
