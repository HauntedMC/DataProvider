package nl.hauntedmc.dataprovider.internal;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Internal typed representation for connection identifier keys.
 */
record ConnectionIdentifier(String value) {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    ConnectionIdentifier {
        Objects.requireNonNull(value, "Connection identifier cannot be null.");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Connection identifier cannot be blank.");
        }
        if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Connection identifier contains unsupported characters.");
        }
        value = normalized;
    }

    static ConnectionIdentifier of(String value) {
        return new ConnectionIdentifier(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
