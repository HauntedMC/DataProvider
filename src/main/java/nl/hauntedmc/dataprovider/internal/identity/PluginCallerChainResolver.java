package nl.hauntedmc.dataprovider.internal.identity;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Resolves the nearest plugin-backed caller from a stack-derived class loader chain.
 */
public final class PluginCallerChainResolver {

    private PluginCallerChainResolver() {
    }

    public static CallerContext resolveNearestMappedCaller(
            List<ClassLoader> callerChain,
            Function<ClassLoader, String> pluginIdResolver,
            String missingCallerMessage
    ) {
        Objects.requireNonNull(callerChain, "Caller chain cannot be null.");
        Objects.requireNonNull(pluginIdResolver, "Plugin ID resolver cannot be null.");
        Objects.requireNonNull(missingCallerMessage, "Missing caller message cannot be null.");

        for (ClassLoader callerLoader : callerChain) {
            if (callerLoader == null) {
                continue;
            }

            String pluginId = pluginIdResolver.apply(callerLoader);
            if (pluginId == null || pluginId.isBlank()) {
                continue;
            }
            return new CallerContext(pluginId, callerLoader);
        }

        throw new SecurityException(missingCallerMessage);
    }
}
