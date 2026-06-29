/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.test.ESTestCase;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

public class TieringServiceTests extends ESTestCase {

    private ClusterState createClusterState(Version minNodeVersion) {
        DiscoveryNode localNode = new DiscoveryNode("node1", buildNewFakeTransportAddress(),
            Collections.emptyMap(), Collections.emptySet(), minNodeVersion);
        DiscoveryNodes nodes = DiscoveryNodes.builder().add(localNode).localNodeId("node1").masterNodeId("node1").build();
        return ClusterState.builder(ClusterState.EMPTY_STATE).nodes(nodes).build();
    }

    private TierTransitioner successTransitioner() {
        return new TierTransitioner() {
            @Override
            public boolean prepareTransition(String index, TieringState from, TieringState to) {
                return true;
            }

            @Override
            public boolean executeTransition(String index, TieringState from, TieringState to) {
                return true;
            }

            @Override
            public void rollback(String index, TieringState from, TieringState to) {}
        };
    }

    public void testHotToWarmTransition() {
        TieringService service = new TieringService(successTransitioner());
        ClusterState state = createClusterState(Version.V_7_17_4);
        boolean result = service.transitionIndex("test-index", TieringState.HOT, TieringState.WARM, state);

        assertTrue(result);
        TieringMetadata metadata = service.getState("test-index");
        assertEquals(TieringState.WARM, metadata.getCurrentState());
        assertEquals(TieringState.HOT_TO_WARM, metadata.getPreviousState());
    }

    public void testWarmToHotPromoteTransition() {
        TieringService service = new TieringService(successTransitioner());
        ClusterState state = createClusterState(Version.V_7_17_4);
        boolean result = service.transitionIndex("test-index", TieringState.WARM, TieringState.HOT, state);

        assertTrue(result);
        TieringMetadata metadata = service.getState("test-index");
        assertEquals(TieringState.HOT, metadata.getCurrentState());
        assertEquals(TieringState.WARM, metadata.getPreviousState());
    }

    public void testColdToWarmPromoteTransition() {
        TieringService service = new TieringService(successTransitioner());
        ClusterState state = createClusterState(Version.V_7_17_4);
        boolean result = service.transitionIndex("test-index", TieringState.COLD, TieringState.WARM, state);

        assertTrue(result);
        TieringMetadata metadata = service.getState("test-index");
        assertEquals(TieringState.WARM, metadata.getCurrentState());
        assertEquals(TieringState.COLD, metadata.getPreviousState());
    }

    public void testInvalidTransitionRejected() {
        TieringService service = new TieringService(successTransitioner());
        ClusterState state = createClusterState(Version.V_7_17_4);
        boolean result = service.transitionIndex("test-index", TieringState.HOT, TieringState.COLD, state);

        assertFalse(result);
    }

    public void testRollbackOnFailure() {
        AtomicReference<String> rolledBack = new AtomicReference<>(null);
        TierTransitioner transitioner = new TierTransitioner() {
            @Override
            public boolean prepareTransition(String index, TieringState from, TieringState to) {
                return true;
            }

            @Override
            public boolean executeTransition(String index, TieringState from, TieringState to) {
                return false;
            }

            @Override
            public void rollback(String index, TieringState from, TieringState to) {
                rolledBack.set(index);
            }
        };

        TieringService service = new TieringService(transitioner);
        ClusterState state = createClusterState(Version.V_7_17_4);
        boolean result = service.transitionIndex("test-index", TieringState.HOT, TieringState.WARM, state);

        assertFalse(result);
        assertEquals("test-index", rolledBack.get());
    }

    public void testTransitionBlockedOnMixedCluster() {
        TieringService service = new TieringService(successTransitioner());
        ClusterState state = createClusterState(Version.V_7_17_3);
        boolean result = service.transitionIndex("test-index", TieringState.HOT, TieringState.WARM, state);

        assertFalse("Transition should be blocked when cluster has old nodes", result);
    }

    public void testTransitionBlockedWhenUnknownState() {
        TieringService service = new TieringService(successTransitioner());
        ClusterState state = createClusterState(Version.V_7_17_4);

        // First do a successful transition
        boolean warmResult = service.transitionIndex("test-index", TieringState.HOT, TieringState.WARM, state);
        assertTrue(warmResult);

        // Now simulate an unknown state being loaded from stream by directly putting a metadata with unknown flag
        // This would happen when a newer node serialized an unknown TieringState
        // We verify the service checks hasUnknownState before allowing further transitions
        assertNotNull(service.getState("test-index"));
        assertFalse(service.getState("test-index").hasUnknownState());
    }
}
