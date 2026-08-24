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

    /** Returns the reusable no-op observer used by the uninstrumented fast path. */
    static DataProviderObserver noop() {
        return NoopDataProviderObserver.INSTANCE;
    }
}

final class NoopDataProviderObserver implements DataProviderObserver {

    static final NoopDataProviderObserver INSTANCE = new NoopDataProviderObserver();

    private NoopDataProviderObserver() {
    }

    @Override
    public DataProviderObservation start(DataProviderOperationContext context) {
        return DataProviderObservation.noop();
    }
}
