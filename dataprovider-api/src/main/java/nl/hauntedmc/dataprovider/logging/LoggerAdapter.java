package nl.hauntedmc.dataprovider.logging;

/**
 * Backend-agnostic logging contract for DataProvider internals.
 */
public interface LoggerAdapter {

    void log(LogLevel level, String message, Throwable throwable);

    void log(LogLevel level, String message);

    /**
     * Emits optional diagnostic detail that should not appear in normal production startup output.
     *
     * <p>This is a default no-op so existing third-party adapters remain binary compatible and do not suddenly
     * promote new debug-only messages to INFO.</p>
     */
    default void debug(String message) { }

    default void info(String message) {
        log(LogLevel.INFO, message);
    }

    default void warn(String message) {
        log(LogLevel.WARN, message);
    }

    default void error(String message) {
        log(LogLevel.ERROR, message);
    }

    default void info(String message, Throwable throwable) {
        log(LogLevel.INFO, message, throwable);
    }

    default void warn(String message, Throwable throwable) {
        log(LogLevel.WARN, message, throwable);
    }

    default void error(String message, Throwable throwable) {
        log(LogLevel.ERROR, message, throwable);
    }
}
