package nl.hauntedmc.dataprovider.platform.bukkit.logger;

import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BukkitLoggerAdapter implements ILoggerAdapter {

    private final Logger logger;

    public BukkitLoggerAdapter(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    @Override
    public void info(String message) {
        logger.log(Level.INFO, message);
    }

    @Override
    public void warn(String message) {
        logger.log(Level.WARNING, message);
    }

    @Override
    public void error(String message) {
        logger.log(Level.SEVERE, message);
    }

    @Override
    public void info(String message, Throwable throwable) {
        logger.log(Level.INFO, message, throwable);
    }

    @Override
    public void warn(String message, Throwable throwable) {
        logger.log(Level.WARNING, message, throwable);
    }

    @Override
    public void error(String message, Throwable throwable) {
        logger.log(Level.SEVERE, message, throwable);
    }
}
