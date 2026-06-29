package org.elasticsearch.index.remote.dr;

import org.elasticsearch.test.ESTestCase;

public class CrossRegionReplicationServiceTests extends ESTestCase {

    public void testReplicationStateTracking() {
        CrossRegionReplicationService service = new CrossRegionReplicationService();
        service.startReplication("idx", "us-east-1", "eu-west-1");

        CrossRegionReplicationService.ReplicationState state = service.getReplicationState("idx");
        assertNotNull(state);
        assertEquals("us-east-1", state.sourceRegion());
        assertEquals("eu-west-1", state.targetRegion());
        assertTrue(state.isActive());
    }

    public void testReplicationLag() {
        CrossRegionReplicationService service = new CrossRegionReplicationService();
        service.startReplication("idx", "us-east-1", "eu-west-1");
        service.updateSourceCheckpoint("idx", 1000L);
        service.updateTargetCheckpoint("idx", 950L);

        assertEquals(50L, service.getReplicationLag("idx"));
    }

    public void testStopReplication() {
        CrossRegionReplicationService service = new CrossRegionReplicationService();
        service.startReplication("idx", "us-east-1", "eu-west-1");
        service.stopReplication("idx");

        CrossRegionReplicationService.ReplicationState state = service.getReplicationState("idx");
        assertFalse(state.isActive());
    }
}
