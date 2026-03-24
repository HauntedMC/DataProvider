package nl.hauntedmc.dataprovider;

import nl.hauntedmc.dataprovider.config.ConfigHandler;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.internal.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.Objects;

public class DataProvider {

    private final ILoggerAdapter logger;
    private final Path dataPath;
    private final ClassLoader parentClassLoader;
    private final ConfigHandler configHandler;
    private final DataProviderHandler dataProviderHandler;

    public DataProvider(
            ILoggerAdapter logger,
            Path dataDirectory,
            ClassLoader classLoader,
            CallerContextResolver callerContextResolver
    ) {
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        this.dataPath = Objects.requireNonNull(dataDirectory, "Data directory cannot be null.");
        this.parentClassLoader = Objects.requireNonNull(classLoader, "Class loader cannot be null.");
        Objects.requireNonNull(callerContextResolver, "Caller context resolver cannot be null.");

        // Init Main Config
        this.configHandler = new ConfigHandler(dataPath, this.logger);

        // Init Data Provider internals
        dataProviderHandler = new DataProviderHandler(
                dataPath,
                parentClassLoader,
                configHandler,
                callerContextResolver,
                this.logger
        );
    }

    public ILoggerAdapter getLogger() {
        return logger;
    }

    public DataProviderHandler getDataProviderHandler() {
        return dataProviderHandler;
    }

    public Path getDataPath() {
        return dataPath;
    }

    public ConfigHandler getConfigHandler() {
        return configHandler;
    }

    public void shutdownAllDatabases() {
        dataProviderHandler.shutdownAllDatabases();
    }

    public @Nullable InputStream getResource(String filename) {
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
