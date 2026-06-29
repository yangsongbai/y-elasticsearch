/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;

public class TieringMetadataTests extends ESTestCase {

    public void testAllStatesValid() {
        for (TieringState state : TieringState.values()) {
            assertNotNull(state.name());
        }
        assertEquals(7, TieringState.values().length);
    }

    public void testTransitionValidation() {
        assertTrue(TieringState.HOT.canTransitionTo(TieringState.HOT_TO_WARM));
        assertTrue(TieringState.HOT_TO_WARM.canTransitionTo(TieringState.WARM));
        assertTrue(TieringState.HOT_TO_WARM.canTransitionTo(TieringState.HOT));
        assertTrue(TieringState.WARM.canTransitionTo(TieringState.WARM_TO_COLD));
        assertTrue(TieringState.WARM.canTransitionTo(TieringState.HOT));
        assertFalse(TieringState.HOT.canTransitionTo(TieringState.COLD));
        assertFalse(TieringState.COLD.canTransitionTo(TieringState.HOT_TO_WARM));
    }

    public void testSerializationRoundTrip() throws IOException {
        TieringMetadata original = new TieringMetadata(TieringState.WARM, TieringState.HOT, 12345L);

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        TieringMetadata parsed = new TieringMetadata(in);

        assertEquals(original.getCurrentState(), parsed.getCurrentState());
        assertEquals(original.getPreviousState(), parsed.getPreviousState());
        assertEquals(original.getTransitionTimestamp(), parsed.getTransitionTimestamp());
    }
}
