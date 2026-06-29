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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State machine orchestrator for tier transitions.
 * Validates transitions, manages intermediate states, and handles rollback on failure.
 */
public class TieringService {

    private static final Logger logger = LogManager.getLogger(TieringService.class);

    private final TierTransitioner transitioner;
    private final Map<String, TieringMetadata> indexStates = new ConcurrentHashMap<>();

    public TieringService(TierTransitioner transitioner) {
        this.transitioner = transitioner;
    }

    /**
     * Transition an index from one tier state to another.
     * The transition goes through an intermediate state (e.g., HOT -> HOT_TO_WARM -> WARM).
     * If execution fails, rollback is triggered and state reverts.
     *
     * @param index the index name
     * @param currentState the current tier state
     * @param targetState the desired target tier state
     * @return true if the transition completed successfully, false otherwise
     */
    public boolean transitionIndex(String index, TieringState currentState, TieringState targetState) {
        TieringState intermediate = getIntermediateState(currentState, targetState);
        if (intermediate == null) {
            logger.warn("Invalid transition from {} to {} for index [{}]", currentState, targetState, index);
            return false;
        }

        if (!currentState.canTransitionTo(intermediate)) {
            logger.warn("Cannot transition from {} to {} for index [{}]", currentState, intermediate, index);
            return false;
        }

        if (!transitioner.prepareTransition(index, currentState, intermediate)) {
            logger.warn("Prepare failed for index [{}] transition {} -> {}", index, currentState, intermediate);
            return false;
        }

        indexStates.put(index, new TieringMetadata(intermediate, currentState, System.currentTimeMillis()));

        if (!transitioner.executeTransition(index, intermediate, targetState)) {
            logger.warn("Execute failed for index [{}], rolling back", index);
            transitioner.rollback(index, intermediate, currentState);
            indexStates.put(index, new TieringMetadata(currentState, intermediate, System.currentTimeMillis()));
            return false;
        }

        indexStates.put(index, new TieringMetadata(targetState, intermediate, System.currentTimeMillis()));
        return true;
    }

    private TieringState getIntermediateState(TieringState from, TieringState to) {
        if (from == TieringState.HOT && to == TieringState.WARM) {
            return TieringState.HOT_TO_WARM;
        }
        if (from == TieringState.WARM && to == TieringState.COLD) {
            return TieringState.WARM_TO_COLD;
        }
        if (from == TieringState.WARM && to == TieringState.HOT) {
            return TieringState.HOT;
        }
        if (from == TieringState.COLD && to == TieringState.WARM) {
            return TieringState.WARM;
        }
        return null;
    }

    /**
     * Get the current tiering metadata for an index.
     */
    public TieringMetadata getState(String index) {
        return indexStates.get(index);
    }
}
