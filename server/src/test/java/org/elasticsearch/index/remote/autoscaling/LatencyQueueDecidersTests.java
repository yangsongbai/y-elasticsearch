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

public class LatencyQueueDecidersTests extends ESTestCase {

    private MetricSnapshot snap(double p99, int queueSize, int rejections) {
        Map<String, Integer> nodeCounts = new HashMap<>();
        nodeCounts.put("warm", 3);
        return new MetricSnapshot(
            Collections.emptyMap(), 0, p99, queueSize, rejections,
            0.5, 0.6, 0, nodeCounts
        );
    }

    public void testLatencyScaleUpWhenHigh() {
        LatencyDecider decider = new LatencyDecider(200.0, 100.0);
        DeciderResult result = decider.evaluate(snap(350, 0, 0), 3);
        assertTrue(result.desiredCount() > 3);
    }

    public void testLatencyScaleDownWhenLow() {
        LatencyDecider decider = new LatencyDecider(200.0, 100.0);
        DeciderResult result = decider.evaluate(snap(80, 0, 0), 5);
        assertTrue(result.desiredCount() < 5);
    }

    public void testLatencyNoOpInRange() {
        LatencyDecider decider = new LatencyDecider(200.0, 100.0);
        DeciderResult result = decider.evaluate(snap(150, 0, 0), 3);
        assertEquals(3, result.desiredCount());
    }

    public void testQueueScaleUpOnRejections() {
        QueueDecider decider = new QueueDecider(100);
        DeciderResult result = decider.evaluate(snap(100, 50, 5), 3);
        assertTrue(result.desiredCount() > 3);
    }

    public void testQueueScaleUpOnOverflow() {
        QueueDecider decider = new QueueDecider(100);
        DeciderResult result = decider.evaluate(snap(100, 200, 0), 3);
        assertTrue(result.desiredCount() > 3);
    }
}
