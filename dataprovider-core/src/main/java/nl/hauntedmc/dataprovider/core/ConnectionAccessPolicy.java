package nl.hauntedmc.dataprovider.core;

import org.spongepowered.configurate.CommentedConfigurationNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Fail-closed ownership and explicit sharing policy for one configured connection. */
final class ConnectionAccessPolicy {

    private final PluginId ownerPlugin;
    private final Set<PluginId> sharedWith;

    private ConnectionAccessPolicy(PluginId ownerPlugin, Set<PluginId> sharedWith) {
        this.ownerPlugin = ownerPlugin;
        this.sharedWith = Set.copyOf(sharedWith);
    }

    static ConnectionAccessPolicy from(CommentedConfigurationNode connection, String location) {
        CommentedConfigurationNode access = connection.node("access");
        if (access.virtual() || access.childrenMap().isEmpty()) {
            throw new InvalidConnectionAccessPolicyException(
                    "Connection " + location + " must declare access.owner_plugin; implicit access is disabled."
            );
        }

        Object ownerRaw = access.node("owner_plugin").raw();
        if (!(ownerRaw instanceof String owner) || owner.isBlank()) {
            throw new InvalidConnectionAccessPolicyException(
                    "Connection " + location + " must declare a non-blank access.owner_plugin."
            );
        }

        Object sharedRaw = access.node("shared_with").raw();
        if (sharedRaw != null && !(sharedRaw instanceof List<?>)) {
            throw new InvalidConnectionAccessPolicyException(
                    "Connection " + location + " access.shared_with must be a list of plugin identifiers."
            );
        }

        PluginId policyOwner = PluginId.of(owner);
        LinkedHashSet<PluginId> sharedPlugins = new LinkedHashSet<>();
        if (sharedRaw instanceof List<?> sharedList) {
            for (Object entry : sharedList) {
                if (!(entry instanceof String sharedPlugin) || sharedPlugin.isBlank()) {
                    throw new InvalidConnectionAccessPolicyException(
                            "Connection " + location + " access.shared_with contains a blank or non-string plugin identifier."
                    );
                }
                PluginId pluginId = PluginId.of(sharedPlugin);
                if (pluginId.equals(policyOwner)) {
                    throw new InvalidConnectionAccessPolicyException(
                            "Connection " + location + " access.shared_with must not repeat access.owner_plugin."
                    );
                }
                if (!sharedPlugins.add(pluginId)) {
                    throw new InvalidConnectionAccessPolicyException(
                            "Connection " + location + " access.shared_with must not contain duplicate plugin identifiers."
                    );
                }
            }
        }
        return new ConnectionAccessPolicy(policyOwner, sharedPlugins);
    }

    void validateConfiguredPlugins(Predicate<String> knownPlugin, String location) {
        Objects.requireNonNull(knownPlugin, "Known-plugin predicate cannot be null.");
        for (PluginId pluginId : allowedPlugins()) {
            if (!knownPlugin.test(pluginId.value())) {
                throw new InvalidConnectionAccessPolicyException(
                        "Connection " + location + " references unknown plugin '" + pluginId.value() + "'."
                );
            }
        }
    }

    void requireAccess(PluginId caller, String location) {
        Objects.requireNonNull(caller, "Caller plugin cannot be null.");
        if (!allowedPlugins().contains(caller)) {
            throw new ConnectionAccessDeniedException(
                    "Plugin '" + caller.value() + "' is not allowed to access connection " + location + "."
            );
        }
    }

    boolean isExplicitlyShared() {
        return !sharedWith.isEmpty();
    }

    PluginId ownerPlugin() {
        return ownerPlugin;
    }

    private Set<PluginId> allowedPlugins() {
        LinkedHashSet<PluginId> allowed = new LinkedHashSet<>(sharedWith);
        allowed.add(ownerPlugin);
        return allowed;
    }
}
