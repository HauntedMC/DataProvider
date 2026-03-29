package nl.hauntedmc.dataprovider.logging.adapters;

import nl.hauntedmc.dataprovider.logging.LogLevel;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.slf4j.Logger;

import java.util.Objects;

public final class Slf4jLoggerAdapter implements LoggerAdapter {

    private final Logger logger;

    public Slf4jLoggerAdapter(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    @Override
    public void log(LogLevel level, String message, Throwable throwable) {
        Objects.requireNonNull(level, "Log level cannot be null.");
        switch (level) {
            case INFO -> logInfo(message, throwable);
            case WARN -> logWarn(message, throwable);
            case ERROR -> logError(message, throwable);
        }
    }

    private void logInfo(String message, Throwable throwable) {
        if (throwable == null) {
            logger.info(message);
        } else {
            logger.info(message, throwable);
        }
    }

    private void logWarn(String message, Throwable throwable) {
        if (throwable == null) {
            logger.warn(message);
        } else {
            logger.warn(message, throwable);
        }
    }

    private void logError(String message, Throwable throwable) {
        if (throwable == null) {
            logger.error(message);
        } else {
            logger.error(message, throwable);
        }
    }
}
