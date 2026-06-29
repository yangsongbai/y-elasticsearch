package org.elasticsearch.index.remote;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterState;

/**
 * Guards remote store features from being activated during rolling upgrades.
 * All remote store features require this version as the minimum node version in the cluster
 * before they can be enabled. This prevents old nodes from rejecting cluster state containing
 * unknown INDEX-scoped settings.
 */
public final class VersionGatekeeper {

    /**
     * The minimum version required for remote store features.
     * This is the version that introduces storage-compute separation.
     * During rolling upgrades, features are blocked until all nodes reach this version.
     */
    public static final Version REMOTE_STORE_MIN_VERSION = Version.V_7_17_4;

    private VersionGatekeeper() {}

    /**
     * Check whether all nodes in the cluster support remote store features.
     *
     * @param clusterState the current cluster state
     * @return true if all nodes are at or above the minimum required version
     */
    public static boolean allNodesSupport(ClusterState clusterState) {
        Version minVersion = clusterState.nodes().getMinNodeVersion();
        return minVersion.onOrAfter(REMOTE_STORE_MIN_VERSION);
    }

    /**
     * Validate that remote store features can be activated in the current cluster.
     * Throws if the cluster is in a mixed-version state where old nodes would reject
     * the new settings.
     *
     * @param clusterState the current cluster state
     * @param featureName descriptive name of the feature being activated (for error messages)
     * @throws IllegalStateException if the cluster has nodes below the required version
     */
    public static void ensureAllNodesSupport(ClusterState clusterState, String featureName) {
        Version minVersion = clusterState.nodes().getMinNodeVersion();
        if (minVersion.before(REMOTE_STORE_MIN_VERSION)) {
            throw new IllegalStateException(
                "Cannot activate [" + featureName + "]: cluster contains nodes at version ["
                    + minVersion + "] but minimum required version is [" + REMOTE_STORE_MIN_VERSION
                    + "]. Complete the rolling upgrade before enabling remote store features."
            );
        }
    }
}
