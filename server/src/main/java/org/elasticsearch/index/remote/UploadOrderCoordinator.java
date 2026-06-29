package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class UploadOrderCoordinator {

    private static final Logger logger = LogManager.getLogger(UploadOrderCoordinator.class);
    private static final long AWAIT_TIMEOUT_MS = 60_000;

    private final ConcurrentHashMap<Long, CountDownLatch> pendingTranslogs = new ConcurrentHashMap<>();

    public void registerTranslogGeneration(long generation) {
        pendingTranslogs.putIfAbsent(generation, new CountDownLatch(1));
    }

    public void markTranslogUploaded(long generation) {
        CountDownLatch latch = pendingTranslogs.remove(generation);
        if (latch != null) {
            latch.countDown();
        }
    }

    public void awaitTranslogUploadedForGeneration(long generation) {
        CountDownLatch latch = pendingTranslogs.get(generation);
        if (latch == null) {
            return;
        }
        try {
            if (!latch.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logger.warn("Timed out waiting for translog gen={} upload before segment upload", generation);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted waiting for translog gen={} upload", generation);
        }
    }
}
