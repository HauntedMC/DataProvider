package nl.hauntedmc.dataprovider.platform.velocity.logger;

import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import org.slf4j.Logger;

public class SLF4JLoggerAdapter implements ILoggerAdapter {
    private final Logger logger;

    public SLF4JLoggerAdapter(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message) {
        logger.warn(message);
    }

    @Override
    public void error(String message) {
        logger.error(message);
    }

    @Override
    public void info(String message, Throwable throwable) {
        logger.info(message, throwable);
    }

    @Override
    public void warn(String message, Throwable throwable) {
        logger.warn(message, throwable);
    }

    @Override
    public void error(String message, Throwable throwable) {
        logger.error(message, throwable);
    }
}
