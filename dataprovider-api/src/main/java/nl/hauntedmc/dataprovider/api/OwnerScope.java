package nl.hauntedmc.dataprovider.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Typed owner-scope value for optional scoped lifecycle APIs.
 */
public record OwnerScope(String value) {

    private static final Pattern SCOPE_PATTERN = Pattern.compile("[A-Za-z0-9_.:$-]{1,256}");

    public OwnerScope {
        Objects.requireNonNull(value, "Owner scope cannot be null.");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Owner scope cannot be blank.");
        }
        if (!SCOPE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Owner scope contains unsupported characters.");
        }
        value = normalized;
    }

    public static OwnerScope of(String value) {
        return new OwnerScope(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
