package org.elasticsearch.index.remote;

import org.elasticsearch.test.ESTestCase;

public class RemoteStoreStatsTests extends ESTestCase {

    public void testRecordUpload() {
        RemoteStoreStats stats = new RemoteStoreStats();
        stats.recordUploadStart(1024L);
        assertEquals(1024L, stats.getBytesPending());
        assertEquals(1, stats.getUploadsInProgress());

        stats.recordUploadSuccess(1024L, 200L);
        assertEquals(0L, stats.getBytesPending());
        assertEquals(0, stats.getUploadsInProgress());
        assertEquals(1024L, stats.getTotalBytesUploaded());
        assertEquals(1, stats.getTotalUploadsSucceeded());
    }

    public void testRecordUploadFailure() {
        RemoteStoreStats stats = new RemoteStoreStats();
        stats.recordUploadStart(2048L);
        stats.recordUploadFailure(2048L);

        assertEquals(0L, stats.getBytesPending());
        assertEquals(1, stats.getTotalUploadsFailed());
    }

    public void testLagCalculation() {
        RemoteStoreStats stats = new RemoteStoreStats();
        stats.updateLocalRefreshSeqNo(100L);
        stats.updateRemoteRefreshSeqNo(95L);

        assertEquals(5L, stats.getRefreshSeqNoLag());
    }
}
