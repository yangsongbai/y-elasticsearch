package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CrossAzAllocationEnforcer {

    private static final Logger logger = LogManager.getLogger(CrossAzAllocationEnforcer.class);

    public static final CrossAzAllocationEnforcer DISABLED = new CrossAzAllocationEnforcer(
        Collections.emptyList(), "");

    private final List<String> azValues;
    private final String awarenessAttribute;

    public CrossAzAllocationEnforcer(List<String> azValues, String awarenessAttribute) {
        this.azValues = azValues;
        this.awarenessAttribute = awarenessAttribute;
    }

    public boolean canAllocateReplicaOnNode(Map<String, String> primaryNodeAttrs,
                                             Map<String, String> candidateNodeAttrs) {
        if (azValues.isEmpty() || awarenessAttribute.isEmpty()) {
            return true;
        }

        String primaryAz = primaryNodeAttrs.get(awarenessAttribute);
        String candidateAz = candidateNodeAttrs.get(awarenessAttribute);

        if (primaryAz == null || candidateAz == null) {
            logger.warn("Awareness attribute [{}] missing on node, allowing allocation", awarenessAttribute);
            return true;
        }

        boolean allowed = !Objects.equals(primaryAz, candidateAz);
        if (!allowed) {
            logger.debug("Rejecting replica allocation: primary and replica both in AZ [{}]", primaryAz);
        }
        return allowed;
    }

    public boolean validateShardDistribution(Map<String, String> shardToAzMap) {
        if (azValues.isEmpty()) {
            return true;
        }

        String primaryAz = shardToAzMap.get("primary");
        if (primaryAz == null) {
            return true;
        }

        for (Map.Entry<String, String> entry : shardToAzMap.entrySet()) {
            if (entry.getKey().startsWith("replica") && Objects.equals(primaryAz, entry.getValue())) {
                logger.warn("Shard distribution violation: primary and {} in same AZ [{}]",
                    entry.getKey(), primaryAz);
                return false;
            }
        }
        return true;
    }
}
