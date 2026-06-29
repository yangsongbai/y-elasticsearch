package org.elasticsearch.index.remote;

import org.elasticsearch.test.ESTestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class UploadOrderCoordinatorTests extends ESTestCase {

    public void testSegmentUploadBlockedUntilTranslogUploaded() throws Exception {
        List<String> uploadOrder = new ArrayList<>();
        UploadOrderCoordinator coordinator = new UploadOrderCoordinator();

        coordinator.registerTranslogGeneration(5L);

        CountDownLatch segmentLatch = new CountDownLatch(1);
        Thread segmentThread = new Thread(() -> {
            coordinator.awaitTranslogUploadedForGeneration(5L);
            uploadOrder.add("segment");
            segmentLatch.countDown();
        });
        segmentThread.start();

        assertFalse(segmentLatch.await(200, TimeUnit.MILLISECONDS));

        uploadOrder.add("translog");
        coordinator.markTranslogUploaded(5L);

        assertTrue(segmentLatch.await(5, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("translog", "segment"), uploadOrder);
    }

    public void testSegmentUploadProceedsIfNoTranslogPending() {
        UploadOrderCoordinator coordinator = new UploadOrderCoordinator();
        coordinator.awaitTranslogUploadedForGeneration(5L);
    }

    public void testMultipleGenerationsOrderedCorrectly() throws Exception {
        UploadOrderCoordinator coordinator = new UploadOrderCoordinator();
        coordinator.registerTranslogGeneration(3L);
        coordinator.registerTranslogGeneration(4L);

        CountDownLatch latch = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            coordinator.awaitTranslogUploadedForGeneration(4L);
            latch.countDown();
        });
        t.start();

        coordinator.markTranslogUploaded(3L);
        assertFalse(latch.await(200, TimeUnit.MILLISECONDS));

        coordinator.markTranslogUploaded(4L);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
