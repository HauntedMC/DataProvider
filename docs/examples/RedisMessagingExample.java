import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.api.AbstractEventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Example: Redis messaging publish/subscribe workflow.
 */
public final class RedisMessagingExample {

    private static final String STAFF_CHAT_CHANNEL = "proxy.staffchat.message";
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10L;

    private MessagingDataAccess bus;
    private Subscription subscription;

    public void onEnable(DataProviderAPI api, Consumer<StaffChatMessage> messageHandler) {
        MessagingDatabaseProvider provider = api.registerDatabaseOrThrow(
                DatabaseType.REDIS_MESSAGING, "example", MessagingDatabaseProvider.class
        );
        try {
            bus = provider.getDataAccess();
            subscription = bus.subscribe(STAFF_CHAT_CHANNEL, "staffchat", StaffChatMessage.class,
                    Objects.requireNonNull(messageHandler, "Message handler cannot be null."));
        } catch (RuntimeException exception) {
            bus = null;
            api.unregisterDatabase(DatabaseType.REDIS_MESSAGING, "example");
            throw exception;
        }
    }

    public CompletableFuture<Void> publishMessage(String sender, String server, String message) {
        return dataAccess().publish(STAFF_CHAT_CHANNEL, new StaffChatMessage(sender, server, message));
    }

    public void onDisable(DataProviderAPI api) {
        Subscription current = subscription;
        subscription = null;
        bus = null;
        try {
            if (current != null) {
                awaitShutdown(current.unsubscribe());
            }
        } finally {
            api.unregisterDatabase(DatabaseType.REDIS_MESSAGING, "example");
        }
    }

    private MessagingDataAccess dataAccess() {
        if (bus == null) {
            throw new IllegalStateException("Redis messaging is not registered.");
        }
        return bus;
    }

    private static void awaitShutdown(CompletableFuture<Void> shutdown) {
        try {
            shutdown.get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping Redis messaging.", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Redis messaging did not stop cleanly.", failure);
        }
    }

    public static final class StaffChatMessage extends AbstractEventMessage {
        private String sender;
        private String server;
        private String message;

        /** Required for reflective message deserialization. */
        public StaffChatMessage() {
            super("staffchat");
        }

        public StaffChatMessage(String sender, String server, String message) {
            this();
            this.sender = Objects.requireNonNull(sender, "Sender cannot be null.");
            this.server = Objects.requireNonNull(server, "Server cannot be null.");
            this.message = Objects.requireNonNull(message, "Message cannot be null.");
        }

        public String getSender() {
            return sender;
        }

        public String getServer() {
            return server;
        }

        public String getMessage() {
            return message;
        }
    }
}
