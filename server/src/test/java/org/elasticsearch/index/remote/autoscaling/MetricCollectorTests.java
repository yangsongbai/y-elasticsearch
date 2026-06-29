/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESTestCase;

public class MetricCollectorTests extends ESTestCase {

    public void testSnapshotCreation() {
        MetricCollector collector = new MetricCollector();
        collector.recordDiskUsage("node-1", 0.65);
        collector.recordDiskUsage("node-2", 0.70);
        collector.recordLatency(150.0, 250.0);
        collector.recordQueueStats(50, 0);
        collector.recordComputeStats(0.6, 0.5);
        collector.recordWriteRate(1024 * 1024 * 50L);

        MetricSnapshot snapshot = collector.snapshot();
        assertEquals(2, snapshot.diskUsageByNode().size());
        assertEquals(0.65, snapshot.diskUsageByNode().get("node-1"), 0.01);
        assertEquals(250.0, snapshot.p99LatencyMs(), 0.01);
        assertEquals(50, snapshot.searchQueueSize());
        assertEquals(0.6, snapshot.cpuUsage(), 0.01);
    }
}
