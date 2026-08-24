package nl.hauntedmc.dataprovider.api.observation;

/**
 * One in-flight DataProvider operation observed through {@link DataProviderObserver}.
 *
 * <p>Exactly one terminal callback is issued by the DataProvider runtime for an observation it
 * successfully starts. Implementations must be thread-safe because asynchronous operations may
 * complete on a backend worker thread rather than the thread that started the observation.</p>
 */
public interface DataProviderObservation {

    /** Called when the observed operation completes successfully. */
    void succeeded();

    /** Called when the observed operation completes exceptionally or throws. */
    void failed(Throwable failure);

    /** Returns a reusable observation that ignores all terminal callbacks. */
    static DataProviderObservation noop() {
        return NoopDataProviderObservation.INSTANCE;
    }
}

final class NoopDataProviderObservation implements DataProviderObservation {

    static final NoopDataProviderObservation INSTANCE = new NoopDataProviderObservation();

    private NoopDataProviderObservation() {
    }

    @Override
    public void succeeded() {
        // Intentionally empty.
    }

    @Override
    public void failed(Throwable failure) {
        // Intentionally empty.
    }
}
