package nl.hauntedmc.dataprovider.platform.common.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import nl.hauntedmc.dataprovider.core.resilience.ConnectionHealthSnapshot;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.theme.HauntedMcColor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

/**
 * Platform-neutral DataProvider administration behavior. Platform adapters supply their sender, scheduling, and
 * data-access bridges while this class owns command syntax, permissions, diagnostics, completion, and presentation.
 */
public final class DataProviderAdminCommand {

    public static final String STATUS_PERMISSION = "dataprovider.command.status";
    public static final String CONFIG_PERMISSION = "dataprovider.command.config";
    public static final String RELOAD_PERMISSION = "dataprovider.command.reload";

    private static final int MAX_ROWS_TO_DISPLAY = 20;
    private static final TextColor BRAND = HauntedMcColor.BRAND.textColor();
    private static final TextColor ACCENT = HauntedMcColor.ACCENT.textColor();
    private static final TextColor SUCCESS = HauntedMcColor.SUCCESS.textColor();
    private static final TextColor WARNING = HauntedMcColor.WARNING.textColor();
    private static final TextColor ERROR = HauntedMcColor.ERROR.textColor();
    private static final TextColor MUTED = HauntedMcColor.MUTED.textColor();
    private static final TextColor TEXT = HauntedMcColor.TEXT.textColor();
    private static final Comparator<Connection> CONNECTION_COMPARATOR = Comparator
            .comparing(Connection::pluginName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(connection -> connection.type().name())
            .thenComparing(Connection::identifier, String.CASE_INSENSITIVE_ORDER);

    private final Handler handler;
    private final String commandRoot;

    public DataProviderAdminCommand(Handler handler) {
        this(handler, "dp");
    }

    /**
     * Creates the shared command behavior for a platform command root.
     *
     * @param commandName the registered, slash-free command name used in messages
     */
    public DataProviderAdminCommand(Handler handler, String commandName) {
        this.handler = Objects.requireNonNull(handler, "handler cannot be null");
        String normalizedCommandName = Objects.requireNonNull(commandName, "commandName cannot be null").trim();
        if (normalizedCommandName.isEmpty() || normalizedCommandName.startsWith("/")) {
            throw new IllegalArgumentException("commandName must be a non-blank, slash-free command name");
        }
        this.commandRoot = "/" + normalizedCommandName;
    }

    /** Executes the argument list and sends any result through the supplied platform source. */
    public void execute(String[] args, Source source) {
        Objects.requireNonNull(args, "args cannot be null");
        Objects.requireNonNull(source, "source cannot be null");
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            sendHelp(source);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> executeStatus(source, args);
            case "diagnostics" -> executeDiagnostics(source, args);
            case "connections" -> executeConnections(source, args);
            case "health" -> executeHealth(source, args);
            case "config" -> executeConfig(source, args);
            case "reload" -> executeReload(source, args);
            default -> source.sendMessage(error("Unknown subcommand. Use " + commandRoot + " help for usage."));
        }
    }

    /** Returns permission-aware suggestions for the supplied argument list. */
    public List<String> suggest(String[] args, Source source) {
        Objects.requireNonNull(args, "args cannot be null");
        Objects.requireNonNull(source, "source cannot be null");
        if (args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            return complete(args[0], visibleRootCommands(source));
        }
        if (!source.hasPermission(STATUS_PERMISSION)) {
            return List.of();
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (root) {
                case "status" -> complete(args[1], List.of("summary"));
                case "connections" -> complete(args[1], List.of("unhealthy", "plugin", "type", "page"));
                case "health" -> complete(args[1], List.of("check"));
                default -> List.of();
            };
        }
        if ("connections".equals(root) && args.length == 3) {
            if ("plugin".equalsIgnoreCase(args[1])) {
                try {
                    return complete(args[2], handler.snapshot().connections().stream().map(Connection::pluginName)
                            .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList());
                } catch (RuntimeException ignored) {
                    return List.of();
                }
            }
            if ("type".equalsIgnoreCase(args[1])) {
                return complete(args[2], List.of(DatabaseType.values()).stream()
                        .map(DatabaseType::configKey).toList());
            }
            if ("page".equalsIgnoreCase(args[1])) {
                try {
                    return complete(args[2], pageSuggestions(handler.snapshot().connections().size()));
                } catch (RuntimeException ignored) {
                    return List.of();
                }
            }
        }
        return List.of();
    }

    private void executeStatus(Source source, String[] args) {
        if (!requirePermission(source, STATUS_PERMISSION)) return;
        if (args.length == 1) sendSnapshot(source, "DataProvider status", SnapshotView.STATUS);
        else if (args.length == 2 && "summary".equalsIgnoreCase(args[1])) sendSnapshot(source, "DataProvider summary", SnapshotView.SUMMARY);
        else source.sendMessage(note("Usage: " + commandRoot + " status [summary]"));
    }

    private void executeDiagnostics(Source source, String[] args) {
        if (!requirePermission(source, STATUS_PERMISSION)) return;
        if (args.length != 1) source.sendMessage(note("Usage: " + commandRoot + " diagnostics"));
        else sendSnapshot(source, "DataProvider diagnostics", SnapshotView.DIAGNOSTICS);
    }

    private void executeConnections(Source source, String[] args) {
        if (!requirePermission(source, STATUS_PERMISSION)) return;
        if (args.length == 1) {
            sendSnapshot(source, "Active connections", SnapshotView.CONNECTIONS);
        } else if (args.length == 2 && "unhealthy".equalsIgnoreCase(args[1])) {
            sendFilteredConnections(source, connection -> !connection.healthy());
        } else if (args.length == 3 && "plugin".equalsIgnoreCase(args[1])) {
            sendFilteredConnections(source, connection -> connection.pluginName().equalsIgnoreCase(args[2]));
        } else if (args.length == 3 && "type".equalsIgnoreCase(args[1])) {
            DatabaseType type = parseType(args[2]);
            if (type == null) source.sendMessage(error("Unknown database type. Use Tab for valid values."));
            else sendFilteredConnections(source, connection -> connection.type() == type);
        } else if (args.length == 3 && "page".equalsIgnoreCase(args[1])) {
            Integer page = parsePage(args[2]);
            if (page == null) source.sendMessage(error("Page must be a positive whole number."));
            else sendConnectionPage(source, page);
        } else {
            source.sendMessage(note("Usage: " + commandRoot
                    + " connections [unhealthy|plugin <name>|type <type>|page <number>]"));
        }
    }

    private void executeHealth(Source source, String[] args) {
        if (!requirePermission(source, STATUS_PERMISSION)) return;
        if (args.length == 1) {
            sendSnapshot(source, "Connection health", SnapshotView.HEALTH);
            return;
        }
        if (args.length != 2 || !"check".equalsIgnoreCase(args[1])) {
            source.sendMessage(note("Usage: " + commandRoot + " health [check]"));
            return;
        }
        source.sendMessage(note("Running remote database health checks…"));
        try {
            handler.probeHealth().whenComplete((ignored, failure) -> source.dispatchCompletion(() -> {
                if (failure != null) source.sendMessage(error("Running remote database health checks failed: " + describeFailure(failure)));
                else sendSnapshot(source, "Connection health", SnapshotView.HEALTH);
            }));
        } catch (RuntimeException failure) {
            source.sendMessage(error("Unable to start remote health checks: " + describeFailure(failure)));
        }
    }

    private void executeConfig(Source source, String[] args) {
        if (!requirePermission(source, CONFIG_PERMISSION)) return;
        if (args.length != 1) {
            source.sendMessage(note("Usage: " + commandRoot + " config"));
            return;
        }
        try {
            Config config = handler.config();
            header(source, "DataProvider configuration");
            source.sendMessage(field("ORM schema", config.ormSchemaMode(), ACCENT));
            source.sendMessage(field("Backend types", config.enabledBackendCount() + "/" + DatabaseType.values().length + " enabled", SUCCESS));
            for (DatabaseType type : DatabaseType.values()) {
                boolean enabled = config.databaseTypeStates().getOrDefault(type, true);
                source.sendMessage(field(type.name(), enabled ? "ENABLED" : "DISABLED", enabled ? SUCCESS : MUTED));
            }
        } catch (RuntimeException failure) {
            source.sendMessage(error("Unable to read DataProvider config: " + describeFailure(failure)));
        }
    }

    private void executeReload(Source source, String[] args) {
        if (!requirePermission(source, RELOAD_PERMISSION)) return;
        if (args.length != 1) {
            source.sendMessage(note("Usage: " + commandRoot + " reload"));
            return;
        }
        try {
            handler.reload();
            source.sendMessage(success("Reloaded the validated DataProvider configuration."));
            source.sendMessage(note("Existing connections retain their current settings until reconnected."));
        } catch (RuntimeException failure) {
            source.sendMessage(error("DataProvider config reload failed: " + describeFailure(failure)));
        }
    }

    private void sendHelp(Source source) {
        header(source, "DataProvider administration");
        boolean anyVisible = false;
        if (source.hasPermission(STATUS_PERMISSION)) {
            anyVisible = true;
            source.sendMessage(command(commandRoot + " status [summary]", "live connection and backend overview"));
            source.sendMessage(command(commandRoot + " diagnostics", "runtime, circuit, and recovery diagnostics"));
            source.sendMessage(command(commandRoot
                    + " connections [unhealthy|plugin <name>|type <type>|page <number>]", "inspect logical connections"));
            source.sendMessage(command(commandRoot + " health [check]", "cached health or force a remote health probe"));
        }
        if (source.hasPermission(CONFIG_PERMISSION)) {
            anyVisible = true;
            source.sendMessage(command(commandRoot + " config", "configured backend switches and ORM schema mode"));
        }
        if (source.hasPermission(RELOAD_PERMISSION)) {
            anyVisible = true;
            source.sendMessage(command(commandRoot + " reload", "reload validated configuration files"));
        }
        if (!anyVisible) {
            source.sendMessage(note("No administrative subcommands are available to this sender."));
        }
        source.sendMessage(note("Permissions: status=" + STATUS_PERMISSION + ", config=" + CONFIG_PERMISSION
                + ", reload=" + RELOAD_PERMISSION));
    }

    private void sendSnapshot(Source source, String title, SnapshotView view) {
        try {
            Snapshot snapshot = handler.snapshot();
            List<Connection> connections = snapshot.connections().stream().sorted(CONNECTION_COMPARATOR).toList();
            long healthy = connections.stream().filter(Connection::healthy).count();
            long unhealthy = connections.size() - healthy;
            int references = connections.stream().mapToInt(Connection::references).sum();
            long plugins = connections.stream().map(Connection::pluginName).distinct().count();
            header(source, title);
            source.sendMessage(field("Connections", connections.size() + " logical · " + references + " reference(s)", ACCENT));
            source.sendMessage(field("Health", healthy + " healthy · " + unhealthy + " needs attention", unhealthy == 0 ? SUCCESS : WARNING));
            source.sendMessage(field("Consumers", plugins + " plugin(s) · " + snapshot.enabledBackendCount() + "/"
                    + DatabaseType.values().length + " backend type(s) enabled", ACCENT));
            source.sendMessage(field("ORM schema", snapshot.ormSchemaMode(), TEXT));
            if (view == SnapshotView.STATUS || view == SnapshotView.SUMMARY) {
                if (unhealthy > 0) source.sendMessage(note("Use " + commandRoot
                        + " health check to refresh probes, or " + commandRoot
                        + " connections unhealthy for details."));
            } else if (view == SnapshotView.HEALTH) {
                sendConnectionRows(source, connections.stream().filter(connection -> !connection.healthy()).toList(), true);
            } else {
                sendConnectionRows(source, connections, view == SnapshotView.DIAGNOSTICS);
            }
        } catch (RuntimeException failure) {
            source.sendMessage(error("Unable to collect " + title.toLowerCase(Locale.ROOT) + ": " + describeFailure(failure)));
        }
    }

    private void sendConnectionPage(Source source, int page) {
        try {
            List<Connection> connections = handler.snapshot().connections().stream().sorted(CONNECTION_COMPARATOR).toList();
            header(source, "Active connections");
            if (connections.isEmpty()) {
                source.sendMessage(note("No active connections are registered."));
                return;
            }
            int pageCount = pageCount(connections.size());
            if (page > pageCount) {
                source.sendMessage(note("Page " + page + " does not exist. Available pages: 1-" + pageCount + "."));
                return;
            }
            int fromIndex = (page - 1) * MAX_ROWS_TO_DISPLAY;
            int toIndex = Math.min(connections.size(), fromIndex + MAX_ROWS_TO_DISPLAY);
            source.sendMessage(field("Page", page + "/" + pageCount + " · " + connections.size() + " connection(s)", ACCENT));
            connections.subList(fromIndex, toIndex).forEach(connection -> sendConnectionRow(source, connection, true));
        } catch (RuntimeException failure) {
            source.sendMessage(error("Unable to inspect connection page: " + describeFailure(failure)));
        }
    }

    private void sendFilteredConnections(Source source, Predicate<Connection> filter) {
        try {
            List<Connection> connections = handler.snapshot().connections().stream().filter(filter).sorted(CONNECTION_COMPARATOR).toList();
            header(source, "Filtered connections");
            if (connections.isEmpty()) {
                source.sendMessage(note("No active connections match this filter."));
                return;
            }
            source.sendMessage(field("Matches", Integer.toString(connections.size()), ACCENT));
            sendConnectionRows(source, connections, true);
        } catch (RuntimeException failure) {
            source.sendMessage(error("Unable to inspect connections: " + describeFailure(failure)));
        }
    }

    private static void sendConnectionRows(Source source, List<Connection> connections, boolean detailed) {
        if (connections.isEmpty()) {
            source.sendMessage(note("No active connections match this view."));
            return;
        }
        for (Connection connection : connections.subList(0, Math.min(connections.size(), MAX_ROWS_TO_DISPLAY))) {
            sendConnectionRow(source, connection, detailed);
        }
        if (connections.size() > MAX_ROWS_TO_DISPLAY) {
            source.sendMessage(note("… and " + (connections.size() - MAX_ROWS_TO_DISPLAY) + " more."));
        }
    }

    private static void sendConnectionRow(Source source, Connection connection, boolean detailed) {
        ConnectionHealthSnapshot health = connection.health();
        source.sendMessage(field(connection.pluginName() + " / " + connection.identifier(), connection.type().name()
                + " · refs=" + connection.references() + " · " + health.remoteHealth() + " · " + health.runtimeHealth(), healthColor(health)));
        if (detailed) {
            source.sendMessage(field("  resilience", "local=" + health.localState() + " · circuit=" + health.circuit()
                    + " · failures=" + health.consecutiveFailures() + " · reconnects=" + health.reconnectAttempts()
                    + " · checked " + formatAge(health.checkedAt()), MUTED));
            if (health.lastFailureSummary() != null && !health.lastFailureSummary().isBlank()) {
                source.sendMessage(field("  last failure", health.lastFailureSummary(), ERROR));
            }
        }
    }

    private static List<String> visibleRootCommands(Source source) {
        List<String> commands = new ArrayList<>(List.of("help"));
        if (source.hasPermission(STATUS_PERMISSION)) commands.addAll(List.of("status", "diagnostics", "connections", "health"));
        if (source.hasPermission(CONFIG_PERMISSION)) commands.add("config");
        if (source.hasPermission(RELOAD_PERMISSION)) commands.add("reload");
        return commands;
    }

    /** Returns whether a sender should receive this administrative command root at all. */
    public static boolean canUseRootCommand(Predicate<String> permissionChecker) {
        Objects.requireNonNull(permissionChecker, "permissionChecker cannot be null");
        return permissionChecker.test(STATUS_PERMISSION)
                || permissionChecker.test(CONFIG_PERMISSION)
                || permissionChecker.test(RELOAD_PERMISSION);
    }

    private static List<String> complete(String prefix, List<String> candidates) {
        return candidates.stream().filter(candidate -> candidate.regionMatches(true, 0, prefix, 0, prefix.length())).toList();
    }

    private static List<String> pageSuggestions(int connectionCount) {
        int pages = pageCount(connectionCount);
        List<String> suggestions = new ArrayList<>(pages);
        for (int page = 1; page <= pages; page++) {
            suggestions.add(Integer.toString(page));
        }
        return suggestions;
    }

    private static int pageCount(int itemCount) {
        return Math.max(1, Math.ceilDiv(itemCount, MAX_ROWS_TO_DISPLAY));
    }

    private static boolean requirePermission(Source source, String permission) {
        if (source.hasPermission(permission)) return true;
        source.sendMessage(error("Missing permission: " + permission));
        return false;
    }

    private static DatabaseType parseType(String value) {
        return DatabaseType.parse(value).orElse(null);
    }

    private static Integer parsePage(String value) {
        try {
            int page = Integer.parseInt(value);
            return page > 0 ? page : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static TextColor healthColor(ConnectionHealthSnapshot health) {
        if (health.remoteHealth() == ConnectionHealthSnapshot.RemoteHealth.HEALTHY
                && health.runtimeHealth() == ConnectionHealthSnapshot.RuntimeHealth.HEALTHY
                && health.circuit() == ConnectionHealthSnapshot.Circuit.CLOSED) return SUCCESS;
        if (health.remoteHealth() == ConnectionHealthSnapshot.RemoteHealth.UNHEALTHY
                || health.remoteHealth() == ConnectionHealthSnapshot.RemoteHealth.ERROR
                || health.runtimeHealth() == ConnectionHealthSnapshot.RuntimeHealth.UNAVAILABLE
                || health.circuit() == ConnectionHealthSnapshot.Circuit.OPEN) return ERROR;
        return WARNING;
    }

    private static String formatAge(Instant checkedAt) {
        if (checkedAt == null) return "never";
        long seconds = Math.max(0L, Duration.between(checkedAt, Instant.now()).toSeconds());
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    private static String describeFailure(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static void header(Source source, String title) {
        source.sendMessage(Component.text("◆ ", BRAND).append(Component.text(title, BRAND)).append(Component.text(" ─────────────────", MUTED)));
    }

    private static Component field(String label, String value, TextColor color) {
        return Component.text("  " + label, TEXT).append(Component.text("  »  ", MUTED)).append(Component.text(value, color));
    }

    private static Component command(String command, String description) {
        return Component.text("  " + command, ACCENT).append(Component.text("  —  " + description, MUTED));
    }

    private static Component note(String message) { return Component.text("  " + message, MUTED); }
    private static Component success(String message) { return Component.text("✓ " + message, SUCCESS); }
    private static Component error(String message) { return Component.text("✕ " + message, ERROR); }

    public interface Handler {
        Snapshot snapshot();
        Config config();
        CompletionStage<Void> probeHealth();
        void reload();
    }

    public interface Source {
        boolean hasPermission(String permission);
        void sendMessage(Component message);
        /** Dispatches asynchronous command output on the thread required by the platform. */
        void dispatchCompletion(Runnable task);
    }

    public record Snapshot(List<Connection> connections, Map<DatabaseType, Boolean> databaseTypeStates, String ormSchemaMode) {
        public Snapshot {
            connections = List.copyOf(Objects.requireNonNull(connections, "connections cannot be null"));
            databaseTypeStates = Map.copyOf(Objects.requireNonNull(databaseTypeStates, "databaseTypeStates cannot be null"));
            ormSchemaMode = Objects.requireNonNull(ormSchemaMode, "ormSchemaMode cannot be null");
        }
        public long enabledBackendCount() { return databaseTypeStates.values().stream().filter(Boolean::booleanValue).count(); }
    }

    public record Connection(String pluginName, DatabaseType type, String identifier, int references, ConnectionHealthSnapshot health) {
        public Connection {
            if (pluginName == null || pluginName.isBlank() || identifier == null || identifier.isBlank()) throw new IllegalArgumentException("connection text cannot be blank");
            type = Objects.requireNonNull(type, "type cannot be null");
            if (references < 1) throw new IllegalArgumentException("references must be positive");
            health = Objects.requireNonNull(health, "health cannot be null");
        }
        public boolean healthy() {
            return health.remoteHealth() == ConnectionHealthSnapshot.RemoteHealth.HEALTHY
                    && health.runtimeHealth() == ConnectionHealthSnapshot.RuntimeHealth.HEALTHY
                    && health.circuit() == ConnectionHealthSnapshot.Circuit.CLOSED;
        }
    }

    public record Config(Map<DatabaseType, Boolean> databaseTypeStates, String ormSchemaMode) {
        public Config {
            databaseTypeStates = Map.copyOf(Objects.requireNonNull(databaseTypeStates, "databaseTypeStates cannot be null"));
            ormSchemaMode = Objects.requireNonNull(ormSchemaMode, "ormSchemaMode cannot be null");
        }
        public long enabledBackendCount() { return databaseTypeStates.values().stream().filter(Boolean::booleanValue).count(); }
    }

    private enum SnapshotView { STATUS, SUMMARY, DIAGNOSTICS, CONNECTIONS, HEALTH }
}
