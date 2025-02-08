package nl.hauntedmc.dataprovider.database.messaging;

/**
 * A callback interface for receiving messages.
 */
public interface MessageListener {
    /**
     * Invoked when a new message is received.
     *
     * @param message the message payload
     */
    void onMessage(String message);
}
