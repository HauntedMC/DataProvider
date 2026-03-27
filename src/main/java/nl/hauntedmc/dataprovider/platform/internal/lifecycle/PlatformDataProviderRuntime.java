package nl.hauntedmc.dataprovider.platform.internal.lifecycle;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;

import java.util.Objects;
import java.util.function.Consumer;
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
    private static final String STARTUP_INITIALIZATION_FAILURE_MESSAGE =
            "Failed to complete DataProvider startup initialization.";
    private static final String STARTUP_FAILURE_SHUTDOWN_MESSAGE =
            "Failed to shut down DataProvider instance after startup initialization failure.";
    private static final String SHUTDOWN_FAILURE_MESSAGE =
            "Failed to shut down DataProvider cleanly.";

    private DataProvider activeProvider;

    /**
     * Starts a fresh DataProvider instance.
     * If an old runtime instance is still present, it is shut down first.
     */
    public synchronized DataProvider start(
            Supplier<DataProvider> providerFactory,
            Consumer<DataProvider> startupInitializer,
            LoggerAdapter logger
    ) {
        Objects.requireNonNull(providerFactory, "Provider factory cannot be null.");
        Objects.requireNonNull(startupInitializer, "Startup initializer cannot be null.");
        Objects.requireNonNull(logger, "Logger cannot be null.");

        DataProvider previousProvider = activeProvider;
        activeProvider = null;
        if (previousProvider != null) {
            logger.warn(LEFTOVER_INSTANCE_MESSAGE);
            shutdownProvider(previousProvider, logger, LEFTOVER_SHUTDOWN_FAILURE_MESSAGE);
        }

        DataProvider createdProvider = Objects.requireNonNull(
                providerFactory.get(),
                "Provider factory cannot return null."
        );

        try {
            startupInitializer.accept(createdProvider);
        } catch (RuntimeException | Error exception) {
            logger.error(STARTUP_INITIALIZATION_FAILURE_MESSAGE, exception);
            shutdownProvider(createdProvider, logger, STARTUP_FAILURE_SHUTDOWN_MESSAGE);
            throw exception;
        }

        activeProvider = createdProvider;
        return createdProvider;
    }

    /**
     * Stops the current DataProvider instance, if one is active.
     */
    public synchronized void stop(LoggerAdapter logger) {
        Objects.requireNonNull(logger, "Logger cannot be null.");
        DataProvider providerToShutdown = activeProvider;
        activeProvider = null;
        if (providerToShutdown != null) {
            shutdownProvider(providerToShutdown, logger, SHUTDOWN_FAILURE_MESSAGE);
        }
    }

    /**
     * Resolves a new API facade for the currently active provider.
     */
    public synchronized DataProviderAPI getDataProviderAPI() {
        DataProvider provider = activeProvider;
        if (provider == null) {
            throw new IllegalStateException(NOT_INITIALIZED_MESSAGE);
        }
        return new DataProviderAPI(provider.getDataProviderHandler());
    }

    private static void shutdownProvider(DataProvider provider, LoggerAdapter logger, String failureMessage) {
        try {
            provider.shutdownAllDatabases();
        } catch (Exception e) {
            logger.error(failureMessage, e);
        }
    }
}
