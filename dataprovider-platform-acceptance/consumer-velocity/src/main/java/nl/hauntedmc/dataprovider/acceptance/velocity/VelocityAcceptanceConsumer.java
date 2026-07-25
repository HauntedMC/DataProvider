package nl.hauntedmc.dataprovider.acceptance.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.api.DataProviderApiSupplier;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.database.document.model.DocumentQuery;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.api.AbstractEventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;
import nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises a released Velocity bundle exclusively through the public DataProvider API. */
@Plugin(
        id = "dataprovider-acceptance",
        name = "DataProvider Acceptance",
        version = "${project.version}",
        dependencies = @Dependency(id = "dataprovider")
)
public final class VelocityAcceptanceConsumer {

    private static final String CONNECTION = "velocity";
    private static final String CHANNEL = "dataprovider.platform.acceptance";
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(10);

    private final ProxyServer proxy;
    private final Logger logger;

    @Inject
    public VelocityAcceptanceConsumer(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        proxy.getScheduler().buildTask(this, () -> {
            Set<String> databaseThreadsBefore = databaseThreadNames();
            DataProviderAPI api = null;
            boolean passed = false;
            try {
                api = resolveApi();
                KeyValueDatabaseProvider redis = runAcceptance(api);
                require(await(proxy.getCommandManager().executeAsync(
                        proxy.getConsoleCommandSource(), "dataprovider reload"
                )), "DataProvider reload command was rejected.");
                require("redis-ok".equals(await(redis.getDataAccess().getKey("dataprovider:acceptance:velocity"))),
                        "Registered Redis provider did not remain usable after configuration reload.");
                passed = true;
            } catch (Exception exception) {
                logger.error("DATAPROVIDER_ACCEPTANCE_FAIL platform=velocity", exception);
            } finally {
                if (api != null) {
                    try {
                        api.unregisterAllDatabasesForPlugin();
                        awaitDatabaseThreadsToReturn(databaseThreadsBefore);
                    } catch (Exception exception) {
                        passed = false;
                        logger.error("DATAPROVIDER_ACCEPTANCE_FAIL platform=velocity cleanup", exception);
                    }
                }
            }
            if (passed) {
                logger.info("DATAPROVIDER_ACCEPTANCE_PASS platform=velocity");
            }
        }).schedule();
    }

    private DataProviderAPI resolveApi() {
        Object plugin = proxy.getPluginManager().getPlugin("dataprovider")
                .flatMap(container -> container.getInstance())
                .orElseThrow(() -> new IllegalStateException("DataProvider plugin instance is unavailable."));
        if (!(plugin instanceof DataProviderApiSupplier supplier)) {
            throw new IllegalStateException("DataProvider plugin does not expose DataProviderApiSupplier.");
        }
        return supplier.dataProviderApi();
    }

    private static KeyValueDatabaseProvider runAcceptance(DataProviderAPI api) throws Exception {
        RelationalDatabaseProvider mysql = (RelationalDatabaseProvider) api.registerDatabaseOrThrow(
                DatabaseType.MYSQL, CONNECTION
        );
        await(mysql.getDataAccess().executeUpdate(
                "CREATE TABLE IF NOT EXISTS dataprovider_acceptance (id VARCHAR(64) PRIMARY KEY, value_text VARCHAR(64) NOT NULL)"
        ));
        await(mysql.getDataAccess().executeUpdate(
                "INSERT INTO dataprovider_acceptance (id, value_text) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE value_text = VALUES(value_text)",
                "velocity", "mysql-ok"
        ));
        require("mysql-ok".equals(await(mysql.getDataAccess().queryForSingleValue(
                "SELECT value_text FROM dataprovider_acceptance WHERE id = ?", "velocity"
        ))), "MySQL round trip did not return the inserted value.");

        DocumentDatabaseProvider mongodb = (DocumentDatabaseProvider) api.registerDatabaseOrThrow(
                DatabaseType.MONGODB, CONNECTION
        );
        await(mongodb.getDataAccess().deleteOne("dataprovider_acceptance", new DocumentQuery().eq("_id", "velocity")));
        await(mongodb.getDataAccess().insertOne(
                "dataprovider_acceptance", Map.<String, Object>of("_id", "velocity", "value", "mongodb-ok")
        ));
        Map<String, Object> document = await(mongodb.getDataAccess().findOne(
                "dataprovider_acceptance", new DocumentQuery().eq("_id", "velocity")
        ));
        require("mongodb-ok".equals(document.get("value")), "MongoDB round trip did not return the inserted value.");

        KeyValueDatabaseProvider redis = (KeyValueDatabaseProvider) api.registerDatabaseOrThrow(
                DatabaseType.REDIS, CONNECTION
        );
        await(redis.getDataAccess().setKey("dataprovider:acceptance:velocity", "redis-ok"));
        require("redis-ok".equals(await(redis.getDataAccess().getKey("dataprovider:acceptance:velocity"))),
                "Redis round trip did not return the inserted value.");

        MessagingDatabaseProvider messaging = (MessagingDatabaseProvider) api.registerDatabaseOrThrow(
                DatabaseType.REDIS_MESSAGING, CONNECTION
        );
        CountDownLatch received = new CountDownLatch(1);
        Subscription subscription = messaging.getDataAccess().subscribe(CHANNEL, AcceptanceMessage.class, message -> {
            if ("velocity-message".equals(message.value())) {
                received.countDown();
            }
        });
        try {
            awaitActiveSubscription(subscription);
            await(messaging.getDataAccess().publish(CHANNEL, new AcceptanceMessage("velocity-message")));
            require(received.await(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "Redis messaging subscription did not receive its published message.");
        } finally {
            await(subscription.unsubscribe());
            require(subscription.state() == SubscriptionState.CLOSED,
                    "Redis messaging subscription did not close cleanly.");
        }
        return redis;
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void awaitActiveSubscription(Subscription subscription) throws InterruptedException {
        long deadline = System.nanoTime() + OPERATION_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (subscription.state() == SubscriptionState.ACTIVE) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new IllegalStateException("Redis messaging subscription did not become active.");
    }

    private static Set<String> databaseThreadNames() {
        Set<String> names = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && isDatabaseThread(thread.getName())) {
                names.add(thread.getName());
            }
        }
        return names;
    }

    private static void awaitDatabaseThreadsToReturn(Set<String> baseline) throws InterruptedException {
        long deadline = System.nanoTime() + OPERATION_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Set<String> leaked = databaseThreadNames();
            leaked.removeAll(baseline);
            if (leaked.isEmpty()) {
                return;
            }
            Thread.sleep(25L);
        }
        Set<String> leaked = databaseThreadNames();
        leaked.removeAll(baseline);
        throw new IllegalStateException("Database worker threads remained after provider cleanup: " + leaked);
    }

    private static boolean isDatabaseThread(String name) {
        return name.startsWith("HikariPool-")
                || name.startsWith("redis-sub-")
                || name.startsWith("cluster-")
                || name.startsWith("MaintenanceTimer");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** Public no-argument shape is intentional: the messaging codec reconstructs this payload reflectively. */
    public static final class AcceptanceMessage extends AbstractEventMessage {
        private String value;

        public AcceptanceMessage() {
            this("");
        }

        private AcceptanceMessage(String value) {
            super("dataprovider.platform.acceptance");
            this.value = Objects.requireNonNull(value, "Value cannot be null.");
        }

        public String value() {
            return value;
        }
    }
}
