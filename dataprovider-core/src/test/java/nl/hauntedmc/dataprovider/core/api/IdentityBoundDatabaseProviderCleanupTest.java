package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityRegistry;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityBoundDatabaseProviderCleanupTest {

    @Test
    void subscriptionAndMessagingShutdownRemainAvailableWhileNormalWorkIsRejected() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = new PluginIdentityRegistry().register(
                "owner",
                getClass().getClassLoader()
        );
        AtomicBoolean disabling = new AtomicBoolean();
        doAnswer(invocation -> {
            if (disabling.get()) {
                throw new SecurityException("new work rejected");
            }
            return null;
        }).when(handler).requireIdentity(identity);

        MessagingDatabaseProvider delegate = mock(MessagingDatabaseProvider.class);
        MessagingDataAccess access = mock(MessagingDataAccess.class);
        Subscription subscription = mock(Subscription.class);
        when(delegate.getDataAccess()).thenReturn(access);
        when(access.subscribe(anyString(), anyString(), any(), any())).thenReturn(subscription);
        when(access.shutdown()).thenReturn(CompletableFuture.completedFuture(null));
        when(subscription.unsubscribe()).thenReturn(CompletableFuture.completedFuture(null));

        MessagingDatabaseProvider bound = (MessagingDatabaseProvider)
                IdentityBoundDatabaseProvider.wrap(handler, identity, delegate);
        MessagingDataAccess boundAccess = bound.getDataAccess();
        Subscription boundSubscription = boundAccess.subscribe(
                "channel",
                "test",
                TestMessage.class,
                ignored -> { }
        );

        disabling.set(true);

        assertThrows(SecurityException.class,
                () -> boundAccess.publish("channel", new TestMessage()));
        assertDoesNotThrow(() -> boundSubscription.unsubscribe().join());
        assertDoesNotThrow(() -> boundAccess.shutdown().join());
        verify(handler).requireIdentityForCleanup(identity);
        verify(subscription).unsubscribe();
        verify(access).shutdown();
    }

    private record TestMessage() implements EventMessage {
        @Override
        public String getType() {
            return "test";
        }
    }
}
