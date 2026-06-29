package org.elasticsearch.index.remote.cache;

import java.util.Comparator;
import java.util.List;

public class LFUDecayPolicy {

    private final double decayFactor;
    private final long decayIntervalMs;

    public LFUDecayPolicy(double decayFactor, long decayIntervalMs) {
        this.decayFactor = decayFactor;
        this.decayIntervalMs = decayIntervalMs;
    }

    public CacheRegion selectVictim(List<CacheRegion> candidates) {
        return candidates.stream()
            .min(Comparator.comparingLong(CacheRegion::getAccessCount))
            .orElse(null);
    }

    public void applyDecay(List<CacheRegion> regions) {
        for (CacheRegion region : regions) {
            long current = region.getAccessCount();
            region.setAccessCount((long) (current * decayFactor));
        }
    }

    public double getDecayFactor() { return decayFactor; }
    public long getDecayIntervalMs() { return decayIntervalMs; }
}
