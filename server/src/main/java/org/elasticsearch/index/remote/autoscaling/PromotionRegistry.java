package org.elasticsearch.index.remote.autoscaling;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PromotionRegistry {

    private final Map<String, PromotionEntry> entries = new ConcurrentHashMap<>();

    public void register(String id, long startTime, long endTime,
                         ScaleFactors scaleFactors, boolean lockScaleDown) {
        entries.put(id, new PromotionEntry(id, startTime, endTime, scaleFactors, lockScaleDown));
    }

    public void remove(String id) {
        entries.remove(id);
    }

    public boolean hasActivePromotion() {
        long now = System.currentTimeMillis();
        for (PromotionEntry entry : entries.values()) {
            if (entry.startTime <= now && entry.endTime > now) {
                return true;
            }
        }
        return false;
    }

    public double getActiveScaleFactor(String tier) {
        long now = System.currentTimeMillis();
        double max = 1.0;
        for (PromotionEntry entry : entries.values()) {
            if (entry.startTime <= now && entry.endTime > now) {
                double factor = "warm".equals(tier) ? entry.scaleFactors.warm() : entry.scaleFactors.coord();
                max = Math.max(max, factor);
            }
        }
        return max;
    }

    public boolean isScaleDownLocked() {
        long now = System.currentTimeMillis();
        for (PromotionEntry entry : entries.values()) {
            if (entry.startTime <= now && entry.endTime > now && entry.lockScaleDown) {
                return true;
            }
        }
        return false;
    }

    // Inner classes (NOT records — Java 11)
    public static class ScaleFactors {
        private final double warm;
        private final double coord;

        public ScaleFactors(double warm, double coord) {
            this.warm = warm;
            this.coord = coord;
        }

        public double warm() { return warm; }
        public double coord() { return coord; }
    }

    private static class PromotionEntry {
        private final String id;
        private final long startTime;
        private final long endTime;
        private final ScaleFactors scaleFactors;
        private final boolean lockScaleDown;

        PromotionEntry(String id, long startTime, long endTime,
                       ScaleFactors scaleFactors, boolean lockScaleDown) {
            this.id = id;
            this.startTime = startTime;
            this.endTime = endTime;
            this.scaleFactors = scaleFactors;
            this.lockScaleDown = lockScaleDown;
        }
    }
}
