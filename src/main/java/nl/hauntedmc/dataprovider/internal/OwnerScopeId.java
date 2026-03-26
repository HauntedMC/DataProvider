package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.api.OwnerScope;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Internal typed representation for ownership scope keys.
 */
record OwnerScopeId(String value) {

    private static final Pattern SCOPE_PATTERN = Pattern.compile("[A-Za-z0-9_.:$-]{1,256}");

    OwnerScopeId {
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

    static OwnerScopeId of(String value) {
        return new OwnerScopeId(value);
    }

    static OwnerScopeId from(OwnerScope ownerScope) {
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        return new OwnerScopeId(ownerScope.value());
    }

    @Override
    public String toString() {
        return value;
    }
}
