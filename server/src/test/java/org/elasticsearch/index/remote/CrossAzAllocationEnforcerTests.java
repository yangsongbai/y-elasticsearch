package org.elasticsearch.index.remote;

import org.elasticsearch.test.ESTestCase;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CrossAzAllocationEnforcerTests extends ESTestCase {

    public void testPrimaryAndReplicaMustBeInDifferentAz() {
        List<String> azValues = Arrays.asList("az-1", "az-2", "az-3");
        CrossAzAllocationEnforcer enforcer = new CrossAzAllocationEnforcer(azValues, "zone");

        Map<String, String> primaryNodeAttrs = new HashMap<>();
        primaryNodeAttrs.put("zone", "az-1");

        Map<String, String> replicaNodeAttrs1 = new HashMap<>();
        replicaNodeAttrs1.put("zone", "az-1");
        assertFalse(enforcer.canAllocateReplicaOnNode(primaryNodeAttrs, replicaNodeAttrs1));

        Map<String, String> replicaNodeAttrs2 = new HashMap<>();
        replicaNodeAttrs2.put("zone", "az-2");
        assertTrue(enforcer.canAllocateReplicaOnNode(primaryNodeAttrs, replicaNodeAttrs2));
    }

    public void testValidationPassesForCrossAzSetup() {
        List<String> azValues = Arrays.asList("az-1", "az-2", "az-3");
        CrossAzAllocationEnforcer enforcer = new CrossAzAllocationEnforcer(azValues, "zone");

        Map<String, String> allocationMap = new HashMap<>();
        allocationMap.put("primary", "az-1");
        allocationMap.put("replica-0", "az-2");

        assertTrue(enforcer.validateShardDistribution(allocationMap));
    }

    public void testValidationFailsForSameAzSetup() {
        List<String> azValues = Arrays.asList("az-1", "az-2", "az-3");
        CrossAzAllocationEnforcer enforcer = new CrossAzAllocationEnforcer(azValues, "zone");

        Map<String, String> allocationMap = new HashMap<>();
        allocationMap.put("primary", "az-1");
        allocationMap.put("replica-0", "az-1");

        assertFalse(enforcer.validateShardDistribution(allocationMap));
    }

    public void testDisabledEnforcerAlwaysAllows() {
        CrossAzAllocationEnforcer enforcer = CrossAzAllocationEnforcer.DISABLED;

        Map<String, String> primaryAttrs = new HashMap<>();
        primaryAttrs.put("zone", "az-1");
        Map<String, String> replicaAttrs = new HashMap<>();
        replicaAttrs.put("zone", "az-1");

        assertTrue(enforcer.canAllocateReplicaOnNode(primaryAttrs, replicaAttrs));
    }
}
