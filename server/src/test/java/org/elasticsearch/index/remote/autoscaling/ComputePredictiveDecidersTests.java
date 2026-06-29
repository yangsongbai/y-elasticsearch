/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESTestCase;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ComputePredictiveDecidersTests extends ESTestCase {

    public void testComputeScaleUpOnHighCPU() {
        ComputeDecider decider = new ComputeDecider(0.80, 0.40);
        Map<String, Integer> nodeCounts = new HashMap<>();
        nodeCounts.put("warm", 3);
        MetricSnapshot snap = new MetricSnapshot(
            Collections.emptyMap(), 0, 100, 0, 0,
            0.90, 0.5, 0, nodeCounts
        );
        DeciderResult result = decider.evaluate(snap, 3);
        assertTrue(result.desiredCount() > 3);
    }

    public void testComputeScaleDownOnLowCPU() {
        ComputeDecider decider = new ComputeDecider(0.80, 0.40);
        Map<String, Integer> nodeCounts = new HashMap<>();
        nodeCounts.put("warm", 5);
        MetricSnapshot snap = new MetricSnapshot(
            Collections.emptyMap(), 0, 100, 0, 0,
            0.20, 0.3, 0, nodeCounts
        );
        DeciderResult result = decider.evaluate(snap, 5);
        assertTrue(result.desiredCount() < 5);
    }

    public void testPredictiveWithHistory() {
        PredictiveDecider decider = new PredictiveDecider();
        decider.recordHistoricalLoad(3, 0.95);
        Map<String, Integer> nodeCounts = new HashMap<>();
        nodeCounts.put("warm", 3);
        MetricSnapshot snap = new MetricSnapshot(
            Collections.emptyMap(), 0, 100, 0, 0,
            0.60, 0.5, 0, nodeCounts
        );
        DeciderResult result = decider.evaluate(snap, 3);
        assertTrue(result.desiredCount() >= 3);
    }
}
