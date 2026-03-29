package nl.hauntedmc.dataprovider.logging.adapters;

import nl.hauntedmc.dataprovider.logging.LogLevel;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class JulLoggerAdapter implements LoggerAdapter {

    private final Logger logger;

    public JulLoggerAdapter(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    @Override
    public void log(LogLevel level, String message, Throwable throwable) {
        Objects.requireNonNull(level, "Log level cannot be null.");
        Level julLevel = switch (level) {
            case INFO -> Level.INFO;
            case WARN -> Level.WARNING;
            case ERROR -> Level.SEVERE;
        };

        if (throwable == null) {
            logger.log(julLevel, message);
        } else {
            logger.log(julLevel, message, throwable);
        }
    }
}
