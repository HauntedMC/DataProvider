package nl.hauntedmc.dataprovider.platform.bukkit.command;

import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.platform.common.command.DataProviderCommandService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

public final class DataProviderCommand implements CommandExecutor, TabCompleter {

    private final DataProviderCommandService commandService;

    public DataProviderCommand(DataProviderHandler dataProviderHandler) {
        this(new DataProviderCommandService(dataProviderHandler));
    }

    DataProviderCommand(DataProviderCommandService commandService) {
        this.commandService = Objects.requireNonNull(commandService, "Command service cannot be null.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        commandService.execute(args, sender::hasPermission, sender::sendMessage);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String[] args) {
        return commandService.suggest(args);
    }
}
