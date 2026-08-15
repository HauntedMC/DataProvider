package nl.hauntedmc.dataprovider.platform.bukkit.command;

import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.platform.common.command.DataProviderAdminCommand;
import nl.hauntedmc.dataprovider.platform.common.command.DataProviderAdminHandler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Paper binding for the shared DataProvider administration command. */
public final class DataProviderCommand implements CommandExecutor, TabCompleter {

    public static final String STATUS_PERMISSION = DataProviderAdminCommand.STATUS_PERMISSION;
    public static final String CONFIG_PERMISSION = DataProviderAdminCommand.CONFIG_PERMISSION;
    public static final String RELOAD_PERMISSION = DataProviderAdminCommand.RELOAD_PERMISSION;

    private final DataProviderAdminCommand command;
    private final Consumer<Runnable> mainThreadExecutor;

    DataProviderCommand(DataProviderHandler handler) {
        this(handler, Runnable::run);
    }

    public DataProviderCommand(DataProviderHandler handler, Consumer<Runnable> mainThreadExecutor) {
        this.command = new DataProviderAdminCommand(new DataProviderAdminHandler(handler));
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor cannot be null");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command ignored,
                             @NotNull String label, String @NotNull [] args) {
        command.execute(args, source(sender));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command ignored,
                                      @NotNull String alias, String @NotNull [] args) {
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
}
