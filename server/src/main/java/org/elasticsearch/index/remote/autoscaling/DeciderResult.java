/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

public class DeciderResult {

    public enum Direction { UP, DOWN, NONE }

    private final String tier;
    private final int desiredCount;
    private final int currentCount;
    private final String reason;
    private final Direction direction;

    private DeciderResult(String tier, int desiredCount, int currentCount, String reason, Direction direction) {
        this.tier = tier;
        this.desiredCount = desiredCount;
        this.currentCount = currentCount;
        this.reason = reason;
        this.direction = direction;
    }

    public static DeciderResult scaleUp(String tier, int desired, String reason) {
        return new DeciderResult(tier, desired, -1, reason, Direction.UP);
    }

    public static DeciderResult scaleDown(String tier, int desired, String reason) {
        return new DeciderResult(tier, desired, -1, reason, Direction.DOWN);
    }

    public static DeciderResult noOp(String tier, int current) {
        return new DeciderResult(tier, current, current, "no change needed", Direction.NONE);
    }

    public String tier() {
        return tier;
    }

    public int desiredCount() {
        return desiredCount;
    }

    public int currentCount() {
        return currentCount;
    }

    public String reason() {
        return reason;
    }

    public Direction direction() {
        return direction;
    }

    public boolean isScaleUp() {
        return direction == Direction.UP;
    }

    public boolean isScaleDown() {
        return direction == Direction.DOWN;
    }
}
