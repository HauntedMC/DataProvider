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
import nl.hauntedmc.dataprovider.api.DataProviderApiSupplier;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.platform.internal.lifecycle.PlatformDataProviderRuntime;
import nl.hauntedmc.dataprovider.platform.velocity.command.DataProviderCommand;
import nl.hauntedmc.dataprovider.platform.velocity.identity.VelocityCallerContextResolver;
import nl.hauntedmc.dataprovider.logging.adapters.Slf4jLoggerAdapter;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "dataprovider",
        name = "DataProvider",
        version = "2.0.0",
        description = "A cross-platform data provider plugin.",
        authors = {"HauntedMC"}
)
public final class VelocityDataProvider implements DataProviderApiSupplier {

    private static final short INITIALIZE_EVENT_PRIORITY = Short.MAX_VALUE;
    private static final short SHUTDOWN_EVENT_PRIORITY = Short.MIN_VALUE;
    private static final String COMMAND_NAME = "dataprovider";
    private static final String NOT_INITIALIZED_MESSAGE = "DataProvider is not initialized yet.";

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private final PlatformDataProviderRuntime runtime = new PlatformDataProviderRuntime();
    private volatile DataProviderAPI dataProviderApi;

    @Inject
    public VelocityDataProvider(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe(priority = INITIALIZE_EVENT_PRIORITY)
    public void onProxyInitialize(ProxyInitializeEvent event) {
        Slf4jLoggerAdapter loggerAdapter = new Slf4jLoggerAdapter(logger);
        runtime.start(
                () -> new DataProvider(
                        loggerAdapter,
                        dataDirectory,
                        getClass().getClassLoader(),
                        new VelocityCallerContextResolver(proxyServer, getClass().getClassLoader())
                ),
                this::initializeBindings,
                loggerAdapter
        );

        String pluginVersion = resolvePluginVersion(proxyServer, this);
        logger.info("DataProvider plugin enabled on Velocity (v{}).", pluginVersion);
    }

    @Subscribe(priority = SHUTDOWN_EVENT_PRIORITY)
    public void onProxyShutdown(ProxyShutdownEvent event) {
        dataProviderApi = null;
        runtime.stop(new Slf4jLoggerAdapter(logger));
        logger.info("DataProvider plugin disabled on Velocity.");
    }

    @Override
    public DataProviderAPI dataProviderApi() {
        DataProviderAPI api = dataProviderApi;
        if (api == null) {
            throw new IllegalStateException(NOT_INITIALIZED_MESSAGE);
        }
        return api;
    }

    private void initializeBindings(DataProvider provider) {
        registerCommand(provider.getDataProviderHandler());
        dataProviderApi = new DataProviderAPI(provider.getDataProviderHandler());
    }

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
