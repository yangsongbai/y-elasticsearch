package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.blobstore.BlobContainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;

public class SingleWriterLock {

    private static final Logger logger = LogManager.getLogger(SingleWriterLock.class);
    private static final String LOCK_PATH = "lock/ownership.lock";

    private final BlobContainer blobContainer;
    private final SingleWriterLockConfig config;

    private volatile boolean degraded = false;
    private volatile boolean heldLocally = false;
    private volatile long lastSuccessfulRenewMs = 0;
    private int consecutiveFailures = 0;

    public SingleWriterLock(BlobContainer blobContainer) {
        this(blobContainer, new SingleWriterLockConfig(30000L, 2, 5000L, true));
    }

    public SingleWriterLock(BlobContainer blobContainer, SingleWriterLockConfig config) {
        this.blobContainer = blobContainer;
        this.config = config;
    }

    public boolean tryAcquire(long primaryTerm, String nodeId) throws IOException {
        try {
            long existingTerm = readCurrentTerm();
            if (existingTerm >= primaryTerm) {
                logger.warn("Lock held by primary_term={}, cannot acquire with term={}",
                    existingTerm, primaryTerm);
                return false;
            }
            writeLock(primaryTerm, nodeId);
            heldLocally = true;
            lastSuccessfulRenewMs = System.currentTimeMillis();
            consecutiveFailures = 0;
            degraded = false;
            return true;
        } catch (NoSuchFileException e) {
            writeLock(primaryTerm, nodeId);
            heldLocally = true;
            lastSuccessfulRenewMs = System.currentTimeMillis();
            consecutiveFailures = 0;
            degraded = false;
            return true;
        }
    }

    public RenewResult tryRenew(long primaryTerm, String nodeId) {
        try {
            writeLock(primaryTerm, nodeId);
            consecutiveFailures = 0;
            lastSuccessfulRenewMs = System.currentTimeMillis();
            if (degraded) {
                degraded = false;
                logger.info("SingleWriterLock recovered from degraded mode");
            }
            return RenewResult.SUCCESS;
        } catch (IOException e) {
            consecutiveFailures++;
            long elapsed = System.currentTimeMillis() - lastSuccessfulRenewMs;

            if (config.isFastDegradeOnFirstFailure()
                && consecutiveFailures >= config.getDegradeAfterFailures()) {
                degraded = true;
                logger.warn("SingleWriterLock degraded after {} consecutive failures, "
                    + "allowing local writes", consecutiveFailures);
                return RenewResult.DEGRADED;
            }

            if (elapsed > config.getLeaseToleranceMs()) {
                heldLocally = false;
                logger.error("SingleWriterLock lease expired, tolerance={}ms elapsed={}ms",
                    config.getLeaseToleranceMs(), elapsed);
                return RenewResult.LEASE_EXPIRED;
            }

            return RenewResult.FAILED_TOLERABLE;
        }
    }

    public void renew(long primaryTerm, String nodeId) throws IOException {
        writeLock(primaryTerm, nodeId);
    }

    public boolean isHeldLocally() {
        return heldLocally;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public boolean allowWriteInDegradedMode() {
        return degraded && heldLocally;
    }

    private long readCurrentTerm() throws IOException {
        try (InputStream is = blobContainer.readBlob(LOCK_PATH)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            String json = baos.toString(StandardCharsets.UTF_8.name());
            int idx = json.indexOf("\"primary_term\":");
            if (idx < 0) {
                return 0;
            }
            int start = idx + "\"primary_term\":".length();
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == ' ')) {
                end++;
            }
            return Long.parseLong(json.substring(start, end).trim());
        }
    }

    private void writeLock(long primaryTerm, String nodeId) throws IOException {
        String content = "{\"primary_term\":" + primaryTerm + ",\"node_id\":\"" + nodeId + "\"}";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            blobContainer.writeBlob(LOCK_PATH, is, bytes.length, true);
        }
    }

    public enum RenewResult {
        SUCCESS,
        FAILED_TOLERABLE,
        DEGRADED,
        LEASE_EXPIRED
    }
}
