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
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.platform.common.lifecycle.PlatformDataProviderRuntime;
import nl.hauntedmc.dataprovider.platform.velocity.command.DataProviderCommand;
import nl.hauntedmc.dataprovider.platform.velocity.identity.VelocityCallerContextResolver;
import nl.hauntedmc.dataprovider.platform.velocity.logger.SLF4JLoggerAdapter;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "dataprovider",
        name = "DataProvider",
        version = "2.0.0",
        description = "A cross-platform data provider plugin.",
        authors = {"HauntedMC"}
)
public final class VelocityDataProvider {

    private static final short INITIALIZE_EVENT_PRIORITY = Short.MAX_VALUE;
    private static final short SHUTDOWN_EVENT_PRIORITY = Short.MIN_VALUE;
    private static final String COMMAND_NAME = "dataprovider";
    private static final PlatformDataProviderRuntime RUNTIME = new PlatformDataProviderRuntime();

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;

    @Inject
    public VelocityDataProvider(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe(priority = INITIALIZE_EVENT_PRIORITY)
    public void onProxyInitialize(ProxyInitializeEvent event) {
        SLF4JLoggerAdapter loggerAdapter = new SLF4JLoggerAdapter(logger);
        DataProvider provider = RUNTIME.start(
                () -> new DataProvider(
                        loggerAdapter,
                        dataDirectory,
                        getClass().getClassLoader(),
                        new VelocityCallerContextResolver(proxyServer, getClass().getClassLoader())
                ),
                loggerAdapter
        );
        try {
            registerCommand(provider.getDataProviderHandler());
        } catch (RuntimeException exception) {
            loggerAdapter.error("Failed to initialize Velocity command wiring.", exception);
            RUNTIME.stop(loggerAdapter);
            throw exception;
        }

        String pluginVersion = resolvePluginVersion(proxyServer, this);
        logger.info("DataProvider plugin enabled on Velocity (v{}).", pluginVersion);
    }

    @Subscribe(priority = SHUTDOWN_EVENT_PRIORITY)
    public void onProxyShutdown(ProxyShutdownEvent event) {
        RUNTIME.stop(new SLF4JLoggerAdapter(logger));
        logger.info("DataProvider plugin disabled on Velocity.");
    }

    // START EXTERNALLY ACCESSIBLE
    public static DataProviderAPI getDataProviderAPI() {
        return RUNTIME.getDataProviderAPI();
    }
    // END EXTERNALLY ACCESSIBLE

    private void registerCommand(DataProviderHandler handler) {
        CommandManager commandManager = proxyServer.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder(COMMAND_NAME).build();
        commandManager.register(meta, new DataProviderCommand(handler));
    }

    static String resolvePluginVersion(ProxyServer proxyServer, Object pluginInstance) {
        return proxyServer.getPluginManager()
                .fromInstance(pluginInstance)
                .flatMap(container -> container.getDescription().getVersion())
                .orElse("unknown");
    }
}
