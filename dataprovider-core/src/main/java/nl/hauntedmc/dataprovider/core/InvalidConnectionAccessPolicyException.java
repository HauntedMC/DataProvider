package nl.hauntedmc.dataprovider.core;

/** Internal signal that a configured connection access policy is malformed or references an unknown plugin. */
public final class InvalidConnectionAccessPolicyException extends IllegalArgumentException {
    public InvalidConnectionAccessPolicyException(String message) {
        super(message);
    }
}
