/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

import java.util.ArrayList;
import java.util.List;

public class PredictiveDecider implements Decider {

    private final List<HistoricalPoint> history = new ArrayList<>();

    @Override
    public String name() {
        return "predictive";
    }

    @Override
    public String[] applicableTiers() {
        return new String[]{"warm", "coord"};
    }

    public void recordHistoricalLoad(int nodeCount, double loadFactor) {
        history.add(new HistoricalPoint(nodeCount, loadFactor));
    }

    @Override
    public DeciderResult evaluate(MetricSnapshot metrics, int currentCount) {
        if (history.isEmpty()) {
            return DeciderResult.noOp("warm", currentCount);
        }

        double avgHistorical = history.stream()
            .mapToDouble(HistoricalPoint::loadFactor)
            .average().orElse(0);

        double currentLoad = Math.max(metrics.cpuUsage(), metrics.memoryUsage());
        if (avgHistorical > currentLoad * 1.5) {
            int predicted = (int) Math.ceil(currentCount * (avgHistorical / 0.7));
            return DeciderResult.scaleUp("warm", Math.max(predicted, currentCount),
                "historical load=" + (int)(avgHistorical * 100) + "%, pre-scaling");
        }
        return DeciderResult.noOp("warm", currentCount);
    }

    private static class HistoricalPoint {
        private final int nodeCount;
        private final double loadFactor;

        HistoricalPoint(int nodeCount, double loadFactor) {
            this.nodeCount = nodeCount;
            this.loadFactor = loadFactor;
        }

        double loadFactor() {
            return loadFactor;
        }
    }
}
