package nl.hauntedmc.dataprovider.core.identity;

/**
 * Resolves the active caller identity from the platform runtime.
 */
public interface CallerContextResolver {

    /**
     * Resolve caller identity for the current API invocation.
     *
     * @return resolved caller context
     * @throws SecurityException when the caller cannot be mapped to a platform plugin identity
     */
    CallerContext resolveCaller();

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
