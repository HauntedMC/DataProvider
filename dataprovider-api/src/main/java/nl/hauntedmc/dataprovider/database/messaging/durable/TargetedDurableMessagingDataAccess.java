package nl.hauntedmc.dataprovider.database.messaging.durable;

import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Recipient-addressed durable messaging.
 *
 * <p>Each recipient owns a distinct stream. Its consumers therefore compete only with another
 * process claiming that same logical recipient, never with unrelated proxy or backend instances.</p>
 */
public interface TargetedDurableMessagingDataAccess {

    <T extends EventMessage> CompletableFuture<PublishedDurableEvent> publishTo(
            String recipient,
            DurableEvent<T> event
    );

    <T extends EventMessage> CompletableFuture<Map<String, PublishedDurableEvent>> fanOut(
            Collection<String> recipients,
            DurableEvent<T> event
    );

    <T extends EventMessage> DurableSubscription consumeInbox(
            String recipient,
            String consumer,
            String messageType,
            Class<T> type,
            Consumer<DurableDelivery<T>> handler
    );

    static TargetedDurableMessagingDataAccess create(DurableMessagingDataAccess delegate, String namespace) {
        return new Default(delegate, namespace);
    }

    /** Default address mapping shared by every provider implementation. */
    final class Default implements TargetedDurableMessagingDataAccess {
        private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
        private final DurableMessagingDataAccess delegate;
        private final String namespace;

        private Default(DurableMessagingDataAccess delegate, String namespace) {
            this.delegate = Objects.requireNonNull(delegate, "Delegate cannot be null.");
            this.namespace = requireName(namespace, "Namespace");
        }

        @Override
        public <T extends EventMessage> CompletableFuture<PublishedDurableEvent> publishTo(
                String recipient, DurableEvent<T> event
        ) {
            return delegate.publish(stream(recipient), Objects.requireNonNull(event, "Event cannot be null."));
        }

        @Override
        public <T extends EventMessage> CompletableFuture<Map<String, PublishedDurableEvent>> fanOut(
                Collection<String> recipients, DurableEvent<T> event
        ) {
            Objects.requireNonNull(recipients, "Recipients cannot be null.");
            Objects.requireNonNull(event, "Event cannot be null.");
            java.util.LinkedHashSet<String> normalizedRecipients = new java.util.LinkedHashSet<>();
            for (String recipient : recipients) {
                String normalized = requireName(recipient, "Recipient");
                if (!normalizedRecipients.add(normalized)) {
                    throw new IllegalArgumentException("Recipients cannot contain duplicates: " + normalized);
                }
            }
            Map<String, CompletableFuture<PublishedDurableEvent>> publications = new LinkedHashMap<>();
            for (String normalized : normalizedRecipients) {
                publications.put(normalized, publishTo(normalized, event));
            }
            return CompletableFuture.allOf(publications.values().toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> {
                        Map<String, PublishedDurableEvent> results = new LinkedHashMap<>();
                        publications.forEach((recipient, future) -> results.put(recipient, future.join()));
                        return Map.copyOf(results);
                    });
        }

        @Override
        public <T extends EventMessage> DurableSubscription consumeInbox(
                String recipient,
                String consumer,
                String messageType,
                Class<T> type,
                Consumer<DurableDelivery<T>> handler
        ) {
            String normalized = requireName(recipient, "Recipient");
            return delegate.consume(stream(normalized), namespace + ".inbox", requireName(consumer, "Consumer"),
                    messageType, type, handler);
        }

        private String stream(String recipient) {
            return namespace + ".inbox." + requireName(recipient, "Recipient");
        }

        private static String requireName(String value, String field) {
            if (value == null || !NAME.matcher(value.trim()).matches()) {
                throw new IllegalArgumentException(field + " must match " + NAME.pattern());
            }
            return value.trim();
        }
    }
}
