package org.elasticsearch.index.remote;

import java.util.concurrent.atomic.AtomicLong;

public class RemoteStoreStats {

    private final AtomicLong bytesPending = new AtomicLong(0);
    private final AtomicLong uploadsInProgress = new AtomicLong(0);
    private final AtomicLong totalBytesUploaded = new AtomicLong(0);
    private final AtomicLong totalUploadsSucceeded = new AtomicLong(0);
    private final AtomicLong totalUploadsFailed = new AtomicLong(0);
    private final AtomicLong localRefreshSeqNo = new AtomicLong(0);
    private final AtomicLong remoteRefreshSeqNo = new AtomicLong(0);

    public void recordUploadStart(long bytes) {
        bytesPending.addAndGet(bytes);
        uploadsInProgress.incrementAndGet();
    }

    public void recordUploadSuccess(long bytes, long durationMs) {
        bytesPending.addAndGet(-bytes);
        uploadsInProgress.decrementAndGet();
        totalBytesUploaded.addAndGet(bytes);
        totalUploadsSucceeded.incrementAndGet();
    }

    public void recordUploadFailure(long bytes) {
        bytesPending.addAndGet(-bytes);
        uploadsInProgress.decrementAndGet();
        totalUploadsFailed.incrementAndGet();
    }

    public void updateLocalRefreshSeqNo(long seqNo) {
        localRefreshSeqNo.set(seqNo);
    }

    public void updateRemoteRefreshSeqNo(long seqNo) {
        remoteRefreshSeqNo.set(seqNo);
    }

    public long getBytesPending() {
        return bytesPending.get();
    }

    public int getUploadsInProgress() {
        return (int) uploadsInProgress.get();
    }

    public long getTotalBytesUploaded() {
        return totalBytesUploaded.get();
    }

    public int getTotalUploadsSucceeded() {
        return (int) totalUploadsSucceeded.get();
    }

    public int getTotalUploadsFailed() {
        return (int) totalUploadsFailed.get();
    }

    public long getRefreshSeqNoLag() {
        return localRefreshSeqNo.get() - remoteRefreshSeqNo.get();
    }
}
