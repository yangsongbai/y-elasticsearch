/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESIntegTestCase;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AutoscalingIntegrationIT extends ESIntegTestCase {

    public void testEndToEndScaleUp() {
        AtomicInteger target = new AtomicInteger(0);
        ActionDispatcher dispatcher = (tier, count) -> target.set(count);

        AutoscalingService service = new AutoscalingService(
            Arrays.asList(
                new ReactiveStorageDecider(0.6, 0.75, 0.85),
                new LatencyDecider(200, 100),
                new QueueDecider(100)
            ),
            new PolicyAggregator(2, 50, 1.0, 0.3),
            new CooldownRateLimiter(0, 0),
            new PromotionRegistry(),
            dispatcher
        );

        // Simulate high latency + queue overflow scenario
        Map<String, Double> diskUsage = new HashMap<>();
        diskUsage.put("hot-1", 0.5);
        Map<String, Integer> nodeCounts = new HashMap<>();
        nodeCounts.put("warm", 3);

        MetricSnapshot snap = new MetricSnapshot(
            diskUsage, 180, 400, 150, 2, 0.7, 0.6, 0, nodeCounts
        );

        service.evaluate(snap, nodeCounts);
        assertTrue("Expected scale-up target > 3, got " + target.get(), target.get() > 3);
    }

    public void testPromotionOverride() {
        AtomicInteger target = new AtomicInteger(0);
        ActionDispatcher dispatcher = (tier, count) -> target.set(count);

        PromotionRegistry registry = new PromotionRegistry();
        long now = System.currentTimeMillis();
        registry.register("black-friday", now - 1000, now + 3600_000,
            new PromotionRegistry.ScaleFactors(3.0, 2.0), true);

        AutoscalingService service = new AutoscalingService(
            Arrays.asList(new LatencyDecider(200, 100)),
            new PolicyAggregator(2, 50, 2.0, 0.3),
            new CooldownRateLimiter(0, 0),
            registry,
            dispatcher
        );

        Map<String, Integer> nodeCounts = new HashMap<>();
        nodeCounts.put("warm", 3);
        Map<String, Double> emptyDisks = new HashMap<>();

        MetricSnapshot snap = new MetricSnapshot(
            emptyDisks, 0, 100, 0, 0, 0.3, 0.3, 0, nodeCounts
        );

        service.evaluate(snap, nodeCounts);
        // Promotion factor 3.0 on 3 nodes = 9 desired
        assertTrue("Expected promotion-driven scale > 3, got " + target.get(), target.get() > 3);
    }
}
