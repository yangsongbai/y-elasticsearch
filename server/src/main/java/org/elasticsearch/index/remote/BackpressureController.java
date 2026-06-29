package org.elasticsearch.index.remote;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class BackpressureController {

    public enum Level { NORMAL, WARN, BACKPRESSURE, BLOCK }

    private final int failureThreshold;
    private final double warnDiskThreshold;
    private final double blockDiskThreshold;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile double currentDiskUsage = 0.0;
    private final AtomicReference<Level> level = new AtomicReference<>(Level.NORMAL);

    public BackpressureController(int failureThreshold, double warnDiskThreshold, double blockDiskThreshold) {
        this.failureThreshold = failureThreshold;
        this.warnDiskThreshold = warnDiskThreshold;
        this.blockDiskThreshold = blockDiskThreshold;
    }

    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        recalculateLevel(failures);
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        recalculateLevel(0);
    }

    public void updateDiskUsage(double usage) {
        this.currentDiskUsage = usage;
        recalculateLevel(consecutiveFailures.get());
    }

    private void recalculateLevel(int failures) {
        if (currentDiskUsage >= blockDiskThreshold) {
            level.set(Level.BLOCK);
        } else if (failures >= failureThreshold && currentDiskUsage >= warnDiskThreshold) {
            level.set(Level.BACKPRESSURE);
        } else if (failures >= failureThreshold) {
            level.set(Level.WARN);
        } else {
            level.set(Level.NORMAL);
        }
    }

    public Level getLevel() {
        return level.get();
    }

    public boolean allowWrite() {
        return level.get() != Level.BLOCK;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
}
