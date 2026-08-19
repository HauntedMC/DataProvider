package nl.hauntedmc.dataprovider.api;

/**
 * Runtime contract for obtaining a live DataProvider API instance from a host plugin.
 */
public interface DataProviderApiSupplier {

    DataProviderAPI dataProviderApi();

    /** Returns a DataProvider facade bound to the supplied platform plugin instance. */
    default DataProviderAPI dataProviderApiFor(Object platformPlugin) {
        return dataProviderApi().forPlugin(platformPlugin);
    }
}
