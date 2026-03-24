import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.api.AbstractEventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;

import java.util.Optional;

/**
 * Example: Redis messaging publish/subscribe workflow.
 */
public final class RedisMessagingExample {

    private MessagingDataAccess bus;
    private Subscription subscription;

    public void onEnable(DataProviderAPI api) {
        Optional<MessagingDataAccess> optBus = api.registerDataAccess(
                DatabaseType.REDIS_MESSAGING,
                "default",
                MessagingDataAccess.class
        );

        if (optBus.isEmpty()) {
            return;
        }
        bus = optBus.get();

        subscription = bus.subscribe("proxy.staffchat.message", StaffChatMessage.class, msg -> {
            System.out.println("[" + msg.getServer() + "] " + msg.getSender() + ": " + msg.getMessage());
        });
    }

    public void publishMessage(String sender, String server, String message) {
        if (bus == null) {
            return;
        }
        bus.publish("proxy.staffchat.message", new StaffChatMessage(sender, server, message));
    }

    public void onDisable(DataProviderAPI api) {
        if (subscription != null) {
            subscription.unsubscribe();
            subscription = null;
        }
        if (bus != null) {
            bus.shutdown();
            bus = null;
        }
        api.unregisterDatabase(DatabaseType.REDIS_MESSAGING, "default");
    }

    public static final class StaffChatMessage extends AbstractEventMessage {
        private final String sender;
        private final String server;
        private final String message;

        @SuppressWarnings("unused")
        private StaffChatMessage() {
            super("staffchat");
            this.sender = null;
            this.server = null;
            this.message = null;
        }

        public StaffChatMessage(String sender, String server, String message) {
            super("staffchat");
            this.sender = sender;
            this.server = server;
            this.message = message;
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
