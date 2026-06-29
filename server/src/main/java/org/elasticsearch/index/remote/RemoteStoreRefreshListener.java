package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.search.ReferenceManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.Store;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class RemoteStoreRefreshListener implements ReferenceManager.RefreshListener {

    private static final Logger logger = LogManager.getLogger(RemoteStoreRefreshListener.class);

    private final ShardId shardId;
    private final Store store;
    private final Directory localDirectory;
    private volatile RemoteSegmentStoreDirectory remoteDirectory;
    private volatile SegmentUploadScheduler scheduler;
    private volatile long primaryTerm;
    private final Set<String> uploadedFiles = new HashSet<>();
    private final AtomicLong lastUploadedGeneration = new AtomicLong(-1);
    private final AtomicBoolean active = new AtomicBoolean(false);

    public RemoteStoreRefreshListener(ShardId shardId, Store store, Directory localDirectory,
                                      RemoteSegmentStoreDirectory remoteDirectory,
                                      SegmentUploadScheduler scheduler, long primaryTerm) {
        this.shardId = shardId;
        this.store = store;
        this.localDirectory = localDirectory;
        this.remoteDirectory = remoteDirectory;
        this.scheduler = scheduler;
        this.primaryTerm = primaryTerm;
        this.active.set(true);
    }

    public RemoteStoreRefreshListener(ShardId shardId, Store store, Directory localDirectory) {
        this.shardId = shardId;
        this.store = store;
        this.localDirectory = localDirectory;
    }

    public void activate(RemoteSegmentStoreDirectory remoteDirectory, SegmentUploadScheduler scheduler, long primaryTerm) {
        this.remoteDirectory = remoteDirectory;
        this.scheduler = scheduler;
        this.primaryTerm = primaryTerm;
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
        try {
            uploadNewSegments();
        } catch (Exception e) {
            logger.warn("[{}] Failed to upload segments after refresh", shardId, e);
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

        for (String fileName : toUpload) {
            byte[] content = readLocalFile(fileName);
            if (content != null) {
                SegmentUploadScheduler.UploadTask task = new SegmentUploadScheduler.UploadTask(
                    fileName, content, SegmentUploadScheduler.Priority.NORMAL
                );
                scheduler.schedule(task).whenComplete((v, ex) -> {
                    if (ex == null) {
                        synchronized (uploadedFiles) {
                            uploadedFiles.add(fileName);
                        }
                    }
                });
            }
        }

        long generation = segmentInfos.getGeneration();
        Map<String, RemoteSegmentMetadata.FileInfo> fileInfos = new HashMap<>();
        for (String file : currentFiles) {
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
            byte[] content = new byte[(int) input.length()];
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
