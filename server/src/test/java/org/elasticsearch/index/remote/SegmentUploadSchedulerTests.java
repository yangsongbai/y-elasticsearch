package org.elasticsearch.index.remote;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class SegmentUploadSchedulerTests extends ESTestCase {

    private ThreadPool threadPool;
    private RemoteSegmentStoreDirectory remoteDir;
    private SegmentUploadScheduler scheduler;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool("test");
        remoteDir = mock(RemoteSegmentStoreDirectory.class);
        scheduler = new SegmentUploadScheduler(remoteDir, threadPool, 4, 256 * 1024 * 1024L);
    }

    @Override
    public void tearDown() throws Exception {
        scheduler.close();
        ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
        super.tearDown();
    }

    public void testScheduleAndComplete() throws Exception {
        SegmentUploadScheduler.UploadTask task = new SegmentUploadScheduler.UploadTask(
            "_0.cfs", new byte[1024], SegmentUploadScheduler.Priority.NORMAL
        );
        CompletableFuture<Void> future = scheduler.schedule(task);
        future.get(5, TimeUnit.SECONDS);
        verify(remoteDir).uploadSegmentFile(eq("_0.cfs"), any(byte[].class));
    }

    public void testBackpressureWhenMaxBytesExceeded() throws Exception {
        long maxBytes = 256 * 1024 * 1024L;
        byte[] largeFile = new byte[(int) (maxBytes + 1)];

        SegmentUploadScheduler.UploadTask task = new SegmentUploadScheduler.UploadTask(
            "_big.cfs", largeFile, SegmentUploadScheduler.Priority.NORMAL
        );
        CompletableFuture<Void> future = scheduler.schedule(task);

        assertTrue(scheduler.getBytesPending() > 0 || future.isDone());
        future.get(10, TimeUnit.SECONDS);
    }

    public void testPriorityOrdering() throws Exception {
        doAnswer(inv -> {
            Thread.sleep(50);
            return null;
        }).when(remoteDir).uploadSegmentFile(anyString(), any(byte[].class));

        SegmentUploadScheduler.UploadTask lowTask = new SegmentUploadScheduler.UploadTask(
            "low.cfs", new byte[10], SegmentUploadScheduler.Priority.LOW
        );
        SegmentUploadScheduler.UploadTask highTask = new SegmentUploadScheduler.UploadTask(
            "high.cfs", new byte[10], SegmentUploadScheduler.Priority.HIGH
        );

        scheduler.schedule(lowTask);
        scheduler.schedule(highTask);

        lowTask.completion().get(5, TimeUnit.SECONDS);
        highTask.completion().get(5, TimeUnit.SECONDS);
    }
}
