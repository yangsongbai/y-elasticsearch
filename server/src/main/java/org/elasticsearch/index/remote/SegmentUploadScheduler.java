package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    public SegmentUploadScheduler(RemoteSegmentStoreDirectory remoteDirectory, ThreadPool threadPool,
                                  int parallelism, long maxBytesInFlight) {
        this.remoteDirectory = remoteDirectory;
        this.threadPool = threadPool;
        this.parallelismSemaphore = new Semaphore(parallelism);
        this.maxBytesInFlight = maxBytesInFlight;
    }

    public CompletableFuture<Void> schedule(UploadTask task) {
        if (closed.get()) {
            task.completionFuture.completeExceptionally(new IllegalStateException("scheduler closed"));
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
            threadPool.generic().execute(() -> executeUpload(task));
        }
    }

    private void executeUpload(UploadTask task) {
        try {
            remoteDirectory.uploadSegmentFile(task.fileName, task.content);
            task.completionFuture.complete(null);
        } catch (Exception e) {
            logger.warn("Failed to upload segment file [{}]", task.fileName, e);
            task.completionFuture.completeExceptionally(e);
        } finally {
            bytesPending.addAndGet(-task.content.length);
            parallelismSemaphore.release();
            drainQueue();
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
    }
}
