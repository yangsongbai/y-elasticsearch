/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESTestCase;

public class DeciderResultTests extends ESTestCase {

    public void testScaleUp() {
        DeciderResult result = DeciderResult.scaleUp("warm", 5, "P99 latency > 200ms");
        assertEquals("warm", result.tier());
        assertEquals(5, result.desiredCount());
        assertTrue(result.isScaleUp());
        assertFalse(result.isScaleDown());
        assertEquals(DeciderResult.Direction.UP, result.direction());
        assertEquals("P99 latency > 200ms", result.reason());
    }

    public void testScaleDown() {
        DeciderResult result = DeciderResult.scaleDown("warm", 2, "Queue empty");
        assertEquals("warm", result.tier());
        assertEquals(2, result.desiredCount());
        assertFalse(result.isScaleUp());
        assertTrue(result.isScaleDown());
        assertEquals(DeciderResult.Direction.DOWN, result.direction());
        assertEquals("Queue empty", result.reason());
    }

    public void testNoOp() {
        DeciderResult result = DeciderResult.noOp("warm", 3);
        assertEquals("warm", result.tier());
        assertEquals(3, result.desiredCount());
        assertEquals(3, result.currentCount());
        assertFalse(result.isScaleUp());
        assertFalse(result.isScaleDown());
        assertEquals(DeciderResult.Direction.NONE, result.direction());
        assertEquals("no change needed", result.reason());
    }
}
