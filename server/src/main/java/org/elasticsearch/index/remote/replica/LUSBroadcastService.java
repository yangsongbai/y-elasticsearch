/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.replica;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Monotonically broadcasts LastUploadedSeqNo (LUS) from Primary to replicas.
 * Listeners are notified when LUS advances. Out-of-order (stale) updates are silently dropped.
 */
public class LUSBroadcastService {

    private final AtomicLong currentLUS = new AtomicLong(-1);
    private final List<LongConsumer> listeners = new CopyOnWriteArrayList<>();

    public void broadcastLUS(long seqNo) {
        long current = currentLUS.get();
        if (seqNo <= current) {
            return;
        }
        if (currentLUS.compareAndSet(current, seqNo)) {
            for (LongConsumer listener : listeners) {
                listener.accept(seqNo);
            }
        }
    }

    public void addListener(LongConsumer listener) {
        listeners.add(listener);
    }

    public void removeListener(LongConsumer listener) {
        listeners.remove(listener);
    }

    public long getCurrentLUS() {
        return currentLUS.get();
    }
}
