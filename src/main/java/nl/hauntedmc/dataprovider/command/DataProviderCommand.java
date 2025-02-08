package nl.hauntedmc.dataprovider.command;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;
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
            // Retrieve the active databases map via the registry.
            ConcurrentMap<String, ConcurrentMap<DatabaseType, BaseDatabaseProvider>> activeDatabases =
                    DataProvider.getInstance().getRegistry().getActiveDatabases();

            if (activeDatabases.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "No active database connections found.");
                return true;
            }

            sender.sendMessage(ChatColor.GREEN + "Active Database Connections:");
            // Iterate over each plugin's registered databases
            for (Map.Entry<String, ConcurrentMap<DatabaseType, BaseDatabaseProvider>> pluginEntry : activeDatabases.entrySet()) {
                String pluginName = pluginEntry.getKey();
                ConcurrentMap<DatabaseType, BaseDatabaseProvider> databases = pluginEntry.getValue();
                if (databases.isEmpty()) {
                    continue;
                }
                sender.sendMessage(ChatColor.AQUA + "Plugin: " + pluginName);
                for (Map.Entry<DatabaseType, BaseDatabaseProvider> dbEntry : databases.entrySet()) {
                    DatabaseType type = dbEntry.getKey();
                    BaseDatabaseProvider provider = dbEntry.getValue();
                    String connectionStatus = provider.isConnected()
                            ? ChatColor.GREEN + "Connected"
                            : ChatColor.RED + "Disconnected";
                    sender.sendMessage(ChatColor.YELLOW + "  " + type.name() + ": " + connectionStatus);
                }
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
        // Provide suggestions for the first argument
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
