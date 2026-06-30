package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class SegmentUploadScheduler implements Closeable {

    private static final Logger logger = LogManager.getLogger(SegmentUploadScheduler.class);

    public enum Priority {
        LOW(0), NORMAL(1), HIGH(2), HIGHEST(3);
        final int value;
        Priority(int value) {
            this.value = value;
        }
    }

    public static class UploadTask implements Comparable<UploadTask> {
        private final String fileName;
        private final byte[] content;
        private final Priority priority;
        private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

        public UploadTask(String fileName, byte[] content, Priority priority) {
            this.fileName = fileName;
            this.content = content;
            this.priority = priority;
        }

        public String fileName() {
            return fileName;
        }

        public byte[] content() {
            return content;
        }

        public Priority priority() {
            return priority;
        }

        public CompletableFuture<Void> completion() {
            return completionFuture;
        }

        @Override
        public int compareTo(UploadTask other) {
            return Integer.compare(other.priority.value, this.priority.value);
        }
    }

    private final RemoteSegmentStoreDirectory remoteDirectory;
    private final ThreadPool threadPool;
    private final Semaphore parallelismSemaphore;
    private final long maxBytesInFlight;
    private final AtomicLong bytesPending = new AtomicLong(0);
    private final PriorityBlockingQueue<UploadTask> queue = new PriorityBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile BackpressureController backpressureController;

    public SegmentUploadScheduler(RemoteSegmentStoreDirectory remoteDirectory, ThreadPool threadPool,
                                  int parallelism, long maxBytesInFlight) {
        this(remoteDirectory, threadPool, parallelism, maxBytesInFlight, null);
    }

    public SegmentUploadScheduler(RemoteSegmentStoreDirectory remoteDirectory, ThreadPool threadPool,
                                  int parallelism, long maxBytesInFlight,
                                  BackpressureController backpressureController) {
        this.remoteDirectory = remoteDirectory;
        this.threadPool = threadPool;
        this.parallelismSemaphore = new Semaphore(parallelism);
        this.maxBytesInFlight = maxBytesInFlight;
        this.backpressureController = backpressureController;
    }

    public void setBackpressureController(BackpressureController controller) {
        this.backpressureController = controller;
    }

    public CompletableFuture<Void> schedule(UploadTask task) {
        if (closed.get()) {
            task.completionFuture.completeExceptionally(new IllegalStateException("scheduler closed"));
            return task.completionFuture;
        }
        BackpressureController bp = this.backpressureController;
        if (bp != null && !bp.allowWrite()) {
            task.completionFuture.completeExceptionally(
                new IllegalStateException("backpressure: writes blocked at level [" + bp.getLevel() + "]"));
            return task.completionFuture;
        }
        long pending = bytesPending.addAndGet(task.content.length);
        if (pending > maxBytesInFlight) {
            bytesPending.addAndGet(-task.content.length);
            task.completionFuture.completeExceptionally(
                new IllegalStateException("backpressure: bytes pending [" + (pending - task.content.length)
                    + "] exceeds max [" + maxBytesInFlight + "]"));
            return task.completionFuture;
        }
        queue.offer(task);
        drainQueue();
        return task.completionFuture;
    }

    private void drainQueue() {
        while (!queue.isEmpty() && parallelismSemaphore.tryAcquire()) {
            UploadTask task = queue.poll();
            if (task == null) {
                parallelismSemaphore.release();
                break;
            }
            final UploadTask capturedTask = task;
            threadPool.generic().execute(threadPool.getThreadContext().preserveContext(new AbstractRunnable() {
                @Override
                public void onFailure(Exception e) {
                    logger.warn("Failed to upload segment file [{}]", capturedTask.fileName, e);
                    capturedTask.completionFuture.completeExceptionally(e);
                    bytesPending.addAndGet(-capturedTask.content.length);
                    parallelismSemaphore.release();
                }

                @Override
                protected void doRun() {
                    executeUpload(capturedTask);
                }

                @Override
                public void onRejection(Exception e) {
                    capturedTask.completionFuture.completeExceptionally(e);
                    bytesPending.addAndGet(-capturedTask.content.length);
                    parallelismSemaphore.release();
                }
            }));
        }
    }

    private void executeUpload(UploadTask task) {
        try {
            remoteDirectory.uploadSegmentFile(task.fileName, task.content);
            task.completionFuture.complete(null);
            BackpressureController bp = this.backpressureController;
            if (bp != null) {
                bp.recordSuccess();
            }
        } catch (Exception e) {
            logger.warn("Failed to upload segment file [{}]", task.fileName, e);
            task.completionFuture.completeExceptionally(e);
            BackpressureController bp = this.backpressureController;
            if (bp != null) {
                try {
                    bp.recordFailure();
                } catch (Exception suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
        } finally {
            bytesPending.addAndGet(-task.content.length);
            parallelismSemaphore.release();
            try {
                drainQueue();
            } catch (Exception e) {
                logger.warn("Failed to drain upload queue", e);
            }
        }
    }

    public long getBytesPending() {
        return bytesPending.get();
    }

    public long getMaxBytesInFlight() {
        return maxBytesInFlight;
    }

    @Override
    public void close() {
        closed.set(true);
        UploadTask task;
        while ((task = queue.poll()) != null) {
            task.completionFuture.completeExceptionally(new IllegalStateException("scheduler closed"));
            bytesPending.addAndGet(-task.content.length);
        }
    }
}
