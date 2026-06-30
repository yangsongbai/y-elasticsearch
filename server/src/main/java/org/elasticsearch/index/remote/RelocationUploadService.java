package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongUnaryOperator;

public class RelocationUploadService {

    private static final Logger logger = LogManager.getLogger(RelocationUploadService.class);

    private final long handoffTimeoutMs;
    private final long maxTailAtHandoff;
    private final ThreadPool threadPool;
    private volatile LongUnaryOperator forceUploadCallback;

    public RelocationUploadService(long handoffTimeoutMs, long maxTailAtHandoff, ThreadPool threadPool) {
        this.handoffTimeoutMs = handoffTimeoutMs;
        this.maxTailAtHandoff = maxTailAtHandoff;
        this.threadPool = threadPool;
    }

    public void setForceUploadCallback(LongUnaryOperator callback) {
        this.forceUploadCallback = callback;
    }

    public HandoffReadiness prepareForHandoff(TailState tailState) {
        LongUnaryOperator callback = this.forceUploadCallback;
        if (callback == null) {
            logger.warn("No force upload callback registered, handoff without upload");
            return HandoffReadiness.READY;
        }

        logger.info("Preparing for handoff: LUS={}, localCP={}, tailBytes={}",
            tailState.lastUploadedSeqNo, tailState.localCheckpoint, tailState.tailSizeBytes);

        CompletableFuture<Long> future = new CompletableFuture<>();
        AtomicReference<Thread> runningThread = new AtomicReference<>();
        threadPool.generic().execute(threadPool.getThreadContext().preserveContext(new AbstractRunnable() {
            @Override
            public void onFailure(Exception e) {
                future.completeExceptionally(e);
            }

            @Override
            protected void doRun() {
                runningThread.set(Thread.currentThread());
                try {
                    if (future.isCancelled()) {
                        return;
                    }
                    long result = callback.applyAsLong(tailState.lastUploadedSeqNo);
                    future.complete(result);
                } finally {
                    runningThread.set(null);
                }
            }

            @Override
            public void onRejection(Exception e) {
                future.completeExceptionally(e);
            }
        }));

        try {
            long newLUS = future.get(handoffTimeoutMs, TimeUnit.MILLISECONDS);

            long remainingSeqNoGap = tailState.localCheckpoint - newLUS;
            if (remainingSeqNoGap > 0 && tailState.tailSizeBytes > maxTailAtHandoff) {
                logger.warn("Tail still exceeds max after upload: remaining_seqno_gap={}", remainingSeqNoGap);
                return HandoffReadiness.DELAYED;
            }

            logger.info("Force upload complete: newLUS={}, ready for handoff", newLUS);
            return HandoffReadiness.READY;
        } catch (TimeoutException e) {
            future.cancel(true);
            Thread t = runningThread.getAndSet(null);
            if (t != null) {
                t.interrupt();
            }
            logger.error("Force upload timed out after {}ms", handoffTimeoutMs);
            return HandoffReadiness.TIMEOUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            Thread t = runningThread.getAndSet(null);
            if (t != null) {
                t.interrupt();
            }
            return HandoffReadiness.FAILED;
        } catch (Exception e) {
            logger.error("Force upload failed", e);
            return HandoffReadiness.FAILED;
        }
    }

    public enum HandoffReadiness {
        READY,
        DELAYED,
        TIMEOUT,
        FAILED
    }

    public static class TailState {
        final long localCheckpoint;
        final long lastUploadedSeqNo;
        final long tailSizeBytes;

        public TailState(long localCheckpoint, long lastUploadedSeqNo, long tailSizeBytes) {
            this.localCheckpoint = localCheckpoint;
            this.lastUploadedSeqNo = lastUploadedSeqNo;
            this.tailSizeBytes = tailSizeBytes;
        }
    }
}
