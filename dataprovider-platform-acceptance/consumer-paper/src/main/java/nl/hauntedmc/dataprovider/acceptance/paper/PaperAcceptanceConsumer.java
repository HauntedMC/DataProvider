package nl.hauntedmc.dataprovider.acceptance.paper;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.database.document.model.DocumentQuery;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.api.AbstractEventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;
import nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises a released Paper bundle exclusively through the public DataProvider API. */
public final class PaperAcceptanceConsumer extends JavaPlugin {

    private static final String CONNECTION = "paper";
    private static final String CHANNEL = "dataprovider.platform.acceptance";
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(10);

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                runAcceptance(resolveApi());
                CompletableFuture<Boolean> reload = new CompletableFuture<>();
                Bukkit.getScheduler().runTask(this, () -> reload.complete(Bukkit.dispatchCommand(
                        Bukkit.getConsoleSender(), "dataprovider reload"
                )));
                require(await(reload), "DataProvider reload command was rejected.");
                getLogger().info("DATAPROVIDER_ACCEPTANCE_PASS platform=paper");
            } catch (Exception exception) {
                getLogger().severe("DATAPROVIDER_ACCEPTANCE_FAIL platform=paper cause=" + exception);
            }
        });
    }

    private DataProviderAPI resolveApi() {
        RegisteredServiceProvider<DataProviderAPI> registration = Bukkit.getServicesManager()
                .getRegistration(DataProviderAPI.class);
        if (registration == null) {
            throw new IllegalStateException("DataProviderAPI is not registered by the bundled plugin.");
        }
        return registration.getProvider();
    }

    private static void runAcceptance(DataProviderAPI api) throws Exception {
        RelationalDatabaseProvider mysql = (RelationalDatabaseProvider) api.registerDatabaseOrThrow(
                DatabaseType.MYSQL, CONNECTION
        );
        await(mysql.getDataAccess().executeUpdate(
                "CREATE TABLE IF NOT EXISTS dataprovider_acceptance (id VARCHAR(64) PRIMARY KEY, value_text VARCHAR(64) NOT NULL)"
        ));
        await(mysql.getDataAccess().executeUpdate(
                "INSERT INTO dataprovider_acceptance (id, value_text) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE value_text = VALUES(value_text)",
                "paper", "mysql-ok"
        ));
        require("mysql-ok".equals(await(mysql.getDataAccess().queryForSingleValue(
                "SELECT value_text FROM dataprovider_acceptance WHERE id = ?", "paper"
        ))), "MySQL round trip did not return the inserted value.");

        DocumentDatabaseProvider mongodb = (DocumentDatabaseProvider) api.registerDatabaseOrThrow(
                DatabaseType.MONGODB, CONNECTION
        );
        await(mongodb.getDataAccess().deleteOne("dataprovider_acceptance", new DocumentQuery().eq("_id", "paper")));
        await(mongodb.getDataAccess().insertOne(
                "dataprovider_acceptance", Map.<String, Object>of("_id", "paper", "value", "mongodb-ok")
        ));
        Map<String, Object> document = await(mongodb.getDataAccess().findOne(
                "dataprovider_acceptance", new DocumentQuery().eq("_id", "paper")
        ));
        require("mongodb-ok".equals(document.get("value")), "MongoDB round trip did not return the inserted value.");

        KeyValueDatabaseProvider redis = (KeyValueDatabaseProvider) api.registerDatabaseOrThrow(
                DatabaseType.REDIS, CONNECTION
        );
        await(redis.getDataAccess().setKey("dataprovider:acceptance:paper", "redis-ok"));
        require("redis-ok".equals(await(redis.getDataAccess().getKey("dataprovider:acceptance:paper"))),
                "Redis round trip did not return the inserted value.");

        MessagingDatabaseProvider messaging = (MessagingDatabaseProvider) api.registerDatabaseOrThrow(
                DatabaseType.REDIS_MESSAGING, CONNECTION
        );
        CountDownLatch received = new CountDownLatch(1);
        Subscription subscription = messaging.getDataAccess().subscribe(CHANNEL, AcceptanceMessage.class, message -> {
            if ("paper-message".equals(message.value())) {
                received.countDown();
            }
        });
        awaitActiveSubscription(subscription);
        await(messaging.getDataAccess().publish(CHANNEL, new AcceptanceMessage("paper-message")));
        require(received.await(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                "Redis messaging subscription did not receive its published message.");
        await(subscription.unsubscribe());
        api.unregisterAllDatabasesForPlugin();
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
