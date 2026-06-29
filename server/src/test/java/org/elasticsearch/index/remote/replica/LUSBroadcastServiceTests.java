/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.replica;

import org.elasticsearch.test.ESTestCase;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

public class LUSBroadcastServiceTests extends ESTestCase {

    public void testBroadcastUpdatesListeners() {
        LUSBroadcastService service = new LUSBroadcastService();
        AtomicLong received = new AtomicLong(-1);
        LongConsumer listener = received::set;
        service.addListener(listener);

        service.broadcastLUS(42L);
        assertEquals(42L, received.get());

        service.broadcastLUS(100L);
        assertEquals(100L, received.get());
    }

    public void testMonotonicOnly() {
        LUSBroadcastService service = new LUSBroadcastService();
        AtomicLong received = new AtomicLong(-1);
        service.addListener(received::set);

        service.broadcastLUS(50L);
        service.broadcastLUS(30L);
        assertEquals(50L, received.get());
    }

    public void testRemoveListener() {
        LUSBroadcastService service = new LUSBroadcastService();
        AtomicLong received = new AtomicLong(-1);
        LongConsumer listener = received::set;
        service.addListener(listener);
        service.removeListener(listener);

        service.broadcastLUS(99L);
        assertEquals(-1L, received.get());
    }
}
