package nl.hauntedmc.dataprovider.platform.internal.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Shared command behavior used by all platform command adapters.
 */
public final class DataProviderCommandService {

    private static final String STATUS_SUBCOMMAND = "status";
    private static final String HELP_SUBCOMMAND = "help";
    private static final String STATUS_PERMISSION = "dataprovider.command.status";

    private static final Component USAGE_MESSAGE =
            Component.text("Usage: /dataprovider status", NamedTextColor.YELLOW);
    private static final Component NO_PERMISSION_MESSAGE =
            Component.text("You do not have permission to use this command.", NamedTextColor.RED);
    private static final Component NO_ACTIVE_CONNECTIONS_MESSAGE =
            Component.text("No active database connections found.", NamedTextColor.YELLOW);
    private static final Component CONNECTIONS_HEADER =
            Component.text("Active Database Connections:", NamedTextColor.GREEN);
    private static final Component UNKNOWN_SUBCOMMAND_MESSAGE =
            Component.text("Unknown subcommand. Use /dataprovider help for usage.", NamedTextColor.RED);

    private static final List<String> ROOT_COMPLETIONS = List.of(STATUS_SUBCOMMAND, HELP_SUBCOMMAND);
    private static final Comparator<DatabaseConnectionKey> CONNECTION_KEY_COMPARATOR =
            Comparator.comparing(DatabaseConnectionKey::pluginName)
                    .thenComparing(key -> key.type().name())
                    .thenComparing(DatabaseConnectionKey::connectionIdentifier);

    private final DataProviderHandler dataProviderHandler;

    public DataProviderCommandService(DataProviderHandler dataProviderHandler) {
        this.dataProviderHandler = Objects.requireNonNull(dataProviderHandler, "Data provider handler cannot be null.");
    }

    public void execute(
            String[] args,
            Predicate<String> permissionChecker,
            Consumer<Component> messageSink
    ) {
        Objects.requireNonNull(args, "Args cannot be null.");
        Objects.requireNonNull(permissionChecker, "Permission checker cannot be null.");
        Objects.requireNonNull(messageSink, "Message sink cannot be null.");

        if (args.length == 0 || HELP_SUBCOMMAND.equalsIgnoreCase(args[0])) {
            messageSink.accept(USAGE_MESSAGE);
            return;
        }

        if (!STATUS_SUBCOMMAND.equalsIgnoreCase(args[0])) {
            messageSink.accept(UNKNOWN_SUBCOMMAND_MESSAGE);
            return;
        }

        if (!permissionChecker.test(STATUS_PERMISSION)) {
            messageSink.accept(NO_PERMISSION_MESSAGE);
            return;
        }

        ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> activeDatabases =
                dataProviderHandler.getActiveDatabases();
        Map<DatabaseConnectionKey, Integer> referenceCounts =
                dataProviderHandler.getActiveDatabaseReferenceCounts();

        if (activeDatabases.isEmpty()) {
            messageSink.accept(NO_ACTIVE_CONNECTIONS_MESSAGE);
            return;
        }

        messageSink.accept(CONNECTIONS_HEADER);
        activeDatabases.keySet().stream()
                .sorted(CONNECTION_KEY_COMPARATOR)
                .forEach(key -> {
                    int references = referenceCounts.getOrDefault(key, 1);
                    messageSink.accept(toConnectionComponent(key, references));
                });
    }

    public List<String> suggest(String[] args) {
        Objects.requireNonNull(args, "Args cannot be null.");
        if (args.length != 1) {
            return List.of();
        }

        String partial = args[0].toLowerCase(Locale.ROOT);
        return ROOT_COMPLETIONS.stream()
                .filter(completion -> completion.startsWith(partial))
                .toList();
    }

    private static Component toConnectionComponent(DatabaseConnectionKey key, int references) {
        Component statusComponent = Component.text("Registered (" + references + " refs)", NamedTextColor.GREEN);

        return Component.text("Plugin: ", NamedTextColor.YELLOW)
                .append(Component.text(key.pluginName(), NamedTextColor.WHITE))
                .append(Component.text(", Type: ", NamedTextColor.YELLOW))
                .append(Component.text(key.type().name(), NamedTextColor.WHITE))
                .append(Component.text(", Identifier: ", NamedTextColor.YELLOW))
                .append(Component.text(key.connectionIdentifier(), NamedTextColor.WHITE))
                .append(Component.text(" -> ", NamedTextColor.YELLOW))
                .append(statusComponent);
    }
}
