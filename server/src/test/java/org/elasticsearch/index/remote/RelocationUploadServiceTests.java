package org.elasticsearch.index.remote;

import org.elasticsearch.test.ESTestCase;

public class RelocationUploadServiceTests extends ESTestCase {

    public void testForceUploadBlocksHandoffUntilComplete() {
        RelocationUploadService service = new RelocationUploadService(60_000L, 256 * 1024 * 1024L);
        RelocationUploadService.TailState tailState = new RelocationUploadService.TailState(100L, 80L, 50_000_000L);

        service.setForceUploadCallback(fromSeqNo -> {
            assertEquals(80L, fromSeqNo);
            return 100L;
        });

        RelocationUploadService.HandoffReadiness readiness = service.prepareForHandoff(tailState);
        assertEquals(RelocationUploadService.HandoffReadiness.READY, readiness);
    }

    public void testHandoffDelayedWhenTailExceedsMax() {
        RelocationUploadService service = new RelocationUploadService(60_000L, 256 * 1024 * 1024L);
        // Tail is 500MB — exceeds max
        RelocationUploadService.TailState tailState = new RelocationUploadService.TailState(
            100L, 80L, 500_000_000L);

        service.setForceUploadCallback(fromSeqNo -> 90L); // partial upload

        RelocationUploadService.HandoffReadiness readiness = service.prepareForHandoff(tailState);
        assertEquals(RelocationUploadService.HandoffReadiness.DELAYED, readiness);
    }

    public void testHandoffTimesOut() {
        RelocationUploadService service = new RelocationUploadService(100L, 256 * 1024 * 1024L);
        RelocationUploadService.TailState tailState = new RelocationUploadService.TailState(100L, 80L, 50_000_000L);

        service.setForceUploadCallback(fromSeqNo -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 100L;
        });

        RelocationUploadService.HandoffReadiness readiness = service.prepareForHandoff(tailState);
        assertEquals(RelocationUploadService.HandoffReadiness.TIMEOUT, readiness);
    }

    public void testHandoffReadyWithNoCallback() {
        RelocationUploadService service = new RelocationUploadService(60_000L, 256 * 1024 * 1024L);
        RelocationUploadService.TailState tailState = new RelocationUploadService.TailState(100L, 80L, 50_000_000L);

        RelocationUploadService.HandoffReadiness readiness = service.prepareForHandoff(tailState);
        assertEquals(RelocationUploadService.HandoffReadiness.READY, readiness);
    }
}
