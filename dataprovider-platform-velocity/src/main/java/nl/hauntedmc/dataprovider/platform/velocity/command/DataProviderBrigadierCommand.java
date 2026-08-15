package nl.hauntedmc.dataprovider.platform.velocity.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import nl.hauntedmc.dataprovider.platform.common.command.DataProviderAdminCommand;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Velocity-native Brigadier tree bound to the shared DataProvider administration behavior. */
public final class DataProviderBrigadierCommand {

    public static final String COMMAND_NAME = "dataproviderproxy";
    public static final String STATUS_PERMISSION = DataProviderAdminCommand.STATUS_PERMISSION;
    public static final String CONFIG_PERMISSION = DataProviderAdminCommand.CONFIG_PERMISSION;
    public static final String RELOAD_PERMISSION = DataProviderAdminCommand.RELOAD_PERMISSION;

    private DataProviderBrigadierCommand() {
    }

    public static BrigadierCommand create(DataProviderAdminCommand.Handler handler) {
        DataProviderAdminCommand command = new DataProviderAdminCommand(
                Objects.requireNonNull(handler, "handler cannot be null"), COMMAND_NAME
        );
        return new BrigadierCommand(BrigadierCommand.literalArgumentBuilder(COMMAND_NAME)
                .requires(source -> DataProviderAdminCommand.canUseRootCommand(source::hasPermission))
                .executes(context -> execute(command, context.getSource()))
                .then(BrigadierCommand.literalArgumentBuilder("help")
                        .executes(context -> execute(command, context.getSource(), "help")))
                .then(BrigadierCommand.literalArgumentBuilder("status")
                        .requires(source -> source.hasPermission(STATUS_PERMISSION))
                        .executes(context -> execute(command, context.getSource(), "status"))
                        .then(BrigadierCommand.literalArgumentBuilder("summary")
                                .executes(context -> execute(command, context.getSource(), "status", "summary"))))
                .then(BrigadierCommand.literalArgumentBuilder("diagnostics")
                        .requires(source -> source.hasPermission(STATUS_PERMISSION))
                        .executes(context -> execute(command, context.getSource(), "diagnostics")))
                .then(connectionsCommand(command))
                .then(BrigadierCommand.literalArgumentBuilder("health")
                        .requires(source -> source.hasPermission(STATUS_PERMISSION))
                        .executes(context -> execute(command, context.getSource(), "health"))
                        .then(BrigadierCommand.literalArgumentBuilder("check")
                                .executes(context -> execute(command, context.getSource(), "health", "check"))))
                .then(BrigadierCommand.literalArgumentBuilder("config")
                        .requires(source -> source.hasPermission(CONFIG_PERMISSION))
                        .executes(context -> execute(command, context.getSource(), "config")))
                .then(BrigadierCommand.literalArgumentBuilder("reload")
                        .requires(source -> source.hasPermission(RELOAD_PERMISSION))
                        .executes(context -> execute(command, context.getSource(), "reload"))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSource> connectionsCommand(
            DataProviderAdminCommand command
    ) {
        return BrigadierCommand.literalArgumentBuilder("connections")
                .requires(source -> source.hasPermission(STATUS_PERMISSION))
                .executes(context -> execute(command, context.getSource(), "connections"))
                .then(BrigadierCommand.literalArgumentBuilder("unhealthy")
                        .executes(context -> execute(command, context.getSource(), "connections", "unhealthy")))
                .then(BrigadierCommand.literalArgumentBuilder("plugin")
                        .then(BrigadierCommand.requiredArgumentBuilder("name", StringArgumentType.word())
                                .suggests((context, builder) -> suggestions(command, context.getSource(),
                                        new String[]{"connections", "plugin", builder.getRemaining()}, builder))
                                .executes(context -> execute(command, context.getSource(), "connections", "plugin",
                                        StringArgumentType.getString(context, "name")))))
                .then(BrigadierCommand.literalArgumentBuilder("type")
                        .then(BrigadierCommand.requiredArgumentBuilder("type", StringArgumentType.word())
                                .suggests((context, builder) -> suggestions(command, context.getSource(),
                                        new String[]{"connections", "type", builder.getRemaining()}, builder))
                                .executes(context -> execute(command, context.getSource(), "connections", "type",
                                        StringArgumentType.getString(context, "type")))));
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestions(
            DataProviderAdminCommand command,
            CommandSource source,
            String[] arguments,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder
    ) {
        try {
            command.suggest(arguments, source(source)).forEach(builder::suggest);
        } catch (RuntimeException ignored) {
            // Completion is advisory; stale registry state must never break the command dispatcher.
        }
        return builder.buildFuture();
    }

    private static int execute(DataProviderAdminCommand command, CommandSource source, String... arguments) {
        command.execute(arguments, source(source));
        return Command.SINGLE_SUCCESS;
    }

    private static DataProviderAdminCommand.Source source(CommandSource source) {
        return new DataProviderAdminCommand.Source() {
            @Override
            public boolean hasPermission(String permission) {
                return source.hasPermission(permission);
            }

            @Override
            public void sendMessage(net.kyori.adventure.text.Component message) {
                source.sendMessage(message);
            }

            @Override
            public void dispatchCompletion(Runnable task) {
                task.run();
            }
        };
    }
}
