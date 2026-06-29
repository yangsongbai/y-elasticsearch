/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;

public class AutoscalingSettingsTests extends ESTestCase {

    public void testDefaults() {
        Settings s = Settings.EMPTY;
        assertTrue(AutoscalingSettings.ENABLED.get(s));
        assertEquals(30000L, AutoscalingSettings.EVALUATION_INTERVAL.get(s).millis());
        assertEquals(30000L, AutoscalingSettings.COOLDOWN_UP.get(s).millis());
        assertEquals(300000L, AutoscalingSettings.COOLDOWN_DOWN.get(s).millis());
        assertEquals(1.0, AutoscalingSettings.RATE_UP.get(s), 0.01);
        assertEquals(0.3, AutoscalingSettings.RATE_DOWN.get(s), 0.01);
    }

    public void testDeciderSettings() {
        Settings s = Settings.builder()
            .put("cluster.autoscaling.deciders.latency.target_p99", "500ms")
            .put("cluster.autoscaling.deciders.queue.threshold", 200)
            .put("cluster.autoscaling.deciders.predictive.lookahead", "30m")
            .build();
        assertEquals(500L, AutoscalingSettings.LATENCY_TARGET_P99.get(s).millis());
        assertEquals(200, AutoscalingSettings.QUEUE_THRESHOLD.get(s).intValue());
        assertEquals(1800000L, AutoscalingSettings.PREDICTIVE_LOOKAHEAD.get(s).millis());
    }

    public void testTierBounds() {
        Settings s = Settings.builder()
            .put("cluster.autoscaling.warm.min", 3)
            .put("cluster.autoscaling.warm.max", 50)
            .build();
        assertEquals(3, AutoscalingSettings.WARM_MIN.get(s).intValue());
        assertEquals(50, AutoscalingSettings.WARM_MAX.get(s).intValue());
    }
}
