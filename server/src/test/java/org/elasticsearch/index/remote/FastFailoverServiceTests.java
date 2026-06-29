package org.elasticsearch.index.remote;

import org.elasticsearch.test.ESTestCase;

public class FastFailoverServiceTests extends ESTestCase {

    public void testNodeMarkedUnavailableOnNodeLeft() {
        FastFailoverService service = new FastFailoverService(true, 2000L);
        String nodeId = "node-1";

        assertFalse(service.isNodeUnavailable(nodeId));

        service.onNodeLeft(nodeId);

        assertTrue(service.isNodeUnavailable(nodeId));
    }

    public void testNodeClearedWhenRejoins() {
        FastFailoverService service = new FastFailoverService(true, 2000L);
        String nodeId = "node-1";

        service.onNodeLeft(nodeId);
        assertTrue(service.isNodeUnavailable(nodeId));

        service.onNodeJoined(nodeId);
        assertFalse(service.isNodeUnavailable(nodeId));
    }

    public void testDisabledServiceAlwaysAvailable() {
        FastFailoverService service = new FastFailoverService(false, 2000L);
        String nodeId = "node-1";

        service.onNodeLeft(nodeId);
        assertFalse(service.isNodeUnavailable(nodeId));
    }

    public void testShouldSkipNodeForRouting() {
        FastFailoverService service = new FastFailoverService(true, 2000L);
        String deadNode = "node-1";
        String aliveNode = "node-2";

        service.onNodeLeft(deadNode);

        assertTrue(service.shouldSkipNode(deadNode));
        assertFalse(service.shouldSkipNode(aliveNode));
    }

    public void testGetReducedTimeoutForKnownDeadNode() {
        FastFailoverService service = new FastFailoverService(true, 2000L);
        String deadNode = "node-1";

        assertEquals(-1L, service.getReducedTimeout(deadNode));

        service.onNodeLeft(deadNode);
        assertEquals(2000L, service.getReducedTimeout(deadNode));
    }
}
