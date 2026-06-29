/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

public class CooldownRateLimiter {

    private final long cooldownUpMs;
    private final long cooldownDownMs;
    private volatile long lastScaleUpTime = 0;
    private volatile long lastScaleDownTime = 0;

    public CooldownRateLimiter(long cooldownUpMs, long cooldownDownMs) {
        this.cooldownUpMs = cooldownUpMs;
        this.cooldownDownMs = cooldownDownMs;
    }

    public boolean allowScaleUp() {
        return System.currentTimeMillis() - lastScaleUpTime >= cooldownUpMs;
    }

    public boolean allowScaleDown() {
        return System.currentTimeMillis() - lastScaleDownTime >= cooldownDownMs;
    }

    public void recordScaleUp() {
        lastScaleUpTime = System.currentTimeMillis();
    }

    public void recordScaleDown() {
        lastScaleDownTime = System.currentTimeMillis();
    }
}
