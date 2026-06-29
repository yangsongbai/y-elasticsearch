/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.tiering;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.threadpool.ThreadPool;

/**
 * Evaluates whether an index should transition between tiers based on its age
 * versus the configured thresholds. This is independent of x-pack ILM.
 */
public class TieringPolicyService {

    private static final Logger logger = LogManager.getLogger(TieringPolicyService.class);

    private final ThreadPool threadPool;
    private final ClusterService clusterService;
    private final TieringService tieringService;

    public TieringPolicyService(ThreadPool threadPool, ClusterService clusterService,
                                TieringService tieringService) {
        this.threadPool = threadPool;
        this.clusterService = clusterService;
        this.tieringService = tieringService;
    }

    /**
     * Determines whether an index should transition to the target tier based on its age
     * and the configured threshold settings.
     *
     * @param indexMetadata the index metadata containing creation date and settings
     * @param currentState the current tiering state of the index
     * @param targetTier the target tier name ("warm", "cold")
     * @return true if the index age exceeds the configured threshold for the target tier
     */
    public boolean shouldTransition(IndexMetadata indexMetadata, TieringState currentState, String targetTier) {
        long creationDate = indexMetadata.getCreationDate();
        if (creationDate <= 0) {
            return false;
        }
        long ageMillis = System.currentTimeMillis() - creationDate;

        TimeValue threshold;
        if ("warm".equals(targetTier)) {
            threshold = TieringPolicySettings.WARM_AFTER.get(indexMetadata.getSettings());
        } else if ("cold".equals(targetTier)) {
            threshold = TieringPolicySettings.COLD_AFTER.get(indexMetadata.getSettings());
        } else {
            return false;
        }

        if (threshold.millis() <= 0) {
            return false;
        }

        return ageMillis >= threshold.millis();
    }
}
