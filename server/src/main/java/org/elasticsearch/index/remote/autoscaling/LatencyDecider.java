/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

public class LatencyDecider implements Decider {

    private final double scaleUpThresholdMs;
    private final double scaleDownThresholdMs;

    public LatencyDecider(double scaleUpThresholdMs, double scaleDownThresholdMs) {
        this.scaleUpThresholdMs = scaleUpThresholdMs;
        this.scaleDownThresholdMs = scaleDownThresholdMs;
    }

    @Override
    public String name() {
        return "latency";
    }

    @Override
    public String[] applicableTiers() {
        return new String[]{"warm", "coord"};
    }

    @Override
    public DeciderResult evaluate(MetricSnapshot metrics, int currentCount) {
        double p99 = metrics.p99LatencyMs();
        if (p99 > scaleUpThresholdMs) {
            int needed = (int) Math.ceil(currentCount * (p99 / scaleUpThresholdMs));
            return DeciderResult.scaleUp("warm", needed,
                "P99=" + (int) p99 + "ms > " + (int) scaleUpThresholdMs + "ms");
        } else if (p99 < scaleDownThresholdMs && currentCount > 2) {
            int reduced = Math.max(2, (int) Math.ceil(currentCount * (p99 / scaleUpThresholdMs)));
            return DeciderResult.scaleDown("warm", reduced,
                "P99=" + (int) p99 + "ms < " + (int) scaleDownThresholdMs + "ms");
        }
        return DeciderResult.noOp("warm", currentCount);
    }
}
