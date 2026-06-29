package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FastFailoverService {

    private static final Logger logger = LogManager.getLogger(FastFailoverService.class);

    private final boolean enabled;
    private final long knownDeadTimeoutMs;
    private final Set<String> unavailableNodes = ConcurrentHashMap.newKeySet();

    public FastFailoverService(boolean enabled, long knownDeadTimeoutMs) {
        this.enabled = enabled;
        this.knownDeadTimeoutMs = knownDeadTimeoutMs;
    }

    public void onNodeLeft(String nodeId) {
        if (enabled) {
            unavailableNodes.add(nodeId);
            logger.info("Node [{}] marked unavailable for fast failover routing", nodeId);
        }
    }

    public void onNodeJoined(String nodeId) {
        if (unavailableNodes.remove(nodeId)) {
            logger.info("Node [{}] rejoined, cleared from unavailable set", nodeId);
        }
    }

    public boolean isNodeUnavailable(String nodeId) {
        return unavailableNodes.contains(nodeId);
    }

    public boolean shouldSkipNode(String nodeId) {
        return enabled && unavailableNodes.contains(nodeId);
    }

    public long getReducedTimeout(String nodeId) {
        if (enabled && unavailableNodes.contains(nodeId)) {
            return knownDeadTimeoutMs;
        }
        return -1L;
    }
}
