package org.elasticsearch.index.remote;

public class SingleWriterLockConfig {

    private final long leaseToleranceMs;
    private final int degradeAfterFailures;
    private final long lockAttemptTimeoutMs;
    private final boolean fastDegradeOnFirstFailure;

    public SingleWriterLockConfig(long leaseToleranceMs, int degradeAfterFailures,
                                   long lockAttemptTimeoutMs, boolean fastDegradeOnFirstFailure) {
        this.leaseToleranceMs = leaseToleranceMs;
        this.degradeAfterFailures = degradeAfterFailures;
        this.lockAttemptTimeoutMs = lockAttemptTimeoutMs;
        this.fastDegradeOnFirstFailure = fastDegradeOnFirstFailure;
    }

    public long getLeaseToleranceMs() {
        return leaseToleranceMs;
    }

    public int getDegradeAfterFailures() {
        return degradeAfterFailures;
    }

    public long getLockAttemptTimeoutMs() {
        return lockAttemptTimeoutMs;
    }

    public boolean isFastDegradeOnFirstFailure() {
        return fastDegradeOnFirstFailure;
    }
}
