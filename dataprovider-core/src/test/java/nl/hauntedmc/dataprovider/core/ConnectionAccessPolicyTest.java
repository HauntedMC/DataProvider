package nl.hauntedmc.dataprovider.core;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionAccessPolicyTest {

    private static final String LOCATION = "mysql.yml/shared";

    @Test
    void rejectsMissingOrBlankOwnerDeclarations() {
        CommentedConfigurationNode missingAccess = CommentedConfigurationNode.root();
        CommentedConfigurationNode missingOwner = CommentedConfigurationNode.root();
        missingOwner.node("access", "shared_with").raw(List.of("friend"));
        CommentedConfigurationNode blankOwner = policyNode(" ", List.of());
        CommentedConfigurationNode nonStringOwner = policyNode(42, List.of());

        assertThrows(InvalidConnectionAccessPolicyException.class,
                () -> ConnectionAccessPolicy.from(missingAccess, LOCATION));
        assertThrows(InvalidConnectionAccessPolicyException.class,
                () -> ConnectionAccessPolicy.from(missingOwner, LOCATION));
        assertThrows(InvalidConnectionAccessPolicyException.class,
                () -> ConnectionAccessPolicy.from(blankOwner, LOCATION));
        assertThrows(InvalidConnectionAccessPolicyException.class,
                () -> ConnectionAccessPolicy.from(nonStringOwner, LOCATION));
    }

    @Test
    void normalizesOwnerAndAllowsOwnerAccess() {
        ConnectionAccessPolicy policy = ConnectionAccessPolicy.from(
                policyNode("  OwNeR.Plugin  ", List.of()), LOCATION);

        assertEquals(PluginId.of("owner.plugin"), policy.ownerPlugin());
        assertFalse(policy.isExplicitlyShared());
        policy.requireAccess(PluginId.of("OWNER.PLUGIN"), LOCATION);
    }

    @Test
    void acceptsDistinctSharedPluginsAndDeniesEveryoneElse() {
        ConnectionAccessPolicy policy = ConnectionAccessPolicy.from(
                policyNode("owner", List.of("friend-one", "Friend.Two")), LOCATION);

        assertTrue(policy.isExplicitlyShared());
        policy.requireAccess(PluginId.of("owner"), LOCATION);
        policy.requireAccess(PluginId.of("friend-one"), LOCATION);
        policy.requireAccess(PluginId.of("friend.two"), LOCATION);
        assertThrows(ConnectionAccessDeniedException.class,
                () -> policy.requireAccess(PluginId.of("outsider"), LOCATION));
        assertThrows(NullPointerException.class, () -> policy.requireAccess(null, LOCATION));
    }

    @Test
    void rejectsMalformedSharedWithDeclarations() {
        CommentedConfigurationNode nonList = policyNode("owner", null);
        nonList.node("access", "shared_with").raw("friend");

        assertThrows(InvalidConnectionAccessPolicyException.class,
                () -> ConnectionAccessPolicy.from(nonList, LOCATION));
        assertThrows(InvalidConnectionAccessPolicyException.class,
                () -> ConnectionAccessPolicy.from(policyNode("owner", List.of("")), LOCATION));
        assertThrows(InvalidConnectionAccessPolicyException.class,
                () -> ConnectionAccessPolicy.from(policyNode("owner", List.of(12)), LOCATION));
    }

    @Test
    void rejectsOwnerRepetitionAndNormalizedSharedDuplicates() {
        assertThrows(InvalidConnectionAccessPolicyException.class,
                () -> ConnectionAccessPolicy.from(
                        policyNode("Owner", List.of(" owner ")), LOCATION));
        assertThrows(InvalidConnectionAccessPolicyException.class,
                () -> ConnectionAccessPolicy.from(
                        policyNode("owner", List.of("Shared", " shared ")), LOCATION));
    }

    @Test
    void rejectsInvalidPluginIdentifiersInEveryPosition() {
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionAccessPolicy.from(policyNode("invalid owner", List.of()), LOCATION));
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionAccessPolicy.from(policyNode("owner", List.of("invalid/shared")), LOCATION));
    }

    @Test
    void validatesEveryConfiguredPluginExactlyOnce() {
        ConnectionAccessPolicy policy = ConnectionAccessPolicy.from(
                policyNode("owner", List.of("friend-one", "friend-two")), LOCATION);
        Set<String> inspected = new HashSet<>();

        policy.validateConfiguredPlugins(plugin -> {
            assertTrue(inspected.add(plugin));
            return true;
        }, LOCATION);

        assertEquals(Set.of("owner", "friend-one", "friend-two"), inspected);
        assertThrows(NullPointerException.class, () -> policy.validateConfiguredPlugins(null, LOCATION));
    }

    @Test
    void reportsTheUnknownConfiguredPlugin() {
        ConnectionAccessPolicy policy = ConnectionAccessPolicy.from(
                policyNode("owner", List.of("known", "missing")), LOCATION);

        InvalidConnectionAccessPolicyException failure = assertThrows(
                InvalidConnectionAccessPolicyException.class,
                () -> policy.validateConfiguredPlugins(plugin -> !plugin.equals("missing"), LOCATION)
        );

        assertTrue(failure.getMessage().contains("missing"));
        assertTrue(failure.getMessage().contains(LOCATION));
    }

    private static CommentedConfigurationNode policyNode(Object owner, List<?> sharedWith) {
        CommentedConfigurationNode node = CommentedConfigurationNode.root();
        node.node("access", "owner_plugin").raw(owner);
        if (sharedWith != null) {
            node.node("access", "shared_with").raw(sharedWith);
        }
        return node;
    }
}
