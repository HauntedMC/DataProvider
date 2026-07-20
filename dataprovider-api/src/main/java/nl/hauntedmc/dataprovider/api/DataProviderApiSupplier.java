package nl.hauntedmc.dataprovider.api;

/**
 * Runtime contract for obtaining a live DataProvider API instance from a host plugin.
 */
public interface DataProviderApiSupplier {

    DataProviderAPI dataProviderApi();
}
