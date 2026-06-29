package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.search.ReferenceManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.elasticsearch.index.remote.observability.RemoteStoreTracer;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class RemoteStoreRefreshListener implements ReferenceManager.RefreshListener {

    private static final Logger logger = LogManager.getLogger(RemoteStoreRefreshListener.class);
    private static final long UPLOAD_TIMEOUT_SECONDS = 300;
    private static final long MAX_FILE_SIZE_BYTES = Integer.MAX_VALUE;

    private final ShardId shardId;
    private final Store store;
    private final Directory localDirectory;
    private volatile RemoteSegmentStoreDirectory remoteDirectory;
    private volatile SegmentUploadScheduler scheduler;
    private volatile long primaryTerm;
    private final Set<String> uploadedFiles = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastUploadedGeneration = new AtomicLong(-1);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile RemoteStoreTracer tracer;
    private volatile ThreadPool threadPool;

    public RemoteStoreRefreshListener(ShardId shardId, Store store, Directory localDirectory,
                                      RemoteSegmentStoreDirectory remoteDirectory,
                                      SegmentUploadScheduler scheduler, long primaryTerm,
                                      ThreadPool threadPool) {
        this.shardId = shardId;
        this.store = store;
        this.localDirectory = localDirectory;
        this.remoteDirectory = remoteDirectory;
        this.scheduler = scheduler;
        this.primaryTerm = primaryTerm;
        this.threadPool = threadPool;
        this.active.set(true);
        this.tracer = new RemoteStoreTracer(true, 0.1);
    }

    public RemoteStoreRefreshListener(ShardId shardId, Store store, Directory localDirectory) {
        this.shardId = shardId;
        this.store = store;
        this.localDirectory = localDirectory;
        this.tracer = new RemoteStoreTracer(false, 0.0);
    }

    public void activate(RemoteSegmentStoreDirectory remoteDirectory, SegmentUploadScheduler scheduler,
                         long primaryTerm, ThreadPool threadPool) {
        this.remoteDirectory = remoteDirectory;
        this.scheduler = scheduler;
        this.primaryTerm = primaryTerm;
        this.threadPool = threadPool;
        this.active.set(true);
    }

    @Override
    public void beforeRefresh() {
    }

    @Override
    public void afterRefresh(boolean didRefresh) {
        if (!didRefresh || !active.get()) {
            return;
        }
        ThreadPool tp = this.threadPool;
        if (tp != null) {
            tp.generic().execute(() -> {
                try {
                    uploadNewSegments();
                } catch (Exception e) {
                    logger.warn("[{}] Failed to upload segments after refresh", shardId, e);
                }
            });
        }
    }

    public boolean isActive() {
        return active.get();
    }

    private void uploadNewSegments() throws IOException {
        SegmentInfos segmentInfos = store.readLastCommittedSegmentsInfo();
        Set<String> currentFiles = new HashSet<>(segmentInfos.files(true));

        Set<String> toUpload = new HashSet<>(currentFiles);
        toUpload.removeAll(uploadedFiles);

        if (toUpload.isEmpty()) {
            return;
        }

        List<CompletableFuture<Void>> uploadFutures = new ArrayList<>();
        Set<String> attemptedFiles = new HashSet<>();

        for (String fileName : toUpload) {
            byte[] content = readLocalFile(fileName);
            if (content != null) {
                attemptedFiles.add(fileName);
                RemoteStoreTracer.SpanHandle span = tracer.startSpan("segment_upload", shardId + "/" + fileName);
                span.addAttribute("file_size", content.length);
                SegmentUploadScheduler.UploadTask task = new SegmentUploadScheduler.UploadTask(
                    fileName, content, SegmentUploadScheduler.Priority.NORMAL
                );
                CompletableFuture<Void> future = scheduler.schedule(task).whenComplete((v, ex) -> {
                    span.end(ex == null);
                    if (ex == null) {
                        uploadedFiles.add(fileName);
                    }
                });
                uploadFutures.add(future);
            }
        }

        if (uploadFutures.isEmpty()) {
            return;
        }

        CompletableFuture<Void> allFuture = CompletableFuture.allOf(
            uploadFutures.toArray(new CompletableFuture<?>[0]));

        try {
            allFuture.get(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warn("[{}] Segment upload timed out after [{}s], cancelling in-flight uploads",
                shardId, UPLOAD_TIMEOUT_SECONDS);
            for (CompletableFuture<Void> f : uploadFutures) {
                f.cancel(true);
            }
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[{}] Segment upload interrupted, cancelling in-flight uploads", shardId);
            for (CompletableFuture<Void> f : uploadFutures) {
                f.cancel(true);
            }
            return;
        } catch (Exception e) {
            logger.warn("[{}] Segment upload failed, skipping metadata upload", shardId, e);
            return;
        }

        Set<String> successfullyUploaded = new HashSet<>(uploadedFiles);
        successfullyUploaded.retainAll(currentFiles);

        if (successfullyUploaded.isEmpty()) {
            return;
        }

        long generation = segmentInfos.getGeneration();
        Map<String, RemoteSegmentMetadata.FileInfo> fileInfos = new HashMap<>();
        for (String file : successfullyUploaded) {
            try {
                long size = localDirectory.fileLength(file);
                fileInfos.put(file, new RemoteSegmentMetadata.FileInfo(size, ""));
            } catch (IOException e) {
                logger.warn("[{}] Failed to get file info for [{}]", shardId, file, e);
            }
        }

        RemoteSegmentMetadata metadata = new RemoteSegmentMetadata(
            primaryTerm, generation, segmentInfos.getGeneration(), fileInfos
        );

        try {
            remoteDirectory.uploadMetadata(metadata);
            lastUploadedGeneration.set(generation);
        } catch (IOException e) {
            logger.warn("[{}] Failed to upload segment metadata", shardId, e);
        }
    }

    private byte[] readLocalFile(String fileName) {
        try (IndexInput input = localDirectory.openInput(fileName, IOContext.READONCE)) {
            long fileLength = input.length();
            if (fileLength > MAX_FILE_SIZE_BYTES) {
                logger.warn("[{}] Segment file [{}] is too large ([{}] bytes, max [{}]), skipping upload",
                    shardId, fileName, fileLength, MAX_FILE_SIZE_BYTES);
                return null;
            }
            byte[] content = new byte[(int) fileLength];
            input.readBytes(content, 0, content.length);
            return content;
        } catch (IOException e) {
            logger.warn("[{}] Failed to read local file [{}]", shardId, fileName, e);
            return null;
        }
    }

    public long getLastUploadedGeneration() {
        return lastUploadedGeneration.get();
    }
}
