package nl.hauntedmc.dataprovider.platform.bukkit.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

public class DataProviderCommand implements CommandExecutor, TabCompleter {

    private final DataProvider plugin;

    public DataProviderCommand(DataProvider plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        // Display usage or help message when no arguments or "help" is provided.
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(Component.text("Usage: /dataprovider status", NamedTextColor.YELLOW));
            return true;
        }

        // Handle the "status" subcommand.
        if (args[0].equalsIgnoreCase("status")) {
            // Check for the required permission before executing any subcommand.
            if (!sender.hasPermission("dataprovider.command.status")) {
                sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
                return true;
            }

            ConcurrentMap<DatabaseConnectionKey, BaseDatabaseProvider> activeDatabases =
                    plugin.getDataProviderHandler().getActiveDatabases();

            if (activeDatabases.isEmpty()) {
                sender.sendMessage(Component.text("No active database connections found.", NamedTextColor.YELLOW));
                return true;
            }

            sender.sendMessage(Component.text("Active Database Connections:", NamedTextColor.GREEN));
            for (Map.Entry<DatabaseConnectionKey, BaseDatabaseProvider> entry : activeDatabases.entrySet()) {
                Component connectionInfo = getConnectionComponent(entry);
                sender.sendMessage(connectionInfo);
            }
            return true;
        }

        sender.sendMessage(Component.text("Unknown subcommand. Use /dataprovider help for usage.", NamedTextColor.RED));
        return true;
    }

    private static @NotNull Component getConnectionComponent(Map.Entry<DatabaseConnectionKey, BaseDatabaseProvider> entry) {
        DatabaseConnectionKey key = entry.getKey();
        BaseDatabaseProvider provider = entry.getValue();

        Component statusComponent = provider.isConnected()
                ? Component.text("Connected", NamedTextColor.GREEN)
                : Component.text("Disconnected", NamedTextColor.RED);

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
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            if ("status".startsWith(partial)) {
                completions.add("status");
            }
            if ("help".startsWith(partial)) {
                completions.add("help");
            }
        }
        return completions;
    }
}
