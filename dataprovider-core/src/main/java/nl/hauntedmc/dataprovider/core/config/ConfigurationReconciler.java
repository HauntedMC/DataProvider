package nl.hauntedmc.dataprovider.core.config;

import org.spongepowered.configurate.CommentedConfigurationNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reconciles a configuration node with the keys and structure of a shipped template.
 * Existing scalar values remain administrator-controlled; only absent keys, obsolete keys,
 * and structurally invalid container nodes are changed.
 */
public final class ConfigurationReconciler {

    private ConfigurationReconciler() {
    }

    /**
     * Makes {@code configured} conform to the template's key structure without replacing
     * administrator-provided values.
     *
     * @return whether the configured node was changed
     */
    public static boolean reconcileSchema(
            CommentedConfigurationNode configured,
            CommentedConfigurationNode template
    ) {
        if (!template.isMap()) {
            if (configured.virtual()) {
                configured.from(template);
                return true;
            }
            return false;
        }

        if (!configured.isMap()) {
            configured.from(template);
            return true;
        }

        boolean changed = false;
        Set<Object> templateKeys = new LinkedHashSet<>(template.childrenMap().keySet());
        for (Object configuredKey : new ArrayList<>(configured.childrenMap().keySet())) {
            if (!templateKeys.contains(configuredKey)) {
                configured.removeChild(configuredKey);
                changed = true;
            }
        }
        for (Object key : templateKeys) {
            changed |= reconcileSchema(configured.node(key), template.node(key));
        }
        return changed;
    }
}
