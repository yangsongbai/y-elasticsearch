/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

public class QueueDecider implements Decider {

    private final int queueThreshold;

    public QueueDecider(int queueThreshold) {
        this.queueThreshold = queueThreshold;
    }

    @Override
    public String name() {
        return "queue";
    }

    @Override
    public String[] applicableTiers() {
        return new String[]{"warm", "coord"};
    }

    @Override
    public DeciderResult evaluate(MetricSnapshot metrics, int currentCount) {
        if (metrics.searchRejections() > 0) {
            return DeciderResult.scaleUp("warm", currentCount + 2,
                "rejections=" + metrics.searchRejections() + " (immediate scale)");
        }
        if (metrics.searchQueueSize() > queueThreshold) {
            double overflow = (double) metrics.searchQueueSize() / queueThreshold;
            int needed = (int) Math.ceil(currentCount * overflow);
            return DeciderResult.scaleUp("warm", needed,
                "queue=" + metrics.searchQueueSize() + " > threshold=" + queueThreshold);
        }
        return DeciderResult.noOp("warm", currentCount);
    }
}
