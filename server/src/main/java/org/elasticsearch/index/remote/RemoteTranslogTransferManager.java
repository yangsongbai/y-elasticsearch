package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class RemoteTranslogTransferManager implements Closeable {

    private static final Logger logger = LogManager.getLogger(RemoteTranslogTransferManager.class);

    private final BlobContainer translogBlobContainer;
    private final BlobContainer metadataBlobContainer;
    private final ThreadPool threadPool;
    private final Semaphore parallelism;
    private final AtomicLong lastUploadedGeneration = new AtomicLong(-1);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RemoteTranslogTransferManager(BlobContainer translogBlobContainer,
                                         BlobContainer metadataBlobContainer,
                                         ThreadPool threadPool, int parallelUploads) {
        this.translogBlobContainer = translogBlobContainer;
        this.metadataBlobContainer = metadataBlobContainer;
        this.threadPool = threadPool;
        this.parallelism = new Semaphore(parallelUploads);
    }

    public void uploadGeneration(Path translogFile, long generation, long primaryTerm,
                                 long minSeqNo, long maxSeqNo) throws IOException {
        String fileName = translogFile.getFileName().toString();
        long size = Files.size(translogFile);
        try (InputStream is = Files.newInputStream(translogFile)) {
            translogBlobContainer.writeBlob(fileName, is, size, true);
        }
        updateLastUploadedGeneration(generation);
    }

    public void uploadGenerationAsync(Path translogFile, long generation, long primaryTerm,
                                      long minSeqNo, long maxSeqNo) {
        if (closed.get()) {
            return;
        }
        threadPool.generic().execute(threadPool.getThreadContext().preserveContext(new AbstractRunnable() {
            @Override
            public void onFailure(Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    logger.warn("Interrupted while uploading translog generation [{}]", generation);
                } else {
                    logger.warn("Failed to upload translog generation [{}]", generation, e);
                }
            }

            @Override
            protected void doRun() throws Exception {
                if (closed.get()) {
                    return;
                }
                if (!parallelism.tryAcquire(30, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for upload permit for translog generation [" + generation + "]");
                }
                try {
                    if (closed.get()) {
                        return;
                    }
                    uploadGeneration(translogFile, generation, primaryTerm, minSeqNo, maxSeqNo);
                } finally {
                    parallelism.release();
                }
            }

            @Override
            public void onRejection(Exception e) {
                logger.debug("Translog upload rejected for generation [{}] during shutdown", generation);
            }
        }));
    }

    public void uploadTranslogMetadata(long primaryTerm, long generation,
                                       long minSeqNo, long maxSeqNo,
                                       long globalCheckpoint) throws IOException {
        String name = "translog__" + primaryTerm + "__" + generation;
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            builder.startObject();
            builder.field("primary_term", primaryTerm);
            builder.field("generation", generation);
            builder.field("min_seqno", minSeqNo);
            builder.field("max_seqno", maxSeqNo);
            builder.field("global_checkpoint", globalCheckpoint);
            builder.endObject();
            BytesReference bytes = BytesReference.bytes(builder);
            try (InputStream is = bytes.streamInput()) {
                metadataBlobContainer.writeBlob(name, is, bytes.length(), true);
            }
        }
    }

    public long getLastUploadedGeneration() {
        return lastUploadedGeneration.get();
    }

    private void updateLastUploadedGeneration(long generation) {
        long current;
        do {
            current = lastUploadedGeneration.get();
            if (generation <= current) {
                return;
            }
        } while (!lastUploadedGeneration.compareAndSet(current, generation));
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
