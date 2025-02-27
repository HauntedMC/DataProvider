package nl.hauntedmc.dataprovider.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import nl.hauntedmc.dataprovider.database.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.config.ConfigHandler;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "dataprovider",
        name = "DataProvider",
        version = "1.0.0",
        description = "A cross-platform data provider plugin.",
        authors = {"YourName"}
)
public class VelocityDataProvider {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private DataProviderHandler dataProviderHandler;
    private ConfigHandler configHandler;

    @Inject
    public VelocityDataProvider(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // Initialize configuration using your core module (adapt this to your needs)
        configHandler = new ConfigHandler(dataDirectory);

        // Initialize your core data provider handler
        dataProviderHandler = new DataProviderHandler(/* pass necessary dependencies, e.g., config, logger */);

        // Register commands using Velocity's Command API
        // For example: proxyServer.getCommandManager().register(...);

        // Register event listeners using Velocity's Event Manager
        // For example: proxyServer.getEventManager().register(this, new YourVelocityEventListener(...));

        logger.info("DataProvider plugin enabled on Velocity (v1.0.0).");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (dataProviderHandler != null) {
            dataProviderHandler.shutdownAllDatabases();
        }
        logger.info("DataProvider plugin disabled on Velocity.");
    }
}
