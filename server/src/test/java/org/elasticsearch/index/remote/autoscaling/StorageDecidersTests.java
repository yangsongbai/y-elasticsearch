/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESTestCase;

import java.util.HashMap;
import java.util.Map;

public class StorageDecidersTests extends ESTestCase {

    private MetricSnapshot makeSnapshot(double diskUsage, double writeRate) {
        Map<String, Double> disks = new HashMap<>();
        disks.put("hot-1", diskUsage);
        disks.put("hot-2", diskUsage);
        Map<String, Integer> nodeCounts = new HashMap<>();
        nodeCounts.put("hot", 2);
        nodeCounts.put("warm", 3);
        return new MetricSnapshot(
            disks, 50.0, 100.0, 0, 0, 0.5, 0.6, writeRate, nodeCounts
        );
    }

    public void testReactiveNoScaleWhenLow() {
        ReactiveStorageDecider decider = new ReactiveStorageDecider(0.60, 0.75, 0.85);
        DeciderResult result = decider.evaluate(makeSnapshot(0.50, 0), 2);
        assertEquals(2, result.desiredCount());
    }

    public void testReactiveScaleUpWhenHigh() {
        ReactiveStorageDecider decider = new ReactiveStorageDecider(0.60, 0.75, 0.85);
        DeciderResult result = decider.evaluate(makeSnapshot(0.80, 0), 2);
        assertTrue(result.desiredCount() > 2);
    }

    public void testReactiveEmergencyScaleUp() {
        ReactiveStorageDecider decider = new ReactiveStorageDecider(0.60, 0.75, 0.85);
        DeciderResult result = decider.evaluate(makeSnapshot(0.90, 0), 2);
        assertTrue(result.desiredCount() > 3);
    }

    public void testProactivePredictsGrowth() {
        ProactiveStorageDecider decider = new ProactiveStorageDecider(0.60, 2 * 3600 * 1000L);
        DeciderResult result = decider.evaluate(makeSnapshot(0.50, 100.0 * 1024 * 1024), 2);
        assertTrue(result.desiredCount() >= 2);
    }
}
