/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.replica;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine for Lean Sync Replicas that tracks segment-to-seqNo mappings and
 * prunes tail segments once their data has been confirmed uploaded (LUS advances past them).
 */
public class LeanSyncReplicaEngine {

    private static final Logger logger = LogManager.getLogger(LeanSyncReplicaEngine.class);

    private final TailDirectory tailDirectory;
    private final LUSBroadcastService lusService;
    private final Map<String, Long> segmentSeqNos = new ConcurrentHashMap<>();
    private volatile long lastUploadedSeqNo = -1;

    public LeanSyncReplicaEngine(TailDirectory tailDirectory, LUSBroadcastService lusService) {
        this.tailDirectory = tailDirectory;
        this.lusService = lusService;
        this.lusService.addListener(this::onLUSUpdate);
    }

    private void onLUSUpdate(long newLUS) {
        this.lastUploadedSeqNo = newLUS;
        logger.debug("LUS advanced to {}, pruning eligible tail segments", newLUS);
    }

    public void recordSegmentSeqNo(String fileName, long maxSeqNo) {
        segmentSeqNos.put(fileName, maxSeqNo);
    }

    public boolean isPrunable(String fileName) {
        Long seqNo = segmentSeqNos.get(fileName);
        if (seqNo == null) {
            return false;
        }
        return seqNo <= lastUploadedSeqNo;
    }

    public int getTailSegmentCount() {
        int count = 0;
        for (Long seqNo : segmentSeqNos.values()) {
            if (seqNo > lastUploadedSeqNo) {
                count++;
            }
        }
        return count;
    }

    public long getLastUploadedSeqNo() {
        return lastUploadedSeqNo;
    }

    public void pruneTailSegments() {
        segmentSeqNos.entrySet().removeIf(entry -> {
            if (entry.getValue() <= lastUploadedSeqNo) {
                try {
                    tailDirectory.deleteFile(entry.getKey());
                } catch (Exception e) {
                    logger.warn("Failed to prune tail segment [{}]", entry.getKey(), e);
                }
                return true;
            }
            return false;
        });
    }
}
