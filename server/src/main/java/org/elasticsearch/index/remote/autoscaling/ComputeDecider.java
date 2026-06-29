/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

public class ComputeDecider implements Decider {

    private final double scaleUpThreshold;
    private final double scaleDownThreshold;

    public ComputeDecider(double scaleUpThreshold, double scaleDownThreshold) {
        this.scaleUpThreshold = scaleUpThreshold;
        this.scaleDownThreshold = scaleDownThreshold;
    }

    @Override
    public String name() {
        return "compute";
    }

    @Override
    public String[] applicableTiers() {
        return new String[]{"warm", "hot"};
    }

    @Override
    public DeciderResult evaluate(MetricSnapshot metrics, int currentCount) {
        double usage = Math.max(metrics.cpuUsage(), metrics.memoryUsage());
        if (usage > scaleUpThreshold) {
            int needed = (int) Math.ceil(currentCount * (usage / scaleUpThreshold));
            return DeciderResult.scaleUp("warm", needed,
                "CPU/Mem=" + (int)(usage * 100) + "% > " + (int)(scaleUpThreshold * 100) + "%");
        } else if (usage < scaleDownThreshold && currentCount > 2) {
            int reduced = Math.max(2, (int) Math.ceil(currentCount * (usage / scaleUpThreshold)));
            return DeciderResult.scaleDown("warm", reduced,
                "CPU/Mem=" + (int)(usage * 100) + "% < " + (int)(scaleDownThreshold * 100) + "%");
        }
        return DeciderResult.noOp("warm", currentCount);
    }
}
