package nl.hauntedmc.dataprovider.logger;

import nl.hauntedmc.dataprovider.DataProvider;

import java.io.File;
import java.io.IOException;
import java.util.logging.*;

public class DPLogger {

    // Use Bukkit's built-in logger for console output.
    private static final Logger logger = DataProvider.getInstance().getLogger();

    /**
     * Initializes file logging by adding a custom DPFileHandler.
     * Console logging remains handled by Bukkit's default logger.
     */
    public static void initialize() {
        // Ensure the logs folder exists.
        File pluginFolder = DataProvider.getInstance().getDataFolder();
        File logsFolder = new File(pluginFolder, "logs");
        if (!logsFolder.exists() && !logsFolder.mkdirs()) {
            logger.warning("Could not create logs folder at: " + logsFolder.getAbsolutePath());
        }

        // Read file logging settings from config.yml.
        int fileLimit = DataProvider.getInstance().getConfig().getInt("logging.fileLimit", 10 * 1024 * 1024); // default 10 MB
        int fileCount = DataProvider.getInstance().getConfig().getInt("logging.fileCount", 30);
        String fileLevelStr = DataProvider.getInstance().getConfig().getString("logging.fileLevel", "WARNING");
        Level fileLevel = Level.parse(fileLevelStr);

        // The active log file will be named "DataProvider.log" (with no extension).
        String baseFileName = "DataProvider.log";
        try {
            DPFileHandler fileHandler = new DPFileHandler(logsFolder, baseFileName, fileLimit, fileCount, true);
            fileHandler.setLevel(fileLevel);
            fileHandler.setFormatter(new DPLogFormatter());
            // Add the file handler to the plugin's logger.
            logger.addHandler(fileHandler);
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Failed to initialize DPFileHandler", ex);
        }

        logger.info("DPLogger initialized. Log files will be stored in: " + logsFolder.getAbsolutePath());
        logger.info("Active log file: " + new File(logsFolder, baseFileName).getAbsolutePath());
    }

    // Convenience logging methods

    public static void info(String message) {
        logger.info(message);
    }

    public static void info(String message, Object... params) {
        logger.log(Level.INFO, message, params);
    }

    public static void warning(String message) {
        logger.warning(message);
    }

    public static void warning(String message, Object... params) {
        logger.log(Level.WARNING, message, params);
    }

    public static void error(String message) {
        logger.severe(message);
    }

    public static void error(String message, Throwable throwable) {
        logger.log(Level.SEVERE, message, throwable);
    }

    public static void debug(String message) {
        logger.fine(message);
    }

    public static void debug(String message, Object... params) {
        logger.log(Level.FINE, message, params);
    }

}
