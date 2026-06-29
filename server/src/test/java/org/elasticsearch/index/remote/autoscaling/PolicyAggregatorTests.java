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
import java.util.List;

public class PolicyAggregatorTests extends ESTestCase {

    public void testAnyUpPolicy() {
        PolicyAggregator agg = new PolicyAggregator(2, 100, 1.0, 0.3);
        List<DeciderResult> results = Arrays.asList(
            DeciderResult.noOp("warm", 3),
            DeciderResult.scaleUp("warm", 5, "latency high"),
            DeciderResult.noOp("warm", 3)
        );
        int target = agg.aggregateScaleUp(results, 3);
        assertEquals(5, target);
    }

    public void testAllDownPolicy() {
        PolicyAggregator agg = new PolicyAggregator(2, 100, 1.0, 1.0);
        List<DeciderResult> results = Arrays.asList(
            DeciderResult.scaleDown("warm", 2, "latency low"),
            DeciderResult.scaleDown("warm", 1, "queue empty"),
            DeciderResult.scaleDown("warm", 2, "cpu low")
        );
        int target = agg.aggregateScaleDown(results, 5);
        assertEquals(2, target); // most conservative
    }

    public void testBoundsEnforced() {
        PolicyAggregator agg = new PolicyAggregator(3, 10, 1.0, 0.3);
        List<DeciderResult> results = Arrays.asList(
            DeciderResult.scaleUp("warm", 50, "extreme")
        );
        int target = agg.aggregateScaleUp(results, 5);
        assertEquals(10, target); // clamped to max
    }

    public void testRateLimitScaleUp() {
        PolicyAggregator agg = new PolicyAggregator(2, 100, 1.0, 0.3);
        List<DeciderResult> results = Arrays.asList(
            DeciderResult.scaleUp("warm", 20, "spike")
        );
        int target = agg.aggregateScaleUp(results, 5);
        assertEquals(10, target); // rate=1.0 means max +100% = 10
    }

    public void testCooldownPreventsAction() {
        CooldownRateLimiter limiter = new CooldownRateLimiter(60_000L, 300_000L);
        assertTrue(limiter.allowScaleUp());
        limiter.recordScaleUp();
        assertFalse(limiter.allowScaleUp()); // within cooldown
    }
}
