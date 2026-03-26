package nl.hauntedmc.dataprovider.platform.common.lifecycle;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Thread-safe runtime holder for the active platform DataProvider instance.
 */
public final class PlatformDataProviderRuntime {

    private static final String NOT_INITIALIZED_MESSAGE = "DataProvider is not initialized yet.";
    private static final String LEFTOVER_INSTANCE_MESSAGE =
            "Detected leftover DataProvider instance during enable; forcing cleanup first.";
    private static final String LEFTOVER_SHUTDOWN_FAILURE_MESSAGE =
            "Failed to shut down leftover DataProvider instance cleanly.";
    private static final String SHUTDOWN_FAILURE_MESSAGE =
            "Failed to shut down DataProvider cleanly.";

    private final AtomicReference<DataProvider> activeProvider = new AtomicReference<>();

    /**
     * Starts a fresh DataProvider instance.
     * If an old runtime instance is still present, it is shut down first.
     */
    public synchronized DataProvider start(Supplier<DataProvider> providerFactory, ILoggerAdapter logger) {
        Objects.requireNonNull(providerFactory, "Provider factory cannot be null.");
        Objects.requireNonNull(logger, "Logger cannot be null.");

        DataProvider previousProvider = activeProvider.getAndSet(null);
        if (previousProvider != null) {
            logger.warn(LEFTOVER_INSTANCE_MESSAGE);
            shutdownProvider(previousProvider, logger, LEFTOVER_SHUTDOWN_FAILURE_MESSAGE);
        }

        DataProvider createdProvider = Objects.requireNonNull(
                providerFactory.get(),
                "Provider factory cannot return null."
        );
        activeProvider.set(createdProvider);
        return createdProvider;
    }

    /**
     * Stops the current DataProvider instance, if one is active.
     */
    public synchronized void stop(ILoggerAdapter logger) {
        Objects.requireNonNull(logger, "Logger cannot be null.");
        DataProvider providerToShutdown = activeProvider.getAndSet(null);
        if (providerToShutdown != null) {
            shutdownProvider(providerToShutdown, logger, SHUTDOWN_FAILURE_MESSAGE);
        }
    }

    /**
     * Resolves a new API facade for the currently active provider.
     */
    public DataProviderAPI getDataProviderAPI() {
        DataProvider provider = activeProvider.get();
        if (provider == null) {
            throw new IllegalStateException(NOT_INITIALIZED_MESSAGE);
        }
        return new DataProviderAPI(provider.getDataProviderHandler());
    }

    private static void shutdownProvider(DataProvider provider, ILoggerAdapter logger, String failureMessage) {
        try {
            provider.shutdownAllDatabases();
        } catch (Exception e) {
            logger.error(failureMessage, e);
        }
    }
}
