package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xcontent.DeprecationHandler;
import org.elasticsearch.xcontent.NamedXContentRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.atomic.AtomicInteger;

public class SingleWriterLock {

    private static final Logger logger = LogManager.getLogger(SingleWriterLock.class);
    private static final String LOCK_PATH = "lock/ownership.lock";

    private final BlobContainer blobContainer;
    private final SingleWriterLockConfig config;

    private volatile boolean degraded = false;
    private volatile boolean heldLocally = false;
    private volatile long lastSuccessfulRenewMs = 0;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

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
            deleteLock();
            try {
                writeLock(primaryTerm, nodeId, true);
            } catch (IOException writeEx) {
                logger.warn("Lock contention during acquire, term={}", primaryTerm);
                return false;
            }
            if (!verifyLockHolder(nodeId)) {
                logger.warn("Lock acquired by another node after write, term={}", primaryTerm);
                return false;
            }
            heldLocally = true;
            lastSuccessfulRenewMs = System.currentTimeMillis();
            consecutiveFailures.set(0);
            degraded = false;
            return true;
        } catch (NoSuchFileException e) {
            try {
                writeLock(primaryTerm, nodeId, true);
            } catch (IOException writeEx) {
                logger.warn("Lock contention during first acquire, term={}", primaryTerm);
                return false;
            }
            if (!verifyLockHolder(nodeId)) {
                logger.warn("Lock acquired by another node after write, term={}", primaryTerm);
                return false;
            }
            heldLocally = true;
            lastSuccessfulRenewMs = System.currentTimeMillis();
            consecutiveFailures.set(0);
            degraded = false;
            return true;
        }
    }

    public RenewResult tryRenew(long primaryTerm, String nodeId) {
        try {
            long existingTerm;
            try {
                existingTerm = readCurrentTerm();
            } catch (NoSuchFileException e) {
                existingTerm = 0;
            }
            if (existingTerm > primaryTerm) {
                heldLocally = false;
                logger.warn("Lock superseded during renew: existing_term={} > our_term={}",
                    existingTerm, primaryTerm);
                return RenewResult.LEASE_EXPIRED;
            }
            writeLock(primaryTerm, nodeId, false);
            consecutiveFailures.set(0);
            lastSuccessfulRenewMs = System.currentTimeMillis();
            if (degraded) {
                degraded = false;
                logger.info("SingleWriterLock recovered from degraded mode");
            }
            return RenewResult.SUCCESS;
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            long elapsed = System.currentTimeMillis() - lastSuccessfulRenewMs;

            if (config.isFastDegradeOnFirstFailure()
                && failures >= config.getDegradeAfterFailures()) {
                degraded = true;
                logger.warn("SingleWriterLock degraded after {} consecutive failures, "
                    + "allowing local writes", failures);
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
        long existingTerm;
        try {
            existingTerm = readCurrentTerm();
        } catch (NoSuchFileException e) {
            existingTerm = 0;
        }
        if (existingTerm > primaryTerm) {
            throw new IOException("Lock superseded: existing_term=" + existingTerm + " > our_term=" + primaryTerm);
        }
        writeLock(primaryTerm, nodeId, false);
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
            byte[] data = baos.toByteArray();
            try (XContentParser parser = XContentType.JSON.xContent()
                    .createParser(NamedXContentRegistry.EMPTY, DeprecationHandler.THROW_UNSUPPORTED_OPERATION, data)) {
                parser.nextToken();
                while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                    String field = parser.currentName();
                    parser.nextToken();
                    if ("primary_term".equals(field)) {
                        return parser.longValue();
                    } else {
                        parser.skipChildren();
                    }
                }
            }
            return 0;
        }
    }

    private void writeLock(long primaryTerm, String nodeId, boolean failIfAlreadyExists) throws IOException {
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            builder.startObject();
            builder.field("primary_term", primaryTerm);
            builder.field("node_id", nodeId);
            builder.endObject();
            BytesReference bytes = BytesReference.bytes(builder);
            try (InputStream is = bytes.streamInput()) {
                blobContainer.writeBlob(LOCK_PATH, is, bytes.length(), failIfAlreadyExists);
            }
        }
    }

    private void deleteLock() {
        try {
            blobContainer.deleteBlobsIgnoringIfNotExists(java.util.Collections.singletonList(LOCK_PATH).iterator());
        } catch (IOException e) {
            logger.debug("Failed to delete existing lock before re-acquire", e);
        }
    }

    private boolean verifyLockHolder(String expectedNodeId) {
        try (InputStream is = blobContainer.readBlob(LOCK_PATH)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            byte[] data = baos.toByteArray();
            try (XContentParser parser = XContentType.JSON.xContent()
                    .createParser(NamedXContentRegistry.EMPTY, DeprecationHandler.THROW_UNSUPPORTED_OPERATION, data)) {
                parser.nextToken();
                while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                    String field = parser.currentName();
                    parser.nextToken();
                    if ("node_id".equals(field)) {
                        return expectedNodeId.equals(parser.text());
                    } else {
                        parser.skipChildren();
                    }
                }
            }
            return false;
        } catch (IOException e) {
            logger.warn("Failed to verify lock holder", e);
            return false;
        }
    }

    public enum RenewResult {
        SUCCESS,
        FAILED_TOLERABLE,
        DEGRADED,
        LEASE_EXPIRED
    }
}
