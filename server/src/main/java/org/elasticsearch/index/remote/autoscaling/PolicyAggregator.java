/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

import java.util.List;

public class PolicyAggregator {

    private final int minNodes;
    private final int maxNodes;
    private final double rateUp;
    private final double rateDown;

    public PolicyAggregator(int minNodes, int maxNodes, double rateUp, double rateDown) {
        this.minNodes = minNodes;
        this.maxNodes = maxNodes;
        this.rateUp = rateUp;
        this.rateDown = rateDown;
    }

    /**
     * Any-up policy: takes the maximum desired count from all scale-up suggestions.
     * Rate-limits the growth and enforces min/max bounds.
     */
    public int aggregateScaleUp(List<DeciderResult> results, int current) {
        int maxDesired = current;
        for (DeciderResult r : results) {
            if (r.desiredCount() > current) {
                maxDesired = Math.max(maxDesired, r.desiredCount());
            }
        }

        // Rate limit: cannot grow more than rateUp * current
        int maxAllowed = (int) Math.ceil(current * (1 + rateUp));
        int clamped = Math.min(maxDesired, maxAllowed);
        return Math.min(Math.max(clamped, minNodes), maxNodes);
    }

    /**
     * All-down policy: takes the most conservative (highest) among scale-down requests.
     * Rate-limits the shrink and enforces min bounds.
     */
    public int aggregateScaleDown(List<DeciderResult> results, int current) {
        int target = current;
        boolean anyDown = false;
        for (DeciderResult r : results) {
            if (r.desiredCount() < current) {
                if (!anyDown) {
                    target = r.desiredCount();
                    anyDown = true;
                } else {
                    target = Math.max(target, r.desiredCount());
                }
            }
        }
        if (!anyDown) {
            return current;
        }

        // Rate limit: cannot shrink more than rateDown * current
        int minAllowed = (int) Math.floor(current * (1 - rateDown));
        int clamped = Math.max(target, minAllowed);
        return Math.max(clamped, minNodes);
    }
}
