package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongUnaryOperator;

public class RelocationUploadService {

    private static final Logger logger = LogManager.getLogger(RelocationUploadService.class);

    private final long handoffTimeoutMs;
    private final long maxTailAtHandoff;
    private LongUnaryOperator forceUploadCallback;

    public RelocationUploadService(long handoffTimeoutMs, long maxTailAtHandoff) {
        this.handoffTimeoutMs = handoffTimeoutMs;
        this.maxTailAtHandoff = maxTailAtHandoff;
    }

    public void setForceUploadCallback(LongUnaryOperator callback) {
        this.forceUploadCallback = callback;
    }

    public HandoffReadiness prepareForHandoff(TailState tailState) {
        if (forceUploadCallback == null) {
            logger.warn("No force upload callback registered, handoff without upload");
            return HandoffReadiness.READY;
        }

        logger.info("Preparing for handoff: LUS={}, localCP={}, tailBytes={}",
            tailState.lastUploadedSeqNo, tailState.localCheckpoint, tailState.tailSizeBytes);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> future = executor.submit(
                () -> forceUploadCallback.applyAsLong(tailState.lastUploadedSeqNo));
            long newLUS = future.get(handoffTimeoutMs, TimeUnit.MILLISECONDS);

            long remainingSeqNoGap = tailState.localCheckpoint - newLUS;
            if (remainingSeqNoGap > 0 && tailState.tailSizeBytes > maxTailAtHandoff) {
                logger.warn("Tail still exceeds max after upload: remaining_seqno_gap={}", remainingSeqNoGap);
                return HandoffReadiness.DELAYED;
            }

            logger.info("Force upload complete: newLUS={}, ready for handoff", newLUS);
            return HandoffReadiness.READY;
        } catch (TimeoutException e) {
            logger.error("Force upload timed out after {}ms", handoffTimeoutMs);
            return HandoffReadiness.TIMEOUT;
        } catch (Exception e) {
            logger.error("Force upload failed", e);
            return HandoffReadiness.FAILED;
        } finally {
            executor.shutdownNow();
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
