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
