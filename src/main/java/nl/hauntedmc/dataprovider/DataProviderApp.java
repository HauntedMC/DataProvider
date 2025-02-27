package nl.hauntedmc.dataprovider;

import nl.hauntedmc.dataprovider.config.ConfigHandler;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;

public class DataProviderApp {

    private final DataProviderHandler dataProviderHandler;

    private static ConfigHandler configHandler;
    private static ILoggerAdapter logInstance;
    private static Path dataPath;
    private static ClassLoader parentClassLoader;

    public DataProviderApp(ILoggerAdapter logger, Path dataDirectory, ClassLoader classLoader) {
        parentClassLoader = classLoader;;
        logInstance = logger;
        dataPath = dataDirectory;

        // Init Main Config
        configHandler = new ConfigHandler();

        // Init Data Provider Handler
        dataProviderHandler = new DataProviderHandler();
    }

    public static ILoggerAdapter getLogger() {
        return logInstance;
    }

    public DataProviderHandler getDataProviderHandler() {
        return dataProviderHandler;
    }

    public static Path getDataPath() {
        return dataPath;
    }

    public static ConfigHandler getConfigHandler() {
        return configHandler;
    }

    public void shutdownAllDatabases() {
        dataProviderHandler.shutdownAllDatabases();
    }

    public static @Nullable InputStream getResource(String filename) {
        if (filename == null) {
            throw new IllegalArgumentException("Filename cannot be null");
        } else {
            try {
                URL url = parentClassLoader.getResource(filename);
                if (url == null) {
                    return null;
                } else {
                    URLConnection connection = url.openConnection();
                    connection.setUseCaches(false);
                    return connection.getInputStream();
                }
            } catch (IOException var4) {
                return null;
            }
        }
    }
}
