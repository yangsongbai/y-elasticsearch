package org.elasticsearch.index.remote;

public class PrimaryPromotionConfig {

    private final int concurrent;
    private final long batchIntervalMs;
    private final boolean preselectEnabled;

    public PrimaryPromotionConfig(int concurrent, long batchIntervalMs, boolean preselectEnabled) {
        this.concurrent = concurrent;
        this.batchIntervalMs = batchIntervalMs;
        this.preselectEnabled = preselectEnabled;
    }

    public int getConcurrent() {
        return concurrent;
    }

    public long getBatchIntervalMs() {
        return batchIntervalMs;
    }

    public boolean isPreselectEnabled() {
        return preselectEnabled;
    }
}
