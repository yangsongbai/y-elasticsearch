/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.tiering;

/**
 * Strategy interface for executing tier transitions.
 * Implementations handle the actual mechanics of moving data between tiers.
 */
public interface TierTransitioner {

    /**
     * Prepare for a transition. Called before the transition is executed.
     * @return true if preparation succeeded, false to abort the transition
     */
    boolean prepareTransition(String index, TieringState from, TieringState to);

    /**
     * Execute the transition.
     * @return true if the transition succeeded, false to trigger rollback
     */
    boolean executeTransition(String index, TieringState from, TieringState to);

    /**
     * Rollback a failed transition, reverting to the previous state.
     */
    void rollback(String index, TieringState from, TieringState to);
}
