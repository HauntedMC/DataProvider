package nl.hauntedmc.dataprovider.platform.common.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.core.ConnectionHealthSnapshot;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Shared command behavior used by all platform command adapters.
 */
public final class DataProviderCommandService {

    private static final String STATUS_SUBCOMMAND = "status";
    private static final String CONFIG_SUBCOMMAND = "config";
    private static final String RELOAD_SUBCOMMAND = "reload";
    private static final String HELP_SUBCOMMAND = "help";
    private static final String STATUS_PERMISSION = "dataprovider.command.status";
    private static final String CONFIG_PERMISSION = "dataprovider.command.config";
    private static final String RELOAD_PERMISSION = "dataprovider.command.reload";

    private static final Component HELP_HEADER =
            Component.text("DataProvider command help:", NamedTextColor.GOLD);
    private static final Component NO_ACTIVE_CONNECTIONS_MESSAGE =
            Component.text("No active database connections found.", NamedTextColor.YELLOW);
    private static final Component NO_MATCHING_CONNECTIONS_MESSAGE =
            Component.text("No active database connections match the selected filters.", NamedTextColor.YELLOW);
    private static final Component UNKNOWN_SUBCOMMAND_MESSAGE =
            Component.text("Unknown subcommand. Use /dataprovider help for usage.", NamedTextColor.RED);
    private static final Component STATUS_USAGE_MESSAGE =
            Component.text(
                    "Usage: /dataprovider status [summary|connections] [unhealthy] [plugin <name>] [type <databaseType>]",
                    NamedTextColor.YELLOW
            );
    private static final Component CONFIG_USAGE_MESSAGE =
            Component.text("Usage: /dataprovider config", NamedTextColor.YELLOW);
    private static final Component RELOAD_USAGE_MESSAGE =
            Component.text("Usage: /dataprovider reload", NamedTextColor.YELLOW);

    private static final List<String> ROOT_COMPLETIONS = List.of(
            HELP_SUBCOMMAND,
            STATUS_SUBCOMMAND,
            CONFIG_SUBCOMMAND,
            RELOAD_SUBCOMMAND
    );
    private static final List<String> STATUS_OPTION_COMPLETIONS = List.of(
            "summary",
            "connections",
            "unhealthy",
            "plugin",
            "type"
    );
    private static final List<String> DATABASE_TYPE_COMPLETIONS = Arrays.stream(DatabaseType.values())
            .map(type -> type.name().toLowerCase(Locale.ROOT))
            .toList();
    private static final Comparator<DatabaseConnectionKey> CONNECTION_KEY_COMPARATOR =
            Comparator.comparing(DatabaseConnectionKey::pluginName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(key -> key.type().name())
                    .thenComparing(DatabaseConnectionKey::connectionIdentifier, String.CASE_INSENSITIVE_ORDER);

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
            sendHelp(messageSink);
            return;
        }

        String rootSubcommand = args[0].toLowerCase(Locale.ROOT);
        switch (rootSubcommand) {
            case STATUS_SUBCOMMAND -> executeStatus(args, permissionChecker, messageSink);
            case CONFIG_SUBCOMMAND -> executeConfig(args, permissionChecker, messageSink);
            case RELOAD_SUBCOMMAND -> executeReload(args, permissionChecker, messageSink);
            default -> messageSink.accept(UNKNOWN_SUBCOMMAND_MESSAGE);
        }
    }

    public List<String> suggest(String[] args, Predicate<String> permissionChecker) {
        Objects.requireNonNull(args, "Args cannot be null.");
        Objects.requireNonNull(permissionChecker, "Permission checker cannot be null.");
        if (args.length == 0) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> visibleRootCompletions = ROOT_COMPLETIONS.stream()
                    .filter(subcommand -> isRootSubcommandVisible(subcommand, permissionChecker))
                    .toList();
            return filterCompletions(visibleRootCompletions, args[0]);
        }

        if (STATUS_SUBCOMMAND.equalsIgnoreCase(args[0])) {
            if (!permissionChecker.test(STATUS_PERMISSION)) {
                return List.of();
            }
            return suggestStatusArguments(args);
        }

        return List.of();
    }

    private void executeStatus(
            String[] args,
            Predicate<String> permissionChecker,
            Consumer<Component> messageSink
    ) {
        if (!permissionChecker.test(STATUS_PERMISSION)) {
            messageSink.accept(noPermissionMessage(STATUS_PERMISSION));
            return;
        }

        StatusOptions statusOptions = parseStatusOptions(args, messageSink);
        if (statusOptions == null) {
            return;
        }

        List<ConnectionStatus> allConnections = listConnectionStatuses(messageSink);
        if (allConnections == null) {
            return;
        }

        if (allConnections.isEmpty()) {
            messageSink.accept(NO_ACTIVE_CONNECTIONS_MESSAGE);
            return;
        }

        List<ConnectionStatus> filteredConnections = allConnections.stream()
                .filter(status -> statusOptions.pluginFilter() == null
                        || status.key().pluginName().equalsIgnoreCase(statusOptions.pluginFilter()))
                .filter(status -> statusOptions.typeFilter() == null
                        || status.key().type() == statusOptions.typeFilter())
                .filter(status -> !statusOptions.unhealthyOnly()
                        || status.health().remoteHealth() != ConnectionHealthSnapshot.RemoteHealth.HEALTHY)
                .toList();

        if (filteredConnections.isEmpty()) {
            messageSink.accept(NO_MATCHING_CONNECTIONS_MESSAGE);
            return;
        }

        sendStatusOverview(filteredConnections, statusOptions, messageSink);
        sendStatusAggregatesByPlugin(filteredConnections, messageSink);
        sendStatusAggregatesByType(filteredConnections, messageSink);
        if (!statusOptions.summaryOnly()) {
            sendConnectionRows(filteredConnections, messageSink);
        }
    }

    private void executeConfig(
            String[] args,
            Predicate<String> permissionChecker,
            Consumer<Component> messageSink
    ) {
        if (args.length > 1) {
            messageSink.accept(CONFIG_USAGE_MESSAGE);
            return;
        }
        if (!permissionChecker.test(CONFIG_PERMISSION)) {
            messageSink.accept(noPermissionMessage(CONFIG_PERMISSION));
            return;
        }

        Map<DatabaseType, Boolean> states;
        String ormSchemaMode;
        try {
            states = dataProviderHandler.getConfiguredDatabaseTypeStates();
            ormSchemaMode = dataProviderHandler.getConfiguredOrmSchemaMode();
        } catch (RuntimeException exception) {
            messageSink.accept(failedOperationMessage("Failed to inspect DataProvider config", exception));
            return;
        }

        long enabledCount = states.values().stream().filter(Boolean::booleanValue).count();
        messageSink.accept(Component.text("DataProvider Config", NamedTextColor.GOLD));
        messageSink.accept(Component.text(
                "ORM schema_mode=" + ormSchemaMode
                        + ", enabled backends=" + enabledCount + "/" + DatabaseType.values().length,
                NamedTextColor.YELLOW
        ));
        for (DatabaseType type : DatabaseType.values()) {
            boolean enabled = states.getOrDefault(type, Boolean.TRUE);
            messageSink.accept(Component.text(" - " + type.name() + ": ", NamedTextColor.YELLOW)
                    .append(Component.text(enabled ? "enabled" : "disabled", enabled ? NamedTextColor.GREEN : NamedTextColor.RED)));
        }
    }

    private void executeReload(
            String[] args,
            Predicate<String> permissionChecker,
            Consumer<Component> messageSink
    ) {
        if (args.length > 1) {
            messageSink.accept(RELOAD_USAGE_MESSAGE);
            return;
        }
        if (!permissionChecker.test(RELOAD_PERMISSION)) {
            messageSink.accept(noPermissionMessage(RELOAD_PERMISSION));
            return;
        }

        try {
            dataProviderHandler.reloadConfiguration();
        } catch (RuntimeException exception) {
            messageSink.accept(failedOperationMessage("Failed to reload DataProvider config", exception));
            return;
        }

        messageSink.accept(Component.text("Reloaded validated DataProvider configuration snapshot.", NamedTextColor.GREEN));
        messageSink.accept(Component.text(
                "Existing connections retain prior settings until explicitly reconnected.",
                NamedTextColor.YELLOW
        ));
    }

    private void sendHelp(Consumer<Component> messageSink) {
        messageSink.accept(HELP_HEADER);
        messageSink.accept(Component.text("/dataprovider help", NamedTextColor.YELLOW)
                .append(Component.text(" - Show this help.", NamedTextColor.GRAY)));
        messageSink.accept(Component.text(
                        "/dataprovider status [summary|connections] [unhealthy] [plugin <name>] [type <databaseType>]",
                        NamedTextColor.YELLOW
                )
                .append(Component.text(" - Connection diagnostics.", NamedTextColor.GRAY))
                .append(Component.text(" (" + STATUS_PERMISSION + ")", NamedTextColor.DARK_GRAY)));
        messageSink.accept(Component.text("/dataprovider config", NamedTextColor.YELLOW)
                .append(Component.text(" - Show runtime config state.", NamedTextColor.GRAY))
                .append(Component.text(" (" + CONFIG_PERMISSION + ")", NamedTextColor.DARK_GRAY)));
        messageSink.accept(Component.text("/dataprovider reload", NamedTextColor.YELLOW)
                .append(Component.text(" - Atomically reload config.yml and databases/*.yml.", NamedTextColor.GRAY))
                .append(Component.text(" (" + RELOAD_PERMISSION + ")", NamedTextColor.DARK_GRAY)));
    }

    private StatusOptions parseStatusOptions(String[] args, Consumer<Component> messageSink) {
        boolean summaryOnly = false;
        boolean unhealthyOnly = false;
        boolean sawSummary = false;
        boolean sawConnections = false;
        String pluginFilter = null;
        DatabaseType typeFilter = null;

        int index = 1;
        while (index < args.length) {
            String token = args[index].toLowerCase(Locale.ROOT);
            switch (token) {
                case "summary" -> {
                    if (sawConnections) {
                        messageSink.accept(Component.text(
                                "Cannot combine 'summary' with 'connections' in the same command.",
                                NamedTextColor.RED
                        ));
                        messageSink.accept(STATUS_USAGE_MESSAGE);
                        return null;
                    }
                    summaryOnly = true;
                    sawSummary = true;
                    index++;
                }
                case "connections" -> {
                    if (sawSummary) {
                        messageSink.accept(Component.text(
                                "Cannot combine 'connections' with 'summary' in the same command.",
                                NamedTextColor.RED
                        ));
                        messageSink.accept(STATUS_USAGE_MESSAGE);
                        return null;
                    }
                    summaryOnly = false;
                    sawConnections = true;
                    index++;
                }
                case "unhealthy" -> {
                    unhealthyOnly = true;
                    index++;
                }
                case "plugin" -> {
                    if (index + 1 >= args.length) {
                        messageSink.accept(Component.text("Missing plugin name after 'plugin'.", NamedTextColor.RED));
                        messageSink.accept(STATUS_USAGE_MESSAGE);
                        return null;
                    }
                    if (pluginFilter != null) {
                        messageSink.accept(Component.text("Plugin filter can only be set once.", NamedTextColor.RED));
                        messageSink.accept(STATUS_USAGE_MESSAGE);
                        return null;
                    }
                    String pluginName = args[index + 1].trim();
                    if (pluginName.isEmpty()) {
                        messageSink.accept(Component.text("Plugin filter cannot be blank.", NamedTextColor.RED));
                        messageSink.accept(STATUS_USAGE_MESSAGE);
                        return null;
                    }
                    pluginFilter = pluginName;
                    index += 2;
                }
                case "type" -> {
                    if (index + 1 >= args.length) {
                        messageSink.accept(Component.text("Missing database type after 'type'.", NamedTextColor.RED));
                        messageSink.accept(STATUS_USAGE_MESSAGE);
                        return null;
                    }
                    if (typeFilter != null) {
                        messageSink.accept(Component.text("Type filter can only be set once.", NamedTextColor.RED));
                        messageSink.accept(STATUS_USAGE_MESSAGE);
                        return null;
                    }
                    String rawType = args[index + 1];
                    DatabaseType parsedType = parseDatabaseType(rawType);
                    if (parsedType == null) {
                        messageSink.accept(Component.text(
                                "Unknown database type '" + rawType + "'. Valid types: " + String.join(", ", DATABASE_TYPE_COMPLETIONS),
                                NamedTextColor.RED
                        ));
                        messageSink.accept(STATUS_USAGE_MESSAGE);
                        return null;
                    }
                    typeFilter = parsedType;
                    index += 2;
                }
                default -> {
                    messageSink.accept(Component.text("Unknown status option '" + args[index] + "'.", NamedTextColor.RED));
                    messageSink.accept(STATUS_USAGE_MESSAGE);
                    return null;
                }
            }
        }

        return new StatusOptions(summaryOnly, unhealthyOnly, pluginFilter, typeFilter);
    }

    private List<ConnectionStatus> listConnectionStatuses(Consumer<Component> messageSink) {
        ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> activeDatabases;
        Map<DatabaseConnectionKey, ConnectionHealthSnapshot> healthSnapshots;
        Map<DatabaseConnectionKey, Integer> referenceCounts;
        try {
            activeDatabases = dataProviderHandler.getActiveDatabases();
            healthSnapshots = dataProviderHandler.getCachedDatabaseHealth();
            referenceCounts = dataProviderHandler.getActiveDatabaseReferenceCounts();
            dataProviderHandler.probeDatabaseHealthAsync();
        } catch (RuntimeException exception) {
            messageSink.accept(failedOperationMessage("Failed to inspect active connections", exception));
            return null;
        }

        return activeDatabases.keySet().stream()
                .sorted(CONNECTION_KEY_COMPARATOR)
                .map(key -> {
                    int references = Math.max(1, referenceCounts.getOrDefault(key, 1));
                    ConnectionHealthSnapshot health = healthSnapshots.getOrDefault(
                            key,
                            ConnectionHealthSnapshot.unprobed(false)
                    );
                    return new ConnectionStatus(key, references, health);
                })
                .toList();
    }

    private void sendStatusOverview(
            List<ConnectionStatus> filteredConnections,
            StatusOptions statusOptions,
            Consumer<Component> messageSink
    ) {
        long unhealthyCount = filteredConnections.stream()
                .filter(status -> status.health().remoteHealth() != ConnectionHealthSnapshot.RemoteHealth.HEALTHY)
                .count();
        int totalReferences = filteredConnections.stream().mapToInt(ConnectionStatus::references).sum();
        long uniquePluginCount = filteredConnections.stream().map(status -> status.key().pluginName()).distinct().count();
        long uniqueTypeCount = filteredConnections.stream().map(status -> status.key().type()).distinct().count();

        messageSink.accept(Component.text("DataProvider Status", NamedTextColor.GOLD));
        messageSink.accept(Component.text(
                "connections=" + filteredConnections.size()
                        + ", references=" + totalReferences
                        + ", plugins=" + uniquePluginCount
                        + ", backends=" + uniqueTypeCount
                        + ", unhealthy=" + unhealthyCount,
                NamedTextColor.YELLOW
        ));

        List<String> activeFilters = new ArrayList<>();
        if (statusOptions.pluginFilter() != null) {
            activeFilters.add("plugin=" + statusOptions.pluginFilter());
        }
        if (statusOptions.typeFilter() != null) {
            activeFilters.add("type=" + statusOptions.typeFilter().name());
        }
        if (statusOptions.unhealthyOnly()) {
            activeFilters.add("health=unhealthy");
        }
        activeFilters.add(statusOptions.summaryOnly() ? "view=summary" : "view=connections");
        messageSink.accept(Component.text("filters: " + String.join(", ", activeFilters), NamedTextColor.GRAY));
    }

    private void sendStatusAggregatesByPlugin(List<ConnectionStatus> statuses, Consumer<Component> messageSink) {
        Map<String, AggregateCounters> byPlugin = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ConnectionStatus status : statuses) {
            AggregateCounters counters = byPlugin.computeIfAbsent(status.key().pluginName(), ignored -> new AggregateCounters());
            counters.increment(status.references(), status.health());
        }

        messageSink.accept(Component.text("By plugin:", NamedTextColor.GREEN));
        for (Map.Entry<String, AggregateCounters> entry : byPlugin.entrySet()) {
            AggregateCounters counters = entry.getValue();
            messageSink.accept(Component.text(" - " + entry.getKey() + ": ", NamedTextColor.YELLOW)
                    .append(Component.text(
                            "connections=" + counters.connectionCount
                                    + ", refs=" + counters.referenceCount
                                    + ", unhealthy=" + counters.unhealthyCount,
                            NamedTextColor.WHITE
                    )));
        }
    }

    private void sendStatusAggregatesByType(List<ConnectionStatus> statuses, Consumer<Component> messageSink) {
        Map<DatabaseType, AggregateCounters> byType = new EnumMap<>(DatabaseType.class);
        for (ConnectionStatus status : statuses) {
            AggregateCounters counters = byType.computeIfAbsent(status.key().type(), ignored -> new AggregateCounters());
            counters.increment(status.references(), status.health());
        }

        messageSink.accept(Component.text("By backend:", NamedTextColor.GREEN));
        for (DatabaseType type : DatabaseType.values()) {
            AggregateCounters counters = byType.get(type);
            if (counters == null) {
                continue;
            }
            messageSink.accept(Component.text(" - " + type.name() + ": ", NamedTextColor.YELLOW)
                    .append(Component.text(
                            "connections=" + counters.connectionCount
                                    + ", refs=" + counters.referenceCount
                                    + ", unhealthy=" + counters.unhealthyCount,
                            NamedTextColor.WHITE
                    )));
        }
    }

    private void sendConnectionRows(List<ConnectionStatus> statuses, Consumer<Component> messageSink) {
        messageSink.accept(Component.text("Connections:", NamedTextColor.GREEN));
        for (ConnectionStatus status : statuses) {
            DatabaseConnectionKey key = status.key();
            messageSink.accept(Component.text(" - plugin=", NamedTextColor.YELLOW)
                    .append(Component.text(key.pluginName(), NamedTextColor.WHITE))
                    .append(Component.text(", type=", NamedTextColor.YELLOW))
                    .append(Component.text(key.type().name(), NamedTextColor.WHITE))
                    .append(Component.text(", id=", NamedTextColor.YELLOW))
                    .append(Component.text(key.connectionIdentifier(), NamedTextColor.WHITE))
                    .append(Component.text(", refs=", NamedTextColor.YELLOW))
                    .append(Component.text(status.references(), NamedTextColor.WHITE))
                    .append(Component.text(", local=", NamedTextColor.YELLOW))
                    .append(Component.text(status.health().localState().name().toLowerCase(Locale.ROOT), NamedTextColor.WHITE))
                    .append(Component.text(", health=", NamedTextColor.YELLOW))
                    .append(Component.text(status.health().remoteHealth().name().toLowerCase(Locale.ROOT), NamedTextColor.WHITE))
                    .append(Component.text(", health_age=", NamedTextColor.YELLOW))
                    .append(Component.text(formatHealthAge(status.health()), NamedTextColor.WHITE)));
        }
    }

    private List<String> suggestStatusArguments(String[] args) {
        String currentToken = args[args.length - 1];
        if (args.length >= 3) {
            String previousToken = args[args.length - 2].toLowerCase(Locale.ROOT);
            if ("plugin".equals(previousToken)) {
                return suggestPluginNames(currentToken);
            }
            if ("type".equals(previousToken)) {
                return filterCompletions(DATABASE_TYPE_COMPLETIONS, currentToken);
            }
        }
        return filterCompletions(STATUS_OPTION_COMPLETIONS, currentToken);
    }

    private List<String> suggestPluginNames(String partial) {
        try {
            return dataProviderHandler.getActiveDatabases().keySet().stream()
                    .map(DatabaseConnectionKey::pluginName)
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial.toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private static String formatHealthAge(ConnectionHealthSnapshot health) {
        if (health.checkedAt() == null) {
            return "never";
        }
        return Math.max(0, java.time.Duration.between(health.checkedAt(), java.time.Instant.now()).toSeconds()) + "s";
    }

    private static boolean isRootSubcommandVisible(String subcommand, Predicate<String> permissionChecker) {
        return switch (subcommand) {
            case HELP_SUBCOMMAND -> true;
            case STATUS_SUBCOMMAND -> permissionChecker.test(STATUS_PERMISSION);
            case CONFIG_SUBCOMMAND -> permissionChecker.test(CONFIG_PERMISSION);
            case RELOAD_SUBCOMMAND -> permissionChecker.test(RELOAD_PERMISSION);
            default -> false;
        };
    }

    private static List<String> filterCompletions(List<String> completions, String partial) {
        String normalizedPartial = partial.toLowerCase(Locale.ROOT);
        return completions.stream()
                .filter(completion -> completion.startsWith(normalizedPartial))
                .toList();
    }

    private static DatabaseType parseDatabaseType(String rawType) {
        String normalized = rawType.toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return DatabaseType.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Component noPermissionMessage(String permissionNode) {
        return Component.text("Missing permission: " + permissionNode, NamedTextColor.RED);
    }

    private static Component failedOperationMessage(String operation, RuntimeException exception) {
        String reason = exception.getMessage();
        if (reason == null || reason.isBlank()) {
            reason = exception.getClass().getSimpleName();
        }
        return Component.text(operation + " (" + reason + ").", NamedTextColor.RED);
    }

    private record ConnectionStatus(DatabaseConnectionKey key, int references, ConnectionHealthSnapshot health) {
    }

    private record StatusOptions(
            boolean summaryOnly,
            boolean unhealthyOnly,
            String pluginFilter,
            DatabaseType typeFilter
    ) {
    }

    private static final class AggregateCounters {
        private int connectionCount;
        private int referenceCount;
        private int unhealthyCount;

        private void increment(int references, ConnectionHealthSnapshot health) {
            connectionCount++;
            referenceCount += references;
            if (health.remoteHealth() != ConnectionHealthSnapshot.RemoteHealth.HEALTHY) {
                unhealthyCount++;
            }
        }
    }
}
