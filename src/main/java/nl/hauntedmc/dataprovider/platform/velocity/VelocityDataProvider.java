package nl.hauntedmc.dataprovider.platform.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.platform.velocity.command.DataProviderCommand;
import nl.hauntedmc.dataprovider.platform.velocity.identity.VelocityCallerContextResolver;
import nl.hauntedmc.dataprovider.platform.velocity.logger.SLF4JLoggerAdapter;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "dataprovider",
        name = "DataProvider",
        version = "1.20.1",
        description = "A cross-platform data provider plugin.",
        authors = {"HauntedMC"}
)
public class VelocityDataProvider {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private static DataProvider dataProvider;

    @Inject
    public VelocityDataProvider(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        SLF4JLoggerAdapter logInstance = new SLF4JLoggerAdapter(logger);
        dataProvider = new DataProvider(
                logInstance,
                dataDirectory,
                getClass().getClassLoader(),
                new VelocityCallerContextResolver(proxyServer, getClass().getClassLoader())
        );

        CommandManager commandManager = proxyServer.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("dataprovider")
                .build();
        commandManager.register(meta, new DataProviderCommand(dataProvider.getDataProviderHandler()));

        String pluginVersion = proxyServer.getPluginManager()
                .fromInstance(this)
                .map(container -> container.getDescription().getVersion().toString())
                .orElse("unknown");
        logger.info("DataProvider plugin enabled on Velocity (v{}).", pluginVersion);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (dataProvider != null) {
            dataProvider.shutdownAllDatabases();
        }
        logger.info("DataProvider plugin disabled on Velocity.");
    }

    // START EXTERNALLY ACCESSIBLE
    public static DataProviderAPI getDataProviderAPI() {
        if (dataProvider == null) {
            throw new IllegalStateException("DataProvider is not initialized yet.");
        }
        return new DataProviderAPI(dataProvider.getDataProviderHandler());
    }
    // END EXTERNALLY ACCESSIBLE
}
