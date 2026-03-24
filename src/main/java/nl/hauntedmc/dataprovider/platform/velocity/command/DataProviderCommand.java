package nl.hauntedmc.dataprovider.platform.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

public class DataProviderCommand implements SimpleCommand {

    private final DataProviderHandler dataProviderHandler;

    public DataProviderCommand(DataProviderHandler dataProviderHandler) {
        this.dataProviderHandler = dataProviderHandler;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        // Display usage or help message when no arguments or "help" is provided.
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            source.sendMessage(Component.text("Usage: /dataprovider status", NamedTextColor.YELLOW));
            return;
        }

        // Handle the "status" subcommand.
        if (args[0].equalsIgnoreCase("status")) {
            // Check permission before executing any subcommand.
            if (!source.hasPermission("dataprovider.command.status")) {
                source.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
                return;
            }

            ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> activeDatabases =
                    dataProviderHandler.getActiveDatabases();

            if (activeDatabases.isEmpty()) {
                source.sendMessage(Component.text("No active database connections found.", NamedTextColor.YELLOW));
                return;
            }

            source.sendMessage(Component.text("Active Database Connections:", NamedTextColor.GREEN));
            for (Map.Entry<DatabaseConnectionKey, DatabaseProvider> entry : activeDatabases.entrySet()) {
                Component connectionInfo = getConnectionComponent(entry);
                source.sendMessage(connectionInfo);
            }
            return;
        }

        source.sendMessage(Component.text("Unknown subcommand. Use /dataprovider help for usage.", NamedTextColor.RED));
    }

    private static Component getConnectionComponent(Map.Entry<DatabaseConnectionKey, DatabaseProvider> entry) {
        DatabaseConnectionKey key = entry.getKey();

        Component statusComponent = Component.text("Registered", NamedTextColor.GREEN);

        return Component.text("Plugin: ", NamedTextColor.YELLOW)
                .append(Component.text(key.pluginName(), NamedTextColor.WHITE))
                .append(Component.text(", Type: ", NamedTextColor.YELLOW))
                .append(Component.text(key.type().name(), NamedTextColor.WHITE))
                .append(Component.text(", Identifier: ", NamedTextColor.YELLOW))
                .append(Component.text(key.connectionIdentifier(), NamedTextColor.WHITE))
                .append(Component.text(" -> ", NamedTextColor.YELLOW))
                .append(statusComponent);
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        List<String> completions = new ArrayList<>();
        String[] args = invocation.arguments();
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            if ("status".startsWith(partial)) {
                completions.add("status");
            }
            if ("help".startsWith(partial)) {
                completions.add("help");
            }
        }
        return CompletableFuture.completedFuture(completions);
    }
}
