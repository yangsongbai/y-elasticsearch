/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESTestCase;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AutoscalingServiceTests extends ESTestCase {

    public void testEvaluateTriggersDeciders() {
        AtomicInteger dispatched = new AtomicInteger(0);
        ActionDispatcher dispatcher = (tier, count) -> dispatched.set(count);

        AutoscalingService service = new AutoscalingService(
            Arrays.asList(new LatencyDecider(200, 100)),
            new PolicyAggregator(2, 50, 1.0, 0.3),
            new CooldownRateLimiter(0, 0),
            new PromotionRegistry(),
            dispatcher
        );

        Map<String, Integer> nodeCounts = new HashMap<>();
        nodeCounts.put("warm", 3);
        MetricSnapshot snap = new MetricSnapshot(
            Collections.emptyMap(), 0, 350, 0, 0,
            0.5, 0.5, 0, nodeCounts
        );

        service.evaluate(snap, nodeCounts);
        assertTrue("Expected dispatch > 3, got " + dispatched.get(), dispatched.get() > 3);
    }

    public void testCooldownPreventsRepeatedScale() {
        AtomicInteger dispatched = new AtomicInteger(0);
        ActionDispatcher dispatcher = (tier, count) -> dispatched.set(count);

        AutoscalingService service = new AutoscalingService(
            Arrays.asList(new LatencyDecider(200, 100)),
            new PolicyAggregator(2, 50, 1.0, 0.3),
            new CooldownRateLimiter(60_000, 300_000),
            new PromotionRegistry(),
            dispatcher
        );

        Map<String, Integer> nodeCounts = new HashMap<>();
        nodeCounts.put("warm", 3);
        MetricSnapshot snap = new MetricSnapshot(
            Collections.emptyMap(), 0, 350, 0, 0,
            0.5, 0.5, 0, nodeCounts
        );

        service.evaluate(snap, nodeCounts); // first: succeeds
        int first = dispatched.get();
        assertTrue(first > 3);
        dispatched.set(0);

        Map<String, Integer> newCounts = new HashMap<>();
        newCounts.put("warm", first);
        service.evaluate(snap, newCounts); // second: blocked by cooldown
        assertEquals(0, dispatched.get());
    }
}
