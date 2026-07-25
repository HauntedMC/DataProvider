package nl.hauntedmc.dataprovider.core;

import java.io.Serial;

/** Internal signal that a configured connection access policy is malformed or references an unknown plugin. */
public final class InvalidConnectionAccessPolicyException extends IllegalArgumentException {
    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidConnectionAccessPolicyException(String message) {
        super(message);
    }
}
