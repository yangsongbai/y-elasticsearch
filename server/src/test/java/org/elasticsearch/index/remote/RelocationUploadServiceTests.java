package org.elasticsearch.index.remote;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.concurrent.TimeUnit;

public class RelocationUploadServiceTests extends ESTestCase {

    public void testForceUploadBlocksHandoffUntilComplete() throws Exception {
        ThreadPool threadPool = new TestThreadPool("test");
        try {
            RelocationUploadService service = new RelocationUploadService(60_000L, 256 * 1024 * 1024L, threadPool);
            RelocationUploadService.TailState tailState = new RelocationUploadService.TailState(100L, 80L, 50_000_000L);

            service.setForceUploadCallback(fromSeqNo -> {
                assertEquals(80L, fromSeqNo);
                return 100L;
            });

            RelocationUploadService.HandoffReadiness readiness = service.prepareForHandoff(tailState);
            assertEquals(RelocationUploadService.HandoffReadiness.READY, readiness);
        } finally {
            ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
        }
    }

    public void testHandoffDelayedWhenTailExceedsMax() throws Exception {
        ThreadPool threadPool = new TestThreadPool("test");
        try {
            RelocationUploadService service = new RelocationUploadService(60_000L, 256 * 1024 * 1024L, threadPool);
            RelocationUploadService.TailState tailState = new RelocationUploadService.TailState(
                100L, 80L, 500_000_000L);

            service.setForceUploadCallback(fromSeqNo -> 90L);

            RelocationUploadService.HandoffReadiness readiness = service.prepareForHandoff(tailState);
            assertEquals(RelocationUploadService.HandoffReadiness.DELAYED, readiness);
        } finally {
            ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
        }
    }

    public void testHandoffTimesOut() throws Exception {
        ThreadPool threadPool = new TestThreadPool("test");
        try {
            RelocationUploadService service = new RelocationUploadService(100L, 256 * 1024 * 1024L, threadPool);
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
        } finally {
            ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
        }
    }

    public void testHandoffReadyWithNoCallback() throws Exception {
        ThreadPool threadPool = new TestThreadPool("test");
        try {
            RelocationUploadService service = new RelocationUploadService(60_000L, 256 * 1024 * 1024L, threadPool);
            RelocationUploadService.TailState tailState = new RelocationUploadService.TailState(100L, 80L, 50_000_000L);

            RelocationUploadService.HandoffReadiness readiness = service.prepareForHandoff(tailState);
            assertEquals(RelocationUploadService.HandoffReadiness.READY, readiness);
        } finally {
            ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
        }
    }
}
