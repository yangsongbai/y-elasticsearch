/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.autoscaling;

import java.util.Map;

public class MetricSnapshot {

    private final Map<String, Double> diskUsageByNode;
    private final double p95LatencyMs;
    private final double p99LatencyMs;
    private final int searchQueueSize;
    private final int searchRejections;
    private final double cpuUsage;
    private final double memoryUsage;
    private final double writeRateBytesPerSec;
    private final Map<String, Integer> nodeCountByTier;

    public MetricSnapshot(Map<String, Double> diskUsageByNode, double p95LatencyMs,
                          double p99LatencyMs, int searchQueueSize, int searchRejections,
                          double cpuUsage, double memoryUsage, double writeRateBytesPerSec,
                          Map<String, Integer> nodeCountByTier) {
        this.diskUsageByNode = diskUsageByNode;
        this.p95LatencyMs = p95LatencyMs;
        this.p99LatencyMs = p99LatencyMs;
        this.searchQueueSize = searchQueueSize;
        this.searchRejections = searchRejections;
        this.cpuUsage = cpuUsage;
        this.memoryUsage = memoryUsage;
        this.writeRateBytesPerSec = writeRateBytesPerSec;
        this.nodeCountByTier = nodeCountByTier;
    }

    public Map<String, Double> diskUsageByNode() {
        return diskUsageByNode;
    }

    public double p95LatencyMs() {
        return p95LatencyMs;
    }

    public double p99LatencyMs() {
        return p99LatencyMs;
    }

    public int searchQueueSize() {
        return searchQueueSize;
    }

    public int searchRejections() {
        return searchRejections;
    }

    public double cpuUsage() {
        return cpuUsage;
    }

    public double memoryUsage() {
        return memoryUsage;
    }

    public double writeRateBytesPerSec() {
        return writeRateBytesPerSec;
    }

    public Map<String, Integer> nodeCountByTier() {
        return nodeCountByTier;
    }
}
