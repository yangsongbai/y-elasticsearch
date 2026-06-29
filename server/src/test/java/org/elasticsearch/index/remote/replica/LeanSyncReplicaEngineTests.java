/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.replica;

import org.elasticsearch.test.ESTestCase;

public class LeanSyncReplicaEngineTests extends ESTestCase {

    public void testTailPruningOnLUSAdvance() {
        TailDirectory tailDir = new TailDirectory();
        LUSBroadcastService lusService = new LUSBroadcastService();
        LeanSyncReplicaEngine engine = new LeanSyncReplicaEngine(tailDir, lusService);

        engine.recordSegmentSeqNo("_0.cfs", 10L);
        engine.recordSegmentSeqNo("_1.cfs", 20L);
        engine.recordSegmentSeqNo("_2.cfs", 30L);

        lusService.broadcastLUS(20L);

        assertTrue(engine.isPrunable("_0.cfs"));
        assertTrue(engine.isPrunable("_1.cfs"));
        assertFalse(engine.isPrunable("_2.cfs"));
    }

    public void testGetTailSize() {
        TailDirectory tailDir = new TailDirectory();
        LUSBroadcastService lusService = new LUSBroadcastService();
        LeanSyncReplicaEngine engine = new LeanSyncReplicaEngine(tailDir, lusService);

        engine.recordSegmentSeqNo("_0.cfs", 10L);
        engine.recordSegmentSeqNo("_1.cfs", 50L);

        lusService.broadcastLUS(10L);

        assertEquals(1, engine.getTailSegmentCount());
    }
}
