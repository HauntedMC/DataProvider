package nl.hauntedmc.dataprovider.commands;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import org.bukkit.ChatColor;
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

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        // Show usage if no subcommand or "help" is provided.
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /dataprovider status");
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            // Retrieve the active databases map from the registry.
            // This map is keyed by DatabaseConnectionKey.
            ConcurrentMap<DatabaseConnectionKey, BaseDatabaseProvider> activeDatabases =
                    DataProvider.getInstance().getDataProviderHandler().getActiveDatabases();

            if (activeDatabases.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "No active database connections found.");
                return true;
            }

            sender.sendMessage(ChatColor.GREEN + "Active Database Connections:");
            for (Map.Entry<DatabaseConnectionKey, BaseDatabaseProvider> entry : activeDatabases.entrySet()) {
                DatabaseConnectionKey key = entry.getKey();
                BaseDatabaseProvider provider = entry.getValue();
                String connectionStatus = provider.isConnected()
                        ? ChatColor.GREEN + "Connected"
                        : ChatColor.RED + "Disconnected";
                sender.sendMessage(ChatColor.YELLOW + "Plugin: " + key.pluginName() +
                        ", Type: " + key.type().name() +
                        ", Identifier: " + key.connectionIdentifier() +
                        " -> " + connectionStatus);
            }
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use /dataprovider help for usage.");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        // Provide suggestions for the first argument.
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
