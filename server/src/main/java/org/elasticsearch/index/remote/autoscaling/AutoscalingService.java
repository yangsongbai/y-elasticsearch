/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AutoscalingService {

    private static final Logger logger = LogManager.getLogger(AutoscalingService.class);

    private final List<Decider> deciders;
    private final PolicyAggregator aggregator;
    private final CooldownRateLimiter cooldown;
    private final PromotionRegistry promotionRegistry;
    private final ActionDispatcher dispatcher;

    public AutoscalingService(List<Decider> deciders, PolicyAggregator aggregator,
                              CooldownRateLimiter cooldown, PromotionRegistry promotionRegistry,
                              ActionDispatcher dispatcher) {
        this.deciders = deciders;
        this.aggregator = aggregator;
        this.cooldown = cooldown;
        this.promotionRegistry = promotionRegistry;
        this.dispatcher = dispatcher;
    }

    public void evaluate(MetricSnapshot metrics, Map<String, Integer> currentCounts) {
        for (Map.Entry<String, Integer> entry : currentCounts.entrySet()) {
            String tier = entry.getKey();
            int current = entry.getValue();
            List<DeciderResult> results = new ArrayList<>();

            for (Decider decider : deciders) {
                for (String applicableTier : decider.applicableTiers()) {
                    if (applicableTier.equals(tier)) {
                        results.add(decider.evaluate(metrics, current));
                    }
                }
            }

            // Apply promotion override
            double scaleFactor = promotionRegistry.getActiveScaleFactor(tier);
            if (scaleFactor > 1.0) {
                int promoted = (int) Math.ceil(current * scaleFactor);
                results.add(DeciderResult.scaleUp(tier, promoted, "promotion override x" + scaleFactor));
            }

            // Aggregate
            int scaleUpTarget = aggregator.aggregateScaleUp(results, current);
            int scaleDownTarget = aggregator.aggregateScaleDown(results, current);

            if (scaleUpTarget > current && cooldown.allowScaleUp()) {
                logger.info("Scaling UP [{}]: {} -> {}", tier, current, scaleUpTarget);
                dispatcher.dispatch(tier, scaleUpTarget);
                cooldown.recordScaleUp();
            } else if (scaleDownTarget < current && cooldown.allowScaleDown()
                       && !promotionRegistry.isScaleDownLocked()) {
                logger.info("Scaling DOWN [{}]: {} -> {}", tier, current, scaleDownTarget);
                dispatcher.dispatch(tier, scaleDownTarget);
                cooldown.recordScaleDown();
            }
        }
    }
}
