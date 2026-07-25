package nl.hauntedmc.dataprovider.core;

/** Internal signal that a resolved plugin lacks permission for a configured connection. */
public final class ConnectionAccessDeniedException extends SecurityException {
    private static final long serialVersionUID = 1L;

    public ConnectionAccessDeniedException(String message) {
        super(message);
    }
}
