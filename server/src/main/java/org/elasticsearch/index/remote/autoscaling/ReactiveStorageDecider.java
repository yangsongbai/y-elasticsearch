/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

public class ReactiveStorageDecider implements Decider {

    private final double targetUtilization;
    private final double scaleUpThreshold;
    private final double emergencyThreshold;

    public ReactiveStorageDecider(double target, double scaleUp, double emergency) {
        this.targetUtilization = target;
        this.scaleUpThreshold = scaleUp;
        this.emergencyThreshold = emergency;
    }

    @Override
    public String name() {
        return "reactive_storage";
    }

    @Override
    public String[] applicableTiers() {
        return new String[]{"hot"};
    }

    @Override
    public DeciderResult evaluate(MetricSnapshot metrics, int currentCount) {
        double maxDisk = metrics.diskUsageByNode().values().stream()
            .mapToDouble(Double::doubleValue).max().orElse(0);

        if (maxDisk >= emergencyThreshold) {
            int needed = (int) Math.ceil(currentCount * (maxDisk / targetUtilization));
            return DeciderResult.scaleUp("hot", Math.max(needed, currentCount + 2),
                "EMERGENCY: disk at " + (int)(maxDisk * 100) + "%");
        } else if (maxDisk >= scaleUpThreshold) {
            int needed = (int) Math.ceil(currentCount * (maxDisk / targetUtilization));
            return DeciderResult.scaleUp("hot", Math.max(needed, currentCount + 1),
                "disk at " + (int)(maxDisk * 100) + "%, target " + (int)(targetUtilization * 100) + "%");
        }
        return DeciderResult.noOp("hot", currentCount);
    }
}
