/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class MetricCollector {

    private final Map<String, Double> diskUsage = new ConcurrentHashMap<>();
    private volatile double p95Latency = 0;
    private volatile double p99Latency = 0;
    private volatile int queueSize = 0;
    private volatile int rejections = 0;
    private volatile double cpuUsage = 0;
    private volatile double memoryUsage = 0;
    private volatile double writeRate = 0;
    private final AtomicReference<Map<String, Integer>> nodeCountByTier =
        new AtomicReference<>(Collections.emptyMap());

    public void recordDiskUsage(String nodeId, double usage) {
        diskUsage.put(nodeId, usage);
    }

    public void recordLatency(double p95, double p99) {
        this.p95Latency = p95;
        this.p99Latency = p99;
    }

    public void recordQueueStats(int queueSize, int rejections) {
        this.queueSize = queueSize;
        this.rejections = rejections;
    }

    public void recordComputeStats(double cpu, double memory) {
        this.cpuUsage = cpu;
        this.memoryUsage = memory;
    }

    public void recordWriteRate(double bytesPerSec) {
        this.writeRate = bytesPerSec;
    }

    public void updateNodeCounts(Map<String, Integer> counts) {
        nodeCountByTier.set(counts);
    }

    public MetricSnapshot snapshot() {
        return new MetricSnapshot(
            new HashMap<>(diskUsage), p95Latency, p99Latency,
            queueSize, rejections, cpuUsage, memoryUsage,
            writeRate, nodeCountByTier.get()
        );
    }
}
