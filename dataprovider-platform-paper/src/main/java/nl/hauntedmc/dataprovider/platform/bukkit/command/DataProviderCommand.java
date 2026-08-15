package nl.hauntedmc.dataprovider.platform.bukkit.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.platform.common.command.DataProviderAdminCommand;
import nl.hauntedmc.dataprovider.platform.common.command.DataProviderAdminHandler;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Paper binding for the shared DataProvider administration command. */
public final class DataProviderCommand implements BasicCommand {

    public static final String STATUS_PERMISSION = DataProviderAdminCommand.STATUS_PERMISSION;
    public static final String CONFIG_PERMISSION = DataProviderAdminCommand.CONFIG_PERMISSION;
    public static final String RELOAD_PERMISSION = DataProviderAdminCommand.RELOAD_PERMISSION;

    private final DataProviderAdminCommand command;
    private final Consumer<Runnable> mainThreadExecutor;

    DataProviderCommand(DataProviderHandler handler) {
        this(handler, Runnable::run);
    }

    public DataProviderCommand(DataProviderHandler handler, Consumer<Runnable> mainThreadExecutor) {
        this.command = new DataProviderAdminCommand(new DataProviderAdminHandler(handler), "dataprovider");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor cannot be null");
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        execute(stack.getSender(), args);
    }

    void execute(CommandSender sender, String[] args) {
        command.execute(args, source(sender));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        return suggest(stack.getSender(), args);
    }

    List<String> suggest(CommandSender sender, String[] args) {
        return command.suggest(args, source(sender));
    }

    private DataProviderAdminCommand.Source source(CommandSender sender) {
        return new DataProviderAdminCommand.Source() {
            @Override
            public boolean hasPermission(String permission) {
                return sender.hasPermission(permission);
            }

            @Override
            public void sendMessage(net.kyori.adventure.text.Component message) {
                sender.sendMessage(message);
            }

            @Override
            public void dispatchCompletion(Runnable task) {
                mainThreadExecutor.accept(task);
            }
        };
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return !(sender instanceof Player)
                || DataProviderAdminCommand.canUseRootCommand(sender::hasPermission);
    }
}
