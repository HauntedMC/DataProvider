package nl.hauntedmc.dataprovider.api.observation;

/**
 * Vendor-neutral hook for observing plugin-scoped DataProvider operations.
 *
 * <p>The runtime invokes observers synchronously when an operation starts. The returned observation
 * receives its terminal callback when that operation completes; asynchronous operations may finish
 * on a backend worker thread. Implementations should therefore be thread-safe and non-blocking.</p>
 *
 * <p>Observer failures are isolated by the DataProvider runtime and never change the outcome of the
 * underlying data operation.</p>
 */
@FunctionalInterface
public interface DataProviderObserver {

    /** Starts one operation observation. Implementations should return a non-null handle. */
    DataProviderObservation start(DataProviderOperationContext context);

    /** Returns a reusable observer that performs no work. */
    static DataProviderObserver noop() {
        return context -> DataProviderObservation.noop();
    }
}
