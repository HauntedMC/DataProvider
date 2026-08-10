package nl.hauntedmc.dataprovider.platform.bukkit.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.platform.common.command.DataProviderCommandService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class DataProviderCommand implements BasicCommand {

    private final DataProviderCommandService commandService;

    public DataProviderCommand(DataProviderHandler dataProviderHandler) {
        this(new DataProviderCommandService(dataProviderHandler));
    }

    DataProviderCommand(DataProviderCommandService commandService) {
        this.commandService = Objects.requireNonNull(commandService, "Command service cannot be null.");
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        execute(stack.getSender(), args);
    }

    void execute(CommandSender sender, String[] args) {
        commandService.execute(args, sender::hasPermission, sender::sendMessage);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        return suggest(stack.getSender(), args);
    }

    List<String> suggest(CommandSender sender, String[] args) {
        return commandService.suggest(args, sender::hasPermission);
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return !(sender instanceof Player)
                || DataProviderCommandService.canUseRootCommand(sender::hasPermission);
    }
}
