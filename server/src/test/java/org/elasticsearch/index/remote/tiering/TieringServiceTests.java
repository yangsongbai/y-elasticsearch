/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.test.ESTestCase;

import java.util.concurrent.atomic.AtomicReference;

public class TieringServiceTests extends ESTestCase {

    public void testHotToWarmTransition() {
        TierTransitioner transitioner = new TierTransitioner() {
            @Override
            public boolean prepareTransition(String index, TieringState from, TieringState to) {
                return true;
            }

            @Override
            public boolean executeTransition(String index, TieringState from, TieringState to) {
                return true;
            }

            @Override
            public void rollback(String index, TieringState from, TieringState to) {}
        };

        TieringService service = new TieringService(transitioner);
        boolean result = service.transitionIndex("test-index", TieringState.HOT, TieringState.WARM);

        assertTrue(result);
    }

    public void testInvalidTransitionRejected() {
        TierTransitioner transitioner = new TierTransitioner() {
            @Override
            public boolean prepareTransition(String index, TieringState from, TieringState to) {
                return true;
            }

            @Override
            public boolean executeTransition(String index, TieringState from, TieringState to) {
                return true;
            }

            @Override
            public void rollback(String index, TieringState from, TieringState to) {}
        };

        TieringService service = new TieringService(transitioner);
        boolean result = service.transitionIndex("test-index", TieringState.HOT, TieringState.COLD);

        assertFalse(result);
    }

    public void testRollbackOnFailure() {
        AtomicReference<String> rolledBack = new AtomicReference<>(null);
        TierTransitioner transitioner = new TierTransitioner() {
            @Override
            public boolean prepareTransition(String index, TieringState from, TieringState to) {
                return true;
            }

            @Override
            public boolean executeTransition(String index, TieringState from, TieringState to) {
                return false;
            }

            @Override
            public void rollback(String index, TieringState from, TieringState to) {
                rolledBack.set(index);
            }
        };

        TieringService service = new TieringService(transitioner);
        boolean result = service.transitionIndex("test-index", TieringState.HOT, TieringState.WARM);

        assertFalse(result);
        assertEquals("test-index", rolledBack.get());
    }
}
