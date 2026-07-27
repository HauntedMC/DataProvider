package nl.hauntedmc.dataprovider.core.identity;

/**
 * Platform identity registry SPI.
 *
 * <p>This is a platform integration SPI used for cooperative-plugin access checks. It does not
 * create a security boundary against code installed in the same JVM.</p>
 */
public interface CallerContextResolver {

    /**
     * Legacy caller-resolution hook retained for internal compatibility. Production platform
     * integrations must issue identities at {@code DataProviderAPI.forPlugin(...)} instead.
     *
     * @return resolved caller context
     * @throws SecurityException when the caller cannot be mapped to a platform plugin identity
     */
    CallerContext resolveCaller();

    /**
     * Resolves a plugin caller when one is present, or returns {@code null} for generic worker
     * threads that contain no platform plugin frame.
     */
    default CallerContext resolveCallerIfPresent() {
        try {
            return resolveCaller();
        } catch (SecurityException ignored) {
            return null;
        }
    }

    /**
     * Resolves an active or disabling plugin caller for teardown-only operations.
     * Platform integrations with explicit disabling states should override this method.
     */
    default CallerContext resolveCallerForCleanup() {
        return resolveCaller();
    }

    /** Cleanup-aware equivalent of {@link #resolveCallerIfPresent()}. */
    default CallerContext resolveCallerForCleanupIfPresent() {
        try {
            return resolveCallerForCleanup();
        } catch (SecurityException ignored) {
            return null;
        }
    }

    /**
     * Issues the identity for an explicitly supplied platform plugin object. This is called at
     * the API binding boundary, never while a database handle is being used.
     */
    default PluginIdentity issueIdentity(Object platformPlugin) {
        throw new SecurityException("This platform integration does not support explicit API binding.");
    }

    /** Checks a captured identity without consulting platform APIs. */
    default boolean isIdentityActive(PluginIdentity identity) {
        return false;
    }

    /** Returns the lifecycle state of a captured identity generation. */
    default PluginIdentityState identityState(PluginIdentity identity) {
        return isIdentityActive(identity) ? PluginIdentityState.ACTIVE : PluginIdentityState.INACTIVE;
    }

    /**
     * Determines whether a plugin identifier in a connection access policy is known to the platform.
     * Platform implementations must override this with their plugin manager lookup. The default is
     * fail-closed so custom platform integrations cannot accidentally authorize unknown names.
     *
     * @param pluginId configured plugin identifier
     * @return whether the plugin identifier is known
     */
    default boolean isKnownPlugin(String pluginId) {
        return false;
    }
}
