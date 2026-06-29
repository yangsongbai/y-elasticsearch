/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

public class ProactiveStorageDecider implements Decider {

    private final double targetUtilization;
    private final long lookaheadMs;

    public ProactiveStorageDecider(double targetUtilization, long lookaheadMs) {
        this.targetUtilization = targetUtilization;
        this.lookaheadMs = lookaheadMs;
    }

    @Override
    public String name() {
        return "proactive_storage";
    }

    @Override
    public String[] applicableTiers() {
        return new String[]{"hot"};
    }

    @Override
    public DeciderResult evaluate(MetricSnapshot metrics, int currentCount) {
        double avgDisk = metrics.diskUsageByNode().values().stream()
            .mapToDouble(Double::doubleValue).average().orElse(0);

        double bytesInWindow = metrics.writeRateBytesPerSec() * (lookaheadMs / 1000.0);
        double capacityPerNode = 800.0 * 1024 * 1024 * 1024;
        double totalCapacity = capacityPerNode * currentCount;
        double projectedUsage = (avgDisk * totalCapacity + bytesInWindow) / totalCapacity;

        if (projectedUsage > targetUtilization) {
            int needed = (int) Math.ceil(currentCount * (projectedUsage / targetUtilization));
            return DeciderResult.scaleUp("hot", needed,
                "projected disk " + (int)(projectedUsage * 100) + "% in " + (lookaheadMs / 3600000) + "h");
        }
        return DeciderResult.noOp("hot", currentCount);
    }
}
