package nl.hauntedmc.dataprovider.internal.identity;

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
}
