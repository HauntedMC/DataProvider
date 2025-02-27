package nl.hauntedmc.dataprovider.platform.common.logger;

/**
 * A common logging interface for both Bukkit and Velocity implementations.
 */
public interface ILoggerAdapter {
    void info(String message);
    void warn(String message);
    void error(String message);
    void info(String message, Throwable throwable);
    void warn(String message, Throwable throwable);
    void error(String message, Throwable throwable);
}
