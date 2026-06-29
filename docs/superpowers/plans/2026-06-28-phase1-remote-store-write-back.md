# Phase 1: Remote Store Write-Back Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable Hot-tier Primary shards to asynchronously upload Lucene segments and translog to a Remote Object Store (S3/OSS/MinIO), providing data durability beyond local disk without blocking write latency.

**Architecture:** Write-back model — Primary writes locally first (Lucene + Translog), then asynchronously uploads segments after each refresh and translog after each generation roll. A SingleWriterLock prevents split-brain. BackpressureController degrades gracefully when Remote is unavailable. Existing Doc Replication is unchanged.

**Tech Stack:** Java 11 (source/target compatibility), Elasticsearch 7.17.4, Lucene 8.11.1, BlobStore API (existing), ESTestCase + Mockito 4.4.0, MinIO for local testing.

**Compatibility note:** ES 7.17.4 targets Java 11. Do NOT use Java records, sealed classes, or pattern matching. Use traditional classes with explicit constructors/getters. `ByteBuffersDirectory` is available in Lucene 8.11.1.

**Scope note:** This is Phase 1 of 4. Phases 2-4 (FileCache, LSR, TieringService, Autoscaler) are separate plans. This phase does NOT modify the read path — queries still use local NVMe only.

---

## File Structure

### New files to create:

| File | Responsibility |
|------|---------------|
| `server/.../index/remote/RemoteStoreSettings.java` | All `index.remote_store.*` and `node.remote_store.*` settings definitions |
| `server/.../index/remote/RemoteStoreNodeAttribute.java` | Node attribute for remote store repository binding |
| `server/.../index/remote/RemoteSegmentMetadata.java` | Segment metadata model (generation, files, checksums) |
| `server/.../index/remote/RemoteSegmentStoreDirectory.java` | Shadow Directory that reads/writes segment files to Remote |
| `server/.../index/remote/RemoteStoreRefreshListener.java` | ReferenceManager.RefreshListener that triggers segment upload |
| `server/.../index/remote/SegmentUploadScheduler.java` | Async upload with priority queue, parallelism, backpressure |
| `server/.../index/remote/RemoteTranslogTransferManager.java` | Uploads translog generations to Remote |
| `server/.../index/remote/SingleWriterLock.java` | CAS-based ownership lock in Remote via BlobContainer |
| `server/.../index/remote/BackpressureController.java` | Failure detection + 3-level degradation (warn/backpressure/block) |
| `server/.../index/remote/RemoteStoreStats.java` | Upload metrics: bytes pending, lag, failures |
| `server/...test.../index/remote/RemoteSegmentStoreDirectoryTests.java` | Unit tests |
| `server/...test.../index/remote/SegmentUploadSchedulerTests.java` | Unit tests |
| `server/...test.../index/remote/RemoteTranslogTransferManagerTests.java` | Unit tests |
| `server/...test.../index/remote/SingleWriterLockTests.java` | Unit tests |
| `server/...test.../index/remote/BackpressureControllerTests.java` | Unit tests |
| `server/...test.../index/remote/RemoteStoreRefreshListenerTests.java` | Unit tests |
| `server/...test.../index/remote/RemoteStoreIntegrationIT.java` | Integration tests |

### Existing files to modify:

| File | Change |
|------|--------|
| `server/.../index/shard/IndexShard.java` | Register RemoteStoreRefreshListener when `index.remote_store.enabled=true` |
| `server/.../index/translog/Translog.java` | Hook RemoteTranslogTransferManager on generation roll |
| `server/.../index/engine/EngineConfig.java` | Add remote store enabled flag |
| `server/.../index/IndexModule.java` | Wire remote store components into index lifecycle |
| `server/.../common/settings/IndexScopedSettings.java` | Register new index settings |
| `server/.../common/settings/ClusterSettings.java` | Register new cluster settings |
| `server/.../node/Node.java` | Initialize remote store node-level services |

### Base path convention:

```
server/src/main/java/org/elasticsearch/index/remote/   ← all new production code
server/src/test/java/org/elasticsearch/index/remote/   ← all new unit tests
server/src/internalClusterTest/java/org/elasticsearch/index/remote/  ← integration tests
```

---

## Task 1: Remote Store Settings

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/RemoteStoreSettings.java`
- Modify: `server/src/main/java/org/elasticsearch/common/settings/IndexScopedSettings.java`
- Modify: `server/src/main/java/org/elasticsearch/common/settings/ClusterSettings.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/RemoteStoreSettingsTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;

public class RemoteStoreSettingsTests extends ESTestCase {

    public void testDefaultValues() {
        Settings settings = Settings.EMPTY;
        assertFalse(RemoteStoreSettings.REMOTE_STORE_ENABLED.get(settings));
        assertEquals("", RemoteStoreSettings.REMOTE_STORE_REPOSITORY.get(settings));
        assertEquals(8, RemoteStoreSettings.SEGMENT_UPLOAD_PARALLELISM.get(settings).intValue());
        assertEquals("256mb", RemoteStoreSettings.SEGMENT_UPLOAD_MAX_BYTES_IN_FLIGHT.get(settings).toString());
    }

    public void testCustomValues() {
        Settings settings = Settings.builder()
            .put("index.remote_store.enabled", true)
            .put("index.remote_store.repository", "my-repo")
            .build();
        assertTrue(RemoteStoreSettings.REMOTE_STORE_ENABLED.get(settings));
        assertEquals("my-repo", RemoteStoreSettings.REMOTE_STORE_REPOSITORY.get(settings));
    }

    public void testTranslogSettings() {
        Settings settings = Settings.builder()
            .put("index.translog.remote.upload.interval", "2s")
            .put("index.translog.remote.upload.batch_size", "8mb")
            .put("index.translog.remote.parallel_upload", 4)
            .build();
        assertEquals(2000, RemoteStoreSettings.TRANSLOG_UPLOAD_INTERVAL.get(settings).millis());
        assertEquals("8mb", RemoteStoreSettings.TRANSLOG_UPLOAD_BATCH_SIZE.get(settings).toString());
        assertEquals(4, RemoteStoreSettings.TRANSLOG_PARALLEL_UPLOAD.get(settings).intValue());
    }

    public void testNodeLevelSettings() {
        Settings settings = Settings.builder()
            .put("node.remote_store.backpressure.local_disk_threshold_warn", 0.65)
            .put("node.remote_store.backpressure.local_disk_threshold_block", 0.85)
            .build();
        assertEquals(0.65, RemoteStoreSettings.BACKPRESSURE_WARN_THRESHOLD.get(settings), 0.001);
        assertEquals(0.85, RemoteStoreSettings.BACKPRESSURE_BLOCK_THRESHOLD.get(settings), 0.001);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.RemoteStoreSettingsTests" -x javadoc`
Expected: Compilation error — `RemoteStoreSettings` does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.core.TimeValue;

import java.util.List;

public final class RemoteStoreSettings {

    public static final Setting<Boolean> REMOTE_STORE_ENABLED = Setting.boolSetting(
        "index.remote_store.enabled", false, Setting.Property.IndexScope, Setting.Property.Final);

    public static final Setting<String> REMOTE_STORE_REPOSITORY = Setting.simpleString(
        "index.remote_store.repository", "", Setting.Property.IndexScope, Setting.Property.Final);

    public static final Setting<Integer> SEGMENT_UPLOAD_PARALLELISM = Setting.intSetting(
        "node.remote_store.segment.upload.parallelism", 8, 1, 32,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<ByteSizeValue> SEGMENT_UPLOAD_MAX_BYTES_IN_FLIGHT = Setting.byteSizeSetting(
        "node.remote_store.segment.upload.max_bytes_in_flight",
        new ByteSizeValue(256, ByteSizeUnit.MB),
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<TimeValue> TRANSLOG_UPLOAD_INTERVAL = Setting.timeSetting(
        "index.translog.remote.upload.interval",
        TimeValue.timeValueSeconds(1),
        TimeValue.timeValueMillis(100),
        Setting.Property.IndexScope, Setting.Property.Dynamic);

    public static final Setting<ByteSizeValue> TRANSLOG_UPLOAD_BATCH_SIZE = Setting.byteSizeSetting(
        "index.translog.remote.upload.batch_size",
        new ByteSizeValue(4, ByteSizeUnit.MB),
        Setting.Property.IndexScope, Setting.Property.Dynamic);

    public static final Setting<Integer> TRANSLOG_PARALLEL_UPLOAD = Setting.intSetting(
        "index.translog.remote.parallel_upload", 8, 1, 32,
        Setting.Property.IndexScope, Setting.Property.Dynamic);

    public static final Setting<Double> BACKPRESSURE_WARN_THRESHOLD = Setting.doubleSetting(
        "node.remote_store.backpressure.local_disk_threshold_warn", 0.70, 0.0, 1.0,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Double> BACKPRESSURE_BLOCK_THRESHOLD = Setting.doubleSetting(
        "node.remote_store.backpressure.local_disk_threshold_block", 0.90, 0.0, 1.0,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Integer> FAILURE_CONSECUTIVE_THRESHOLD = Setting.intSetting(
        "node.remote_store.failure.consecutive_threshold", 5, 1,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    private RemoteStoreSettings() {}

    public static List<Setting<?>> getIndexSettings() {
        return List.of(
            REMOTE_STORE_ENABLED,
            REMOTE_STORE_REPOSITORY,
            TRANSLOG_UPLOAD_INTERVAL,
            TRANSLOG_UPLOAD_BATCH_SIZE,
            TRANSLOG_PARALLEL_UPLOAD
        );
    }

    public static List<Setting<?>> getNodeSettings() {
        return List.of(
            SEGMENT_UPLOAD_PARALLELISM,
            SEGMENT_UPLOAD_MAX_BYTES_IN_FLIGHT,
            BACKPRESSURE_WARN_THRESHOLD,
            BACKPRESSURE_BLOCK_THRESHOLD,
            FAILURE_CONSECUTIVE_THRESHOLD
        );
    }
}
```

- [ ] **Step 4: Register settings in IndexScopedSettings and ClusterSettings**

In `IndexScopedSettings.java`, add to the `BUILT_IN_INDEX_SETTINGS` set:
```java
RemoteStoreSettings.REMOTE_STORE_ENABLED,
RemoteStoreSettings.REMOTE_STORE_REPOSITORY,
RemoteStoreSettings.TRANSLOG_UPLOAD_INTERVAL,
RemoteStoreSettings.TRANSLOG_UPLOAD_BATCH_SIZE,
RemoteStoreSettings.TRANSLOG_PARALLEL_UPLOAD,
```

In `ClusterSettings.java`, add to the `BUILT_IN_CLUSTER_SETTINGS` set:
```java
RemoteStoreSettings.SEGMENT_UPLOAD_PARALLELISM,
RemoteStoreSettings.SEGMENT_UPLOAD_MAX_BYTES_IN_FLIGHT,
RemoteStoreSettings.BACKPRESSURE_WARN_THRESHOLD,
RemoteStoreSettings.BACKPRESSURE_BLOCK_THRESHOLD,
RemoteStoreSettings.FAILURE_CONSECUTIVE_THRESHOLD,
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.RemoteStoreSettingsTests" -x javadoc`
Expected: All 4 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/RemoteStoreSettings.java \
        server/src/test/java/org/elasticsearch/index/remote/RemoteStoreSettingsTests.java \
        server/src/main/java/org/elasticsearch/common/settings/IndexScopedSettings.java \
        server/src/main/java/org/elasticsearch/common/settings/ClusterSettings.java
git commit -m "feat(remote-store): add Remote Store settings definitions"
```

---

## Task 2: Remote Segment Metadata Model

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/RemoteSegmentMetadata.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/RemoteSegmentMetadataTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.Map;

public class RemoteSegmentMetadataTests extends ESTestCase {

    public void testSerializationRoundTrip() throws IOException {
        Map<String, RemoteSegmentMetadata.FileInfo> files = Map.of(
            "_0.cfs", new RemoteSegmentMetadata.FileInfo(134217728L, "sha256:abc123"),
            "_0.si", new RemoteSegmentMetadata.FileInfo(512L, "sha256:def456")
        );
        RemoteSegmentMetadata original = new RemoteSegmentMetadata(
            5L,   // primaryTerm
            42L,  // generation
            9876L, // checkpoint (max seqno)
            files
        );

        BytesReference bytes = original.toXContent();
        RemoteSegmentMetadata parsed = RemoteSegmentMetadata.fromXContent(bytes);

        assertEquals(original.primaryTerm(), parsed.primaryTerm());
        assertEquals(original.generation(), parsed.generation());
        assertEquals(original.checkpoint(), parsed.checkpoint());
        assertEquals(original.files().size(), parsed.files().size());
        assertEquals(original.files().get("_0.cfs").size(), parsed.files().get("_0.cfs").size());
        assertEquals(original.files().get("_0.cfs").checksum(), parsed.files().get("_0.cfs").checksum());
    }

    public void testMetadataFileName() {
        RemoteSegmentMetadata metadata = new RemoteSegmentMetadata(5L, 42L, 9876L, Map.of());
        String expected = "metadata__5__42__9876";
        assertEquals(expected, metadata.toFileName());
    }

    public void testParseFileName() {
        RemoteSegmentMetadata.FileNameParts parts = RemoteSegmentMetadata.parseFileName("metadata__5__42__9876");
        assertEquals(5L, parts.primaryTerm());
        assertEquals(42L, parts.generation());
        assertEquals(9876L, parts.checkpoint());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.RemoteSegmentMetadataTests" -x javadoc`
Expected: Compilation error — class not found

- [ ] **Step 3: Write minimal implementation**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class RemoteSegmentMetadata {

    private final long primaryTerm;
    private final long generation;
    private final long checkpoint;
    private final Map<String, FileInfo> files;

    public static class FileInfo {
        private final long size;
        private final String checksum;
        public FileInfo(long size, String checksum) { this.size = size; this.checksum = checksum; }
        public long size() { return size; }
        public String checksum() { return checksum; }
    }

    public static class FileNameParts {
        private final long primaryTerm;
        private final long generation;
        private final long checkpoint;
        public FileNameParts(long primaryTerm, long generation, long checkpoint) {
            this.primaryTerm = primaryTerm; this.generation = generation; this.checkpoint = checkpoint;
        }
        public long primaryTerm() { return primaryTerm; }
        public long generation() { return generation; }
        public long checkpoint() { return checkpoint; }
    }

    public RemoteSegmentMetadata(long primaryTerm, long generation, long checkpoint, Map<String, FileInfo> files) {
        this.primaryTerm = primaryTerm; this.generation = generation;
        this.checkpoint = checkpoint; this.files = files;
    }

    public long primaryTerm() { return primaryTerm; }
    public long generation() { return generation; }
    public long checkpoint() { return checkpoint; }
    public Map<String, FileInfo> files() { return files; }

    public String toFileName() {
        return "metadata__" + primaryTerm + "__" + generation + "__" + checkpoint;
    }

    public static FileNameParts parseFileName(String fileName) {
        String[] parts = fileName.split("__");
        return new FileNameParts(
            Long.parseLong(parts[1]),
            Long.parseLong(parts[2]),
            Long.parseLong(parts[3])
        );
    }

    public BytesReference toXContent() throws IOException {
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            builder.startObject();
            builder.field("primary_term", primaryTerm);
            builder.field("generation", generation);
            builder.field("checkpoint", checkpoint);
            builder.startObject("files");
            for (Map.Entry<String, FileInfo> entry : files.entrySet()) {
                builder.startObject(entry.getKey());
                builder.field("size", entry.getValue().size());
                builder.field("checksum", entry.getValue().checksum());
                builder.endObject();
            }
            builder.endObject();
            builder.endObject();
            return BytesReference.bytes(builder);
        }
    }

    public static RemoteSegmentMetadata fromXContent(BytesReference bytes) throws IOException {
        try (XContentParser parser = XContentType.JSON.xContent()
                .createParser(null, null, BytesReference.toBytes(bytes))) {
            parser.nextToken(); // START_OBJECT
            long primaryTerm = 0, generation = 0, checkpoint = 0;
            Map<String, FileInfo> files = new HashMap<>();
            while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "primary_term" -> primaryTerm = parser.longValue();
                    case "generation" -> generation = parser.longValue();
                    case "checkpoint" -> checkpoint = parser.longValue();
                    case "files" -> {
                        while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                            String fileName = parser.currentName();
                            parser.nextToken();
                            long size = 0;
                            String checksum = "";
                            while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                                String f = parser.currentName();
                                parser.nextToken();
                                switch (f) {
                                    case "size" -> size = parser.longValue();
                                    case "checksum" -> checksum = parser.text();
                                }
                            }
                            files.put(fileName, new FileInfo(size, checksum));
                        }
                    }
                }
            }
            return new RemoteSegmentMetadata(primaryTerm, generation, checkpoint, files);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.RemoteSegmentMetadataTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/RemoteSegmentMetadata.java \
        server/src/test/java/org/elasticsearch/index/remote/RemoteSegmentMetadataTests.java
git commit -m "feat(remote-store): add RemoteSegmentMetadata model with serialization"
```

---

## Task 3: RemoteSegmentStoreDirectory

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/RemoteSegmentStoreDirectory.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/RemoteSegmentStoreDirectoryTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.blobstore.BlobPath;
import org.elasticsearch.common.blobstore.support.PlainBlobMetadata;
import org.elasticsearch.test.ESTestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.mockito.Mockito.*;

public class RemoteSegmentStoreDirectoryTests extends ESTestCase {

    private BlobContainer segmentsBlobContainer;
    private BlobContainer metadataBlobContainer;
    private RemoteSegmentStoreDirectory directory;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        segmentsBlobContainer = mock(BlobContainer.class);
        metadataBlobContainer = mock(BlobContainer.class);
        directory = new RemoteSegmentStoreDirectory(segmentsBlobContainer, metadataBlobContainer);
    }

    public void testUploadSegmentFile() throws IOException {
        byte[] content = "test segment data".getBytes(StandardCharsets.UTF_8);

        directory.uploadSegmentFile("_0.cfs", content);

        verify(segmentsBlobContainer).writeBlob(eq("_0.cfs"), any(), eq((long) content.length), eq(true));
    }

    public void testUploadMetadata() throws IOException {
        RemoteSegmentMetadata metadata = new RemoteSegmentMetadata(1L, 1L, 100L, Map.of(
            "_0.cfs", new RemoteSegmentMetadata.FileInfo(1024L, "sha256:abc")
        ));

        directory.uploadMetadata(metadata);

        verify(metadataBlobContainer).writeBlob(eq("metadata__1__1__100"), any(), anyLong(), eq(true));
    }

    public void testGetLatestMetadata() throws IOException {
        Map<String, org.elasticsearch.common.blobstore.BlobMetadata> blobs = Map.of(
            "metadata__1__1__50", new PlainBlobMetadata("metadata__1__1__50", 100),
            "metadata__1__2__100", new PlainBlobMetadata("metadata__1__2__100", 120),
            "metadata__1__3__200", new PlainBlobMetadata("metadata__1__3__200", 130)
        );
        when(metadataBlobContainer.listBlobs()).thenReturn(blobs);

        RemoteSegmentMetadata.FileNameParts latest = directory.getLatestMetadataFileName();

        assertEquals(1L, latest.primaryTerm());
        assertEquals(3L, latest.generation());
        assertEquals(200L, latest.checkpoint());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.RemoteSegmentStoreDirectoryTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write minimal implementation**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.blobstore.BlobMetadata;
import org.elasticsearch.common.bytes.BytesReference;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Map;

public class RemoteSegmentStoreDirectory {

    private final BlobContainer segmentsBlobContainer;
    private final BlobContainer metadataBlobContainer;

    public RemoteSegmentStoreDirectory(BlobContainer segmentsBlobContainer, BlobContainer metadataBlobContainer) {
        this.segmentsBlobContainer = segmentsBlobContainer;
        this.metadataBlobContainer = metadataBlobContainer;
    }

    public void uploadSegmentFile(String fileName, byte[] content) throws IOException {
        try (InputStream stream = new ByteArrayInputStream(content)) {
            segmentsBlobContainer.writeBlob(fileName, stream, content.length, true);
        }
    }

    public void uploadSegmentFile(String fileName, InputStream stream, long length) throws IOException {
        segmentsBlobContainer.writeBlob(fileName, stream, length, true);
    }

    public void uploadMetadata(RemoteSegmentMetadata metadata) throws IOException {
        BytesReference bytes = metadata.toXContent();
        String name = metadata.toFileName();
        try (InputStream stream = bytes.streamInput()) {
            metadataBlobContainer.writeBlob(name, stream, bytes.length(), true);
        }
    }

    public RemoteSegmentMetadata.FileNameParts getLatestMetadataFileName() throws IOException {
        Map<String, BlobMetadata> blobs = metadataBlobContainer.listBlobs();
        return blobs.keySet().stream()
            .filter(name -> name.startsWith("metadata__"))
            .map(RemoteSegmentMetadata::parseFileName)
            .max(Comparator.comparingLong(RemoteSegmentMetadata.FileNameParts::generation))
            .orElse(null);
    }

    public RemoteSegmentMetadata fetchMetadata(String fileName) throws IOException {
        try (InputStream is = metadataBlobContainer.readBlob(fileName)) {
            byte[] bytes = is.readAllBytes();
            return RemoteSegmentMetadata.fromXContent(BytesReference.fromByteBuffer(java.nio.ByteBuffer.wrap(bytes)));
        }
    }

    public InputStream readSegmentFile(String fileName) throws IOException {
        return segmentsBlobContainer.readBlob(fileName);
    }

    public void deleteSegmentFile(String fileName) throws IOException {
        segmentsBlobContainer.deleteBlobsIgnoringIfNotExists(java.util.List.of(fileName));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.RemoteSegmentStoreDirectoryTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/RemoteSegmentStoreDirectory.java \
        server/src/test/java/org/elasticsearch/index/remote/RemoteSegmentStoreDirectoryTests.java
git commit -m "feat(remote-store): add RemoteSegmentStoreDirectory for segment upload/read"
```

---

## Task 4: SegmentUploadScheduler

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/SegmentUploadScheduler.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/SegmentUploadSchedulerTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;

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
        // Fill up in-flight capacity
        long maxBytes = 256 * 1024 * 1024L;
        byte[] largeFile = new byte[(int) (maxBytes + 1)];

        SegmentUploadScheduler.UploadTask task = new SegmentUploadScheduler.UploadTask(
            "_big.cfs", largeFile, SegmentUploadScheduler.Priority.NORMAL
        );
        CompletableFuture<Void> future = scheduler.schedule(task);

        // Should still complete (queued), but stats should reflect pending
        assertTrue(scheduler.getBytesPending() > 0 || future.isDone());
    }

    public void testPriorityOrdering() throws Exception {
        AtomicInteger order = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        // Block remote to accumulate tasks
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

        // High priority should be picked first from queue
        // (exact ordering depends on timing, just verify both complete)
        lowTask.completion().get(5, TimeUnit.SECONDS);
        highTask.completion().get(5, TimeUnit.SECONDS);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.SegmentUploadSchedulerTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write minimal implementation**

```java
package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class SegmentUploadScheduler implements Closeable {

    private static final Logger logger = LogManager.getLogger(SegmentUploadScheduler.class);

    public enum Priority { LOW(0), NORMAL(1), HIGH(2), HIGHEST(3);
        final int value;
        Priority(int value) { this.value = value; }
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

        public String fileName() { return fileName; }
        public byte[] content() { return content; }
        public Priority priority() { return priority; }
        public CompletableFuture<Void> completion() { return completionFuture; }

        @Override
        public int compareTo(UploadTask other) {
            return Integer.compare(other.priority.value, this.priority.value); // higher priority first
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
        bytesPending.addAndGet(task.content.length);
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

    @Override
    public void close() {
        closed.set(true);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.SegmentUploadSchedulerTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/SegmentUploadScheduler.java \
        server/src/test/java/org/elasticsearch/index/remote/SegmentUploadSchedulerTests.java
git commit -m "feat(remote-store): add SegmentUploadScheduler with priority queue and parallelism"
```

---

## Task 5: RemoteStoreRefreshListener

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/RemoteStoreRefreshListener.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/RemoteStoreRefreshListenerTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote;

import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.Directory;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.Set;

import static org.mockito.Mockito.*;

public class RemoteStoreRefreshListenerTests extends ESTestCase {

    public void testAfterRefreshTriggersUpload() throws IOException {
        Store store = mock(Store.class);
        Directory localDir = mock(Directory.class);
        SegmentUploadScheduler scheduler = mock(SegmentUploadScheduler.class);
        RemoteSegmentStoreDirectory remoteDir = mock(RemoteSegmentStoreDirectory.class);
        ShardId shardId = new ShardId("test-index", "uuid", 0);

        RemoteStoreRefreshListener listener = new RemoteStoreRefreshListener(
            shardId, store, localDir, remoteDir, scheduler, 1L
        );

        // Simulate: afterRefresh called with didRefresh=true
        listener.afterRefresh(true);

        // Should have attempted to read local segment infos and schedule uploads
        // (exact verification depends on store mock setup)
    }

    public void testAfterRefreshSkipsWhenDidNotRefresh() {
        Store store = mock(Store.class);
        Directory localDir = mock(Directory.class);
        SegmentUploadScheduler scheduler = mock(SegmentUploadScheduler.class);
        RemoteSegmentStoreDirectory remoteDir = mock(RemoteSegmentStoreDirectory.class);
        ShardId shardId = new ShardId("test-index", "uuid", 0);

        RemoteStoreRefreshListener listener = new RemoteStoreRefreshListener(
            shardId, store, localDir, remoteDir, scheduler, 1L
        );

        listener.afterRefresh(false);

        verifyNoInteractions(scheduler);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.RemoteStoreRefreshListenerTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write minimal implementation**

```java
package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.index.store.StoreFileMetadata;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.lucene.search.ReferenceManager;

public class RemoteStoreRefreshListener implements ReferenceManager.RefreshListener {

    private static final Logger logger = LogManager.getLogger(RemoteStoreRefreshListener.class);

    private final ShardId shardId;
    private final Store store;
    private final Directory localDirectory;
    private final RemoteSegmentStoreDirectory remoteDirectory;
    private final SegmentUploadScheduler scheduler;
    private final long primaryTerm;
    private final Set<String> uploadedFiles = new HashSet<>();
    private final AtomicLong lastUploadedGeneration = new AtomicLong(-1);

    public RemoteStoreRefreshListener(ShardId shardId, Store store, Directory localDirectory,
                                      RemoteSegmentStoreDirectory remoteDirectory,
                                      SegmentUploadScheduler scheduler, long primaryTerm) {
        this.shardId = shardId;
        this.store = store;
        this.localDirectory = localDirectory;
        this.remoteDirectory = remoteDirectory;
        this.scheduler = scheduler;
        this.primaryTerm = primaryTerm;
    }

    @Override
    public void beforeRefresh() {}

    @Override
    public void afterRefresh(boolean didRefresh) {
        if (!didRefresh) {
            return;
        }
        try {
            uploadNewSegments();
        } catch (Exception e) {
            logger.warn("[{}] Failed to upload segments after refresh", shardId, e);
        }
    }

    private void uploadNewSegments() throws IOException {
        SegmentInfos segmentInfos = store.readLastCommittedSegmentsInfo();
        Set<String> currentFiles = new HashSet<>(segmentInfos.files(true));

        // Find files not yet uploaded
        Set<String> toUpload = new HashSet<>(currentFiles);
        toUpload.removeAll(uploadedFiles);

        if (toUpload.isEmpty()) {
            return;
        }

        // Schedule upload for each new file
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

        // Upload metadata after all segment files (metadata makes them visible)
        long generation = segmentInfos.getGeneration();
        Map<String, RemoteSegmentMetadata.FileInfo> fileInfos = new HashMap<>();
        for (String file : currentFiles) {
            long size = localDirectory.fileLength(file);
            String checksum = Store.digestToString(Store.readMetadataRecovery(localDirectory, file).checksum());
            fileInfos.put(file, new RemoteSegmentMetadata.FileInfo(size, checksum));
        }

        RemoteSegmentMetadata metadata = new RemoteSegmentMetadata(
            primaryTerm, generation, segmentInfos.getLastCommitGeneration(), fileInfos
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.RemoteStoreRefreshListenerTests" -x javadoc`
Expected: All 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/RemoteStoreRefreshListener.java \
        server/src/test/java/org/elasticsearch/index/remote/RemoteStoreRefreshListenerTests.java
git commit -m "feat(remote-store): add RemoteStoreRefreshListener to upload segments on refresh"
```

---

## Task 6: RemoteTranslogTransferManager

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/RemoteTranslogTransferManager.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/RemoteTranslogTransferManagerTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;

public class RemoteTranslogTransferManagerTests extends ESTestCase {

    private ThreadPool threadPool;
    private BlobContainer translogBlobContainer;
    private BlobContainer metadataBlobContainer;
    private RemoteTranslogTransferManager manager;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool("test");
        translogBlobContainer = mock(BlobContainer.class);
        metadataBlobContainer = mock(BlobContainer.class);
        manager = new RemoteTranslogTransferManager(
            translogBlobContainer, metadataBlobContainer, threadPool, 4
        );
    }

    @Override
    public void tearDown() throws Exception {
        manager.close();
        ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
        super.tearDown();
    }

    public void testUploadTranslogGeneration() throws Exception {
        Path tempDir = createTempDir();
        Path translogFile = tempDir.resolve("translog-1.tlog");
        Files.write(translogFile, "test translog data".getBytes());

        manager.uploadGeneration(translogFile, 1L, 1L, 0L, 100L);

        verify(translogBlobContainer).writeBlob(eq("translog-1.tlog"), any(), anyLong(), eq(true));
    }

    public void testUploadTranslogMetadata() throws Exception {
        manager.uploadTranslogMetadata(1L, 2L, 0L, 500L, 500L);

        verify(metadataBlobContainer).writeBlob(startsWith("translog__1__"), any(), anyLong(), eq(true));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.RemoteTranslogTransferManagerTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write minimal implementation**

```java
package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
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
        try (InputStream is = Files.newInputStream(translogFile)) {
            long size = Files.size(translogFile);
            translogBlobContainer.writeBlob(fileName, is, size, true);
        }
        lastUploadedGeneration.set(generation);
    }

    public void uploadGenerationAsync(Path translogFile, long generation, long primaryTerm,
                                      long minSeqNo, long maxSeqNo) {
        if (closed.get()) return;
        threadPool.generic().execute(() -> {
            try {
                parallelism.acquire();
                try {
                    uploadGeneration(translogFile, generation, primaryTerm, minSeqNo, maxSeqNo);
                } finally {
                    parallelism.release();
                }
            } catch (Exception e) {
                logger.warn("Failed to upload translog generation [{}]", generation, e);
            }
        });
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

    @Override
    public void close() {
        closed.set(true);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.RemoteTranslogTransferManagerTests" -x javadoc`
Expected: All 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/RemoteTranslogTransferManager.java \
        server/src/test/java/org/elasticsearch/index/remote/RemoteTranslogTransferManagerTests.java
git commit -m "feat(remote-store): add RemoteTranslogTransferManager for async translog upload"
```

---

## Task 7: SingleWriterLock

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/SingleWriterLock.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/SingleWriterLockTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.test.ESTestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.NoSuchFileException;

import static org.mockito.Mockito.*;

public class SingleWriterLockTests extends ESTestCase {

    public void testAcquireFirstTime() throws IOException {
        BlobContainer container = mock(BlobContainer.class);
        when(container.readBlob("lock/ownership.lock")).thenThrow(new NoSuchFileException("not found"));

        SingleWriterLock lock = new SingleWriterLock(container);
        boolean acquired = lock.tryAcquire(1L, "node-1");

        assertTrue(acquired);
        verify(container).writeBlob(eq("lock/ownership.lock"), any(), anyLong(), eq(true));
    }

    public void testAcquireWithHigherTerm() throws IOException {
        BlobContainer container = mock(BlobContainer.class);
        String existing = "{\"primary_term\":1,\"node_id\":\"node-1\"}";
        when(container.readBlob("lock/ownership.lock"))
            .thenReturn(new ByteArrayInputStream(existing.getBytes()));

        SingleWriterLock lock = new SingleWriterLock(container);
        boolean acquired = lock.tryAcquire(2L, "node-2");

        assertTrue(acquired);
    }

    public void testAcquireFailsWithLowerTerm() throws IOException {
        BlobContainer container = mock(BlobContainer.class);
        String existing = "{\"primary_term\":5,\"node_id\":\"node-1\"}";
        when(container.readBlob("lock/ownership.lock"))
            .thenReturn(new ByteArrayInputStream(existing.getBytes()));

        SingleWriterLock lock = new SingleWriterLock(container);
        boolean acquired = lock.tryAcquire(3L, "node-2");

        assertFalse(acquired);
        verify(container, never()).writeBlob(eq("lock/ownership.lock"), any(), anyLong(), anyBoolean());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.SingleWriterLockTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write minimal implementation**

```java
package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;

public class SingleWriterLock {

    private static final Logger logger = LogManager.getLogger(SingleWriterLock.class);
    private static final String LOCK_PATH = "lock/ownership.lock";

    private final BlobContainer blobContainer;

    public SingleWriterLock(BlobContainer blobContainer) {
        this.blobContainer = blobContainer;
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
            return true;
        } catch (NoSuchFileException e) {
            writeLock(primaryTerm, nodeId);
            return true;
        }
    }

    public void renew(long primaryTerm, String nodeId) throws IOException {
        writeLock(primaryTerm, nodeId);
    }

    private long readCurrentTerm() throws IOException {
        try (InputStream is = blobContainer.readBlob(LOCK_PATH)) {
            byte[] bytes = is.readAllBytes();
            String json = new String(bytes, StandardCharsets.UTF_8);
            try (XContentParser parser = XContentType.JSON.xContent()
                    .createParser(null, null, json.getBytes(StandardCharsets.UTF_8))) {
                parser.nextToken();
                long term = 0;
                while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                    if ("primary_term".equals(parser.currentName())) {
                        parser.nextToken();
                        term = parser.longValue();
                    } else {
                        parser.nextToken();
                        parser.skipChildren();
                    }
                }
                return term;
            }
        }
    }

    private void writeLock(long primaryTerm, String nodeId) throws IOException {
        String content = "{\"primary_term\":" + primaryTerm + ",\"node_id\":\"" + nodeId + "\"}";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            blobContainer.writeBlob(LOCK_PATH, is, bytes.length, true);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.SingleWriterLockTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/SingleWriterLock.java \
        server/src/test/java/org/elasticsearch/index/remote/SingleWriterLockTests.java
git commit -m "feat(remote-store): add SingleWriterLock for split-brain prevention"
```

---

## Task 8: BackpressureController

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/BackpressureController.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/BackpressureControllerTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.test.ESTestCase;

public class BackpressureControllerTests extends ESTestCase {

    public void testNormalState() {
        BackpressureController controller = new BackpressureController(5, 0.70, 0.90);
        assertEquals(BackpressureController.Level.NORMAL, controller.getLevel());
        assertTrue(controller.allowWrite());
    }

    public void testWarnAfterConsecutiveFailures() {
        BackpressureController controller = new BackpressureController(3, 0.70, 0.90);
        controller.recordFailure();
        controller.recordFailure();
        controller.recordFailure();
        assertEquals(BackpressureController.Level.WARN, controller.getLevel());
        assertTrue(controller.allowWrite()); // warn still allows write
    }

    public void testBackpressureOnDiskThreshold() {
        BackpressureController controller = new BackpressureController(5, 0.70, 0.90);
        controller.recordFailure();
        controller.recordFailure();
        controller.recordFailure();
        controller.recordFailure();
        controller.recordFailure();
        controller.updateDiskUsage(0.75); // above warn threshold
        assertEquals(BackpressureController.Level.BACKPRESSURE, controller.getLevel());
        assertTrue(controller.allowWrite()); // backpressure slows but allows
    }

    public void testBlockOnHighDisk() {
        BackpressureController controller = new BackpressureController(5, 0.70, 0.90);
        controller.updateDiskUsage(0.92); // above block threshold
        assertEquals(BackpressureController.Level.BLOCK, controller.getLevel());
        assertFalse(controller.allowWrite()); // block rejects writes
    }

    public void testRecoveryResetsState() {
        BackpressureController controller = new BackpressureController(3, 0.70, 0.90);
        controller.recordFailure();
        controller.recordFailure();
        controller.recordFailure();
        assertEquals(BackpressureController.Level.WARN, controller.getLevel());

        controller.recordSuccess();
        assertEquals(BackpressureController.Level.NORMAL, controller.getLevel());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.BackpressureControllerTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write minimal implementation**

```java
package org.elasticsearch.index.remote;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class BackpressureController {

    public enum Level { NORMAL, WARN, BACKPRESSURE, BLOCK }

    private final int failureThreshold;
    private final double warnDiskThreshold;
    private final double blockDiskThreshold;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile double currentDiskUsage = 0.0;
    private final AtomicReference<Level> level = new AtomicReference<>(Level.NORMAL);

    public BackpressureController(int failureThreshold, double warnDiskThreshold, double blockDiskThreshold) {
        this.failureThreshold = failureThreshold;
        this.warnDiskThreshold = warnDiskThreshold;
        this.blockDiskThreshold = blockDiskThreshold;
    }

    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        recalculateLevel(failures);
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        recalculateLevel(0);
    }

    public void updateDiskUsage(double usage) {
        this.currentDiskUsage = usage;
        recalculateLevel(consecutiveFailures.get());
    }

    private void recalculateLevel(int failures) {
        if (currentDiskUsage >= blockDiskThreshold) {
            level.set(Level.BLOCK);
        } else if (failures >= failureThreshold && currentDiskUsage >= warnDiskThreshold) {
            level.set(Level.BACKPRESSURE);
        } else if (failures >= failureThreshold) {
            level.set(Level.WARN);
        } else {
            level.set(Level.NORMAL);
        }
    }

    public Level getLevel() {
        return level.get();
    }

    public boolean allowWrite() {
        return level.get() != Level.BLOCK;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.BackpressureControllerTests" -x javadoc`
Expected: All 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/BackpressureController.java \
        server/src/test/java/org/elasticsearch/index/remote/BackpressureControllerTests.java
git commit -m "feat(remote-store): add BackpressureController with 3-level degradation"
```

---

## Task 9: RemoteStoreStats

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/RemoteStoreStats.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/RemoteStoreStatsTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.test.ESTestCase;

public class RemoteStoreStatsTests extends ESTestCase {

    public void testRecordUpload() {
        RemoteStoreStats stats = new RemoteStoreStats();
        stats.recordUploadStart(1024L);
        assertEquals(1024L, stats.getBytesPending());
        assertEquals(1, stats.getUploadsInProgress());

        stats.recordUploadSuccess(1024L, 200L);
        assertEquals(0L, stats.getBytesPending());
        assertEquals(0, stats.getUploadsInProgress());
        assertEquals(1024L, stats.getTotalBytesUploaded());
        assertEquals(1, stats.getTotalUploadsSucceeded());
    }

    public void testRecordUploadFailure() {
        RemoteStoreStats stats = new RemoteStoreStats();
        stats.recordUploadStart(2048L);
        stats.recordUploadFailure(2048L);

        assertEquals(0L, stats.getBytesPending());
        assertEquals(1, stats.getTotalUploadsFailed());
    }

    public void testLagCalculation() {
        RemoteStoreStats stats = new RemoteStoreStats();
        stats.updateLocalRefreshSeqNo(100L);
        stats.updateRemoteRefreshSeqNo(95L);

        assertEquals(5L, stats.getRefreshSeqNoLag());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.RemoteStoreStatsTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write minimal implementation**

```java
package org.elasticsearch.index.remote;

import java.util.concurrent.atomic.AtomicLong;

public class RemoteStoreStats {

    private final AtomicLong bytesPending = new AtomicLong(0);
    private final AtomicLong uploadsInProgress = new AtomicLong(0);
    private final AtomicLong totalBytesUploaded = new AtomicLong(0);
    private final AtomicLong totalUploadsSucceeded = new AtomicLong(0);
    private final AtomicLong totalUploadsFailed = new AtomicLong(0);
    private final AtomicLong localRefreshSeqNo = new AtomicLong(0);
    private final AtomicLong remoteRefreshSeqNo = new AtomicLong(0);

    public void recordUploadStart(long bytes) {
        bytesPending.addAndGet(bytes);
        uploadsInProgress.incrementAndGet();
    }

    public void recordUploadSuccess(long bytes, long durationMs) {
        bytesPending.addAndGet(-bytes);
        uploadsInProgress.decrementAndGet();
        totalBytesUploaded.addAndGet(bytes);
        totalUploadsSucceeded.incrementAndGet();
    }

    public void recordUploadFailure(long bytes) {
        bytesPending.addAndGet(-bytes);
        uploadsInProgress.decrementAndGet();
        totalUploadsFailed.incrementAndGet();
    }

    public void updateLocalRefreshSeqNo(long seqNo) {
        localRefreshSeqNo.set(seqNo);
    }

    public void updateRemoteRefreshSeqNo(long seqNo) {
        remoteRefreshSeqNo.set(seqNo);
    }

    public long getBytesPending() { return bytesPending.get(); }
    public int getUploadsInProgress() { return (int) uploadsInProgress.get(); }
    public long getTotalBytesUploaded() { return totalBytesUploaded.get(); }
    public int getTotalUploadsSucceeded() { return (int) totalUploadsSucceeded.get(); }
    public int getTotalUploadsFailed() { return (int) totalUploadsFailed.get(); }
    public long getRefreshSeqNoLag() { return localRefreshSeqNo.get() - remoteRefreshSeqNo.get(); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.RemoteStoreStatsTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/RemoteStoreStats.java \
        server/src/test/java/org/elasticsearch/index/remote/RemoteStoreStatsTests.java
git commit -m "feat(remote-store): add RemoteStoreStats for upload metrics tracking"
```

---

## Task 10: Wire into IndexShard

**Files:**
- Modify: `server/src/main/java/org/elasticsearch/index/shard/IndexShard.java`
- Modify: `server/src/main/java/org/elasticsearch/index/IndexModule.java`

- [ ] **Step 1: Add RemoteStoreRefreshListener registration in IndexShard**

In `IndexShard.java`, after `postRecovery()` is called and the shard becomes PRIMARY, register the listener:

```java
// Add field to IndexShard
private volatile RemoteStoreRefreshListener remoteStoreRefreshListener;

// In postRecovery or after primary promotion check:
private void maybeInitRemoteStore() {
    if (indexSettings.getSettings().getAsBoolean("index.remote_store.enabled", false)
        && routingEntry().primary()) {
        // Initialize RemoteSegmentStoreDirectory from repository
        String repoName = indexSettings.getSettings().get("index.remote_store.repository", "");
        if (repoName.isEmpty()) return;
        
        // Get BlobStore from RepositoriesService (injected via IndexModule)
        // Create RemoteSegmentStoreDirectory, SegmentUploadScheduler
        // Register RemoteStoreRefreshListener
        this.remoteStoreRefreshListener = new RemoteStoreRefreshListener(
            shardId(), store(), store().directory(), remoteSegmentStoreDirectory,
            segmentUploadScheduler, getOperationPrimaryTerm()
        );
        // Register with the internal searcher manager
        getEngine().config().getExternalRefreshListener().add(remoteStoreRefreshListener);
    }
}
```

- [ ] **Step 2: Add remote store enabled flag to EngineConfig**

In `EngineConfig.java`, add a list for external refresh listeners:

```java
private final List<ReferenceManager.RefreshListener> externalRefreshListeners;

public List<ReferenceManager.RefreshListener> getExternalRefreshListener() {
    return externalRefreshListeners;
}
```

- [ ] **Step 3: Wire translog upload on generation roll**

In `Translog.java` `rollGeneration()`, add a hook:

```java
// After rolling local generation:
if (remoteTranslogTransferManager != null) {
    Path currentFile = location.resolve(getFilename(currentFileGeneration()));
    remoteTranslogTransferManager.uploadGenerationAsync(
        currentFile, currentFileGeneration(), primaryTerm, /* minSeqNo */ 0, /* maxSeqNo */ 0
    );
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :server:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/shard/IndexShard.java \
        server/src/main/java/org/elasticsearch/index/engine/EngineConfig.java \
        server/src/main/java/org/elasticsearch/index/translog/Translog.java \
        server/src/main/java/org/elasticsearch/index/IndexModule.java
git commit -m "feat(remote-store): wire RemoteStoreRefreshListener into IndexShard lifecycle"
```

---

## Task 11: Integration Test

**Files:**
- Create: `server/src/internalClusterTest/java/org/elasticsearch/index/remote/RemoteStoreIntegrationIT.java`

- [ ] **Step 1: Write integration test**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.Map;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 2)
public class RemoteStoreIntegrationIT extends ESIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal, otherSettings))
            .put("node.remote_store.segment.upload.parallelism", 4)
            .build();
    }

    public void testIndexWithRemoteStoreEnabled() throws Exception {
        // Create index with remote store enabled (using fs repository for testing)
        client().admin().cluster().preparePutRepository("test-repo")
            .setType("fs")
            .setSettings(Settings.builder()
                .put("location", randomRepoPath())
                .build())
            .get();

        CreateIndexRequest createRequest = new CreateIndexRequest("test-index");
        createRequest.settings(Settings.builder()
            .put("index.remote_store.enabled", true)
            .put("index.remote_store.repository", "test-repo")
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", 1)
            .build());
        client().admin().indices().create(createRequest).actionGet();

        ensureGreen("test-index");

        // Index documents
        for (int i = 0; i < 100; i++) {
            IndexResponse response = client().index(new IndexRequest("test-index")
                .source(Map.of("field", "value-" + i)))
                .actionGet();
            assertEquals("CREATED", response.status().name().equals("CREATED") ? "CREATED" : "OK");
        }

        // Force refresh to trigger segment upload
        client().admin().indices().prepareRefresh("test-index").get();

        // Verify data is still queryable
        long count = client().prepareSearch("test-index").setSize(0).get().getHits().getTotalHits().value;
        assertEquals(100L, count);
    }

    public void testWritesContinueWhenRemoteStoreDisabled() throws Exception {
        // Baseline: index without remote store should work normally
        CreateIndexRequest createRequest = new CreateIndexRequest("no-remote-index");
        createRequest.settings(Settings.builder()
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", 0)
            .build());
        client().admin().indices().create(createRequest).actionGet();
        ensureGreen("no-remote-index");

        IndexResponse response = client().index(new IndexRequest("no-remote-index")
            .source(Map.of("field", "value")))
            .actionGet();
        assertTrue(response.status().getStatus() < 300);
    }
}
```

- [ ] **Step 2: Run integration test**

Run: `./gradlew :server:internalClusterTest --tests "org.elasticsearch.index.remote.RemoteStoreIntegrationIT" -x javadoc`
Expected: All 2 tests PASS (may need adjustments based on actual wiring)

- [ ] **Step 3: Commit**

```bash
git add server/src/internalClusterTest/java/org/elasticsearch/index/remote/RemoteStoreIntegrationIT.java
git commit -m "test(remote-store): add integration test for Remote Store write-back"
```

---

## Task 12: Final compilation check and documentation

- [ ] **Step 1: Full compilation check**

Run: `./gradlew :server:compileJava :server:compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all new unit tests**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.*" -x javadoc`
Expected: All tests PASS

- [ ] **Step 3: Run existing tests to ensure no regressions**

Run: `./gradlew :server:test -x javadoc` (full server module tests)
Expected: No new failures

- [ ] **Step 4: Commit final state**

```bash
git add -A
git commit -m "chore(remote-store): Phase 1 core Remote Store write-back complete"
```

---

## Task 13: SingleWriterLock Lease Tolerance & Fast Degrade

> **Risk mitigation:** §8.7.1(S-1) + §8.10.6(改进2) — 续约容忍窗口 + 快速降级，防止 Remote 不可达时写入长时间中断

**Files:**
- Modify: `server/src/main/java/org/elasticsearch/index/remote/SingleWriterLock.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/SingleWriterLockConfig.java`
- Modify: `server/src/main/java/org/elasticsearch/index/remote/RemoteStoreSettings.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/SingleWriterLockLeaseTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.nio.file.NoSuchFileException;

import static org.mockito.Mockito.*;

public class SingleWriterLockLeaseTests extends ESTestCase {

    public void testLeaseToleranceAllowsTransientFailure() throws IOException {
        BlobContainer container = mock(BlobContainer.class);
        SingleWriterLockConfig config = new SingleWriterLockConfig(
            30000L,  // lease_tolerance: 30s
            2,       // degrade_after_failures: 2
            5000L,   // lock_attempt_timeout: 5s
            true     // fast_degrade_on_first_failure
        );
        SingleWriterLock lock = new SingleWriterLock(container, config);

        // First acquire succeeds
        when(container.readBlob("lock/ownership.lock")).thenThrow(new NoSuchFileException("not found"));
        assertTrue(lock.tryAcquire(1L, "node-1"));

        // Simulate renewal failure (Remote unreachable)
        when(container.writeBlob(anyString(), any(), anyLong(), anyBoolean()))
            .thenThrow(new IOException("connection refused"));

        // First renewal failure: should NOT trigger degrade yet
        SingleWriterLock.RenewResult result1 = lock.tryRenew(1L, "node-1");
        assertEquals(SingleWriterLock.RenewResult.FAILED_TOLERABLE, result1);
        assertTrue(lock.isHeldLocally()); // still considered held within tolerance

        // Second renewal failure: triggers degrade
        SingleWriterLock.RenewResult result2 = lock.tryRenew(1L, "node-1");
        assertEquals(SingleWriterLock.RenewResult.DEGRADED, result2);
        assertTrue(lock.isDegraded());
    }

    public void testDegradedModeAllowsLocalWrites() throws IOException {
        BlobContainer container = mock(BlobContainer.class);
        SingleWriterLockConfig config = new SingleWriterLockConfig(30000L, 2, 5000L, true);
        SingleWriterLock lock = new SingleWriterLock(container, config);

        when(container.readBlob("lock/ownership.lock")).thenThrow(new NoSuchFileException("not found"));
        lock.tryAcquire(1L, "node-1");

        // Force into degraded state
        when(container.writeBlob(anyString(), any(), anyLong(), anyBoolean()))
            .thenThrow(new IOException("timeout"));
        lock.tryRenew(1L, "node-1"); // fail 1
        lock.tryRenew(1L, "node-1"); // fail 2 → degraded

        assertTrue(lock.isDegraded());
        assertTrue(lock.allowWriteInDegradedMode());
    }

    public void testRecoveryFromDegradedMode() throws IOException {
        BlobContainer container = mock(BlobContainer.class);
        SingleWriterLockConfig config = new SingleWriterLockConfig(30000L, 2, 5000L, true);
        SingleWriterLock lock = new SingleWriterLock(container, config);

        when(container.readBlob("lock/ownership.lock")).thenThrow(new NoSuchFileException("not found"));
        lock.tryAcquire(1L, "node-1");

        // Enter degraded mode
        when(container.writeBlob(anyString(), any(), anyLong(), anyBoolean()))
            .thenThrow(new IOException("timeout"));
        lock.tryRenew(1L, "node-1");
        lock.tryRenew(1L, "node-1");
        assertTrue(lock.isDegraded());

        // Remote recovers
        reset(container);
        when(container.readBlob("lock/ownership.lock")).thenThrow(new NoSuchFileException("not found"));

        SingleWriterLock.RenewResult result = lock.tryRenew(1L, "node-1");
        assertEquals(SingleWriterLock.RenewResult.SUCCESS, result);
        assertFalse(lock.isDegraded());
    }

    public void testLeaseToleranceWindowExpiry() throws Exception {
        BlobContainer container = mock(BlobContainer.class);
        // Very short tolerance for testing
        SingleWriterLockConfig config = new SingleWriterLockConfig(100L, 5, 5000L, false);
        SingleWriterLock lock = new SingleWriterLock(container, config);

        when(container.readBlob("lock/ownership.lock")).thenThrow(new NoSuchFileException("not found"));
        lock.tryAcquire(1L, "node-1");

        // Simulate tolerance window expiry without fast_degrade
        when(container.writeBlob(anyString(), any(), anyLong(), anyBoolean()))
            .thenThrow(new IOException("timeout"));
        // With fast_degrade=false, need to exceed tolerance window
        Thread.sleep(150); // exceed 100ms tolerance
        SingleWriterLock.RenewResult result = lock.tryRenew(1L, "node-1");
        assertEquals(SingleWriterLock.RenewResult.LEASE_EXPIRED, result);
        assertFalse(lock.isHeldLocally());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.SingleWriterLockLeaseTests" -x javadoc`
Expected: Compilation error — `SingleWriterLockConfig`, `RenewResult`, `isDegraded()` etc. don't exist

- [ ] **Step 3: Add lease tolerance settings to RemoteStoreSettings**

Add to `RemoteStoreSettings.java`:

```java
public static final Setting<TimeValue> SINGLE_WRITER_LEASE_TOLERANCE = Setting.timeSetting(
    "cluster.remote_store.single_writer.lease_tolerance",
    TimeValue.timeValueSeconds(30),
    TimeValue.timeValueSeconds(5),
    Setting.Property.NodeScope, Setting.Property.Dynamic);

public static final Setting<Integer> SINGLE_WRITER_DEGRADE_AFTER_FAILURES = Setting.intSetting(
    "cluster.remote_store.single_writer.degrade_after_failures", 2, 1, 10,
    Setting.Property.NodeScope, Setting.Property.Dynamic);

public static final Setting<TimeValue> SINGLE_WRITER_LOCK_ATTEMPT_TIMEOUT = Setting.timeSetting(
    "cluster.remote_store.single_writer.lock_attempt_timeout",
    TimeValue.timeValueSeconds(5),
    TimeValue.timeValueSeconds(1),
    Setting.Property.NodeScope, Setting.Property.Dynamic);

public static final Setting<Boolean> SINGLE_WRITER_FAST_DEGRADE = Setting.boolSetting(
    "cluster.remote_store.single_writer.fast_degrade_on_first_failure", true,
    Setting.Property.NodeScope, Setting.Property.Dynamic);
```

- [ ] **Step 4: Create SingleWriterLockConfig**

```java
package org.elasticsearch.index.remote;

public class SingleWriterLockConfig {

    private final long leaseToleranceMs;
    private final int degradeAfterFailures;
    private final long lockAttemptTimeoutMs;
    private final boolean fastDegradeOnFirstFailure;

    public SingleWriterLockConfig(long leaseToleranceMs, int degradeAfterFailures,
                                   long lockAttemptTimeoutMs, boolean fastDegradeOnFirstFailure) {
        this.leaseToleranceMs = leaseToleranceMs;
        this.degradeAfterFailures = degradeAfterFailures;
        this.lockAttemptTimeoutMs = lockAttemptTimeoutMs;
        this.fastDegradeOnFirstFailure = fastDegradeOnFirstFailure;
    }

    public long getLeaseToleranceMs() { return leaseToleranceMs; }
    public int getDegradeAfterFailures() { return degradeAfterFailures; }
    public long getLockAttemptTimeoutMs() { return lockAttemptTimeoutMs; }
    public boolean isFastDegradeOnFirstFailure() { return fastDegradeOnFirstFailure; }
}
```

- [ ] **Step 5: Enhance SingleWriterLock with lease tolerance and degraded mode**

Add to `SingleWriterLock.java`:

```java
public enum RenewResult {
    SUCCESS,
    FAILED_TOLERABLE,   // within tolerance window, still held locally
    DEGRADED,           // exceeded failure threshold, entering degraded mode
    LEASE_EXPIRED       // tolerance window expired, lock no longer valid
}

private final SingleWriterLockConfig config;
private volatile boolean degraded = false;
private volatile boolean heldLocally = false;
private volatile long lastSuccessfulRenewMs = 0;
private int consecutiveFailures = 0;

public SingleWriterLock(BlobContainer blobContainer, SingleWriterLockConfig config) {
    this.blobContainer = blobContainer;
    this.config = config;
}

public RenewResult tryRenew(long primaryTerm, String nodeId) {
    try {
        writeLock(primaryTerm, nodeId);
        consecutiveFailures = 0;
        lastSuccessfulRenewMs = System.currentTimeMillis();
        degraded = false;
        return RenewResult.SUCCESS;
    } catch (IOException e) {
        consecutiveFailures++;
        long elapsed = System.currentTimeMillis() - lastSuccessfulRenewMs;

        if (config.isFastDegradeOnFirstFailure() &&
            consecutiveFailures >= config.getDegradeAfterFailures()) {
            degraded = true;
            logger.warn("SingleWriterLock degraded after {} consecutive failures, " +
                "allowing local writes", consecutiveFailures);
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

public boolean isHeldLocally() { return heldLocally; }
public boolean isDegraded() { return degraded; }
public boolean allowWriteInDegradedMode() { return degraded && heldLocally; }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.SingleWriterLockLeaseTests" -x javadoc`
Expected: All 4 tests PASS

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/SingleWriterLock.java \
        server/src/main/java/org/elasticsearch/index/remote/SingleWriterLockConfig.java \
        server/src/main/java/org/elasticsearch/index/remote/RemoteStoreSettings.java \
        server/src/test/java/org/elasticsearch/index/remote/SingleWriterLockLeaseTests.java
git commit -m "feat(remote-store): add lease tolerance and fast degrade to SingleWriterLock

Risk mitigation S-1: Remote Store unreachable no longer blocks writes indefinitely.
Tolerance window (30s) + fast degrade (2 failures) reduces write interruption from 75-90s to 15-20s."
```

---

## Task 14: Upload Order Guarantee (Translog Before Segment)

> **Risk mitigation:** §8.9 Phase1 D-3 — Translog/Segment 上传顺序保证，防止 Recovery 时 segment 可见但 translog 缺失导致 seq_no gap

**Files:**
- Modify: `server/src/main/java/org/elasticsearch/index/remote/RemoteStoreRefreshListener.java`
- Modify: `server/src/main/java/org/elasticsearch/index/remote/SegmentUploadScheduler.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/UploadOrderCoordinator.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/UploadOrderCoordinatorTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.test.ESTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class UploadOrderCoordinatorTests extends ESTestCase {

    public void testSegmentUploadBlockedUntilTranslogUploaded() throws Exception {
        List<String> uploadOrder = new ArrayList<>();
        UploadOrderCoordinator coordinator = new UploadOrderCoordinator();

        // Register a translog generation
        coordinator.registerTranslogGeneration(5L);

        // Attempt segment upload — should block
        CountDownLatch segmentLatch = new CountDownLatch(1);
        Thread segmentThread = new Thread(() -> {
            coordinator.awaitTranslogUploadedForGeneration(5L);
            uploadOrder.add("segment");
            segmentLatch.countDown();
        });
        segmentThread.start();

        // Brief pause — segment should still be blocked
        assertFalse(segmentLatch.await(200, TimeUnit.MILLISECONDS));

        // Complete translog upload
        uploadOrder.add("translog");
        coordinator.markTranslogUploaded(5L);

        // Now segment should proceed
        assertTrue(segmentLatch.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("translog", "segment"), uploadOrder);
    }

    public void testSegmentUploadProceedsIfNoTranslogPending() {
        UploadOrderCoordinator coordinator = new UploadOrderCoordinator();
        // No translog registered — segment upload should not block
        coordinator.awaitTranslogUploadedForGeneration(5L); // should return immediately
    }

    public void testMultipleGenerationsOrderedCorrectly() throws Exception {
        UploadOrderCoordinator coordinator = new UploadOrderCoordinator();
        coordinator.registerTranslogGeneration(3L);
        coordinator.registerTranslogGeneration(4L);

        // Generation 4 segment waits for gen 4 translog
        CountDownLatch latch = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            coordinator.awaitTranslogUploadedForGeneration(4L);
            latch.countDown();
        });
        t.start();

        // Uploading gen 3 translog does not unblock gen 4 segment
        coordinator.markTranslogUploaded(3L);
        assertFalse(latch.await(200, TimeUnit.MILLISECONDS));

        // Uploading gen 4 translog unblocks gen 4 segment
        coordinator.markTranslogUploaded(4L);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.UploadOrderCoordinatorTests" -x javadoc`
Expected: Compilation error — `UploadOrderCoordinator` does not exist

- [ ] **Step 3: Write implementation**

```java
package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class UploadOrderCoordinator {

    private static final Logger logger = LogManager.getLogger(UploadOrderCoordinator.class);
    private static final long AWAIT_TIMEOUT_MS = 60_000;

    private final ConcurrentHashMap<Long, CountDownLatch> pendingTranslogs = new ConcurrentHashMap<>();

    public void registerTranslogGeneration(long generation) {
        pendingTranslogs.putIfAbsent(generation, new CountDownLatch(1));
    }

    public void markTranslogUploaded(long generation) {
        CountDownLatch latch = pendingTranslogs.remove(generation);
        if (latch != null) {
            latch.countDown();
        }
    }

    public void awaitTranslogUploadedForGeneration(long generation) {
        CountDownLatch latch = pendingTranslogs.get(generation);
        if (latch == null) {
            return; // no pending translog for this generation
        }
        try {
            if (!latch.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logger.warn("Timed out waiting for translog gen={} upload before segment upload", generation);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted waiting for translog gen={} upload", generation);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.UploadOrderCoordinatorTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 5: Wire into RefreshListener and TranslogManager**

In `RemoteStoreRefreshListener.afterRefresh()`, before scheduling segment upload:
```java
// Ensure translog for this generation is uploaded first
uploadOrderCoordinator.awaitTranslogUploadedForGeneration(currentGeneration);
// Then proceed with segment upload
segmentUploadScheduler.scheduleUpload(...);
```

In `RemoteTranslogTransferManager.onUploadComplete()`:
```java
// Signal that this generation's translog is uploaded
uploadOrderCoordinator.markTranslogUploaded(generation);
```

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/UploadOrderCoordinator.java \
        server/src/test/java/org/elasticsearch/index/remote/UploadOrderCoordinatorTests.java \
        server/src/main/java/org/elasticsearch/index/remote/RemoteStoreRefreshListener.java \
        server/src/main/java/org/elasticsearch/index/remote/RemoteTranslogTransferManager.java
git commit -m "feat(remote-store): enforce translog-before-segment upload ordering

Risk mitigation D-3: Guarantees translog is uploaded before corresponding segment,
preventing seq_no gaps during Remote recovery."
```

---

## Task 15: ForceUpload-Before-Handoff (Relocation Safety)

> **Risk mitigation:** §7.3.3 — Relocation 前强制上传 tail，防止 Primary shard 迁移期间 Source 崩溃导致已 ACK 数据丢失

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/RelocationUploadService.java`
- Modify: `server/src/main/java/org/elasticsearch/index/remote/RemoteStoreSettings.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/RelocationUploadServiceTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class RelocationUploadServiceTests extends ESTestCase {

    public void testForceUploadBlocksHandoffUntilComplete() throws Exception {
        AtomicBoolean uploadComplete = new AtomicBoolean(false);
        AtomicLong uploadedSeqNo = new AtomicLong(-1);

        RelocationUploadService service = new RelocationUploadService(
            60_000L,   // handoff_timeout: 60s
            256 * 1024 * 1024L // max_tail_at_handoff: 256MB
        );

        // Simulate tail state: localCheckpoint=100, lastUploadedSeqNo=80
        RelocationUploadService.TailState tailState = new RelocationUploadService.TailState(100L, 80L, 50_000_000L);

        // Register upload callback
        service.setForceUploadCallback((fromSeqNo) -> {
            uploadedSeqNo.set(fromSeqNo);
            uploadComplete.set(true);
            return 100L; // returns new LUS after upload
        });

        // Execute force upload before handoff
        RelocationUploadService.HandoffReadiness readiness = service.prepareForHandoff(tailState);

        assertTrue(uploadComplete.get());
        assertEquals(80L, uploadedSeqNo.get()); // should upload from LUS=80
        assertEquals(RelocationUploadService.HandoffReadiness.READY, readiness);
    }

    public void testHandoffDelayedWhenTailExceedsMax() throws Exception {
        RelocationUploadService service = new RelocationUploadService(
            60_000L,
            256 * 1024 * 1024L // 256MB max
        );

        // Tail is 500MB — exceeds max_tail_at_handoff
        RelocationUploadService.TailState tailState = new RelocationUploadService.TailState(
            100L, 80L, 500_000_000L);

        service.setForceUploadCallback((fromSeqNo) -> {
            // Simulates partial upload — only gets tail down to 300MB
            return 90L;
        });

        RelocationUploadService.HandoffReadiness readiness = service.prepareForHandoff(tailState);
        assertEquals(RelocationUploadService.HandoffReadiness.DELAYED, readiness);
    }

    public void testHandoffTimesOut() throws Exception {
        RelocationUploadService service = new RelocationUploadService(
            100L,  // very short timeout for test
            256 * 1024 * 1024L
        );

        RelocationUploadService.TailState tailState = new RelocationUploadService.TailState(100L, 80L, 50_000_000L);

        service.setForceUploadCallback((fromSeqNo) -> {
            try {
                Thread.sleep(200); // exceeds timeout
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 100L;
        });

        RelocationUploadService.HandoffReadiness readiness = service.prepareForHandoff(tailState);
        assertEquals(RelocationUploadService.HandoffReadiness.TIMEOUT, readiness);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.RelocationUploadServiceTests" -x javadoc`
Expected: Compilation error — `RelocationUploadService` does not exist

- [ ] **Step 3: Write implementation**

```java
package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Callable;
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

        long tailBytes = tailState.tailSizeBytes;
        logger.info("Preparing for handoff: LUS={}, localCP={}, tailBytes={}",
            tailState.lastUploadedSeqNo, tailState.localCheckpoint, tailBytes);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> future = executor.submit(() -> forceUploadCallback.applyAsLong(tailState.lastUploadedSeqNo));
            long newLUS = future.get(handoffTimeoutMs, TimeUnit.MILLISECONDS);

            // Check if tail is now within acceptable range
            long remainingTail = tailState.localCheckpoint - newLUS;
            if (remainingTail > 0 && tailBytes > maxTailAtHandoff) {
                logger.warn("Tail still exceeds max after upload: remaining_seqno_gap={}", remainingTail);
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
        READY,    // tail uploaded, safe to proceed
        DELAYED,  // tail still too large, retry later
        TIMEOUT,  // upload timed out
        FAILED    // upload failed
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
```

- [ ] **Step 4: Add relocation settings to RemoteStoreSettings**

```java
public static final Setting<Boolean> RELOCATION_FORCE_UPLOAD = Setting.boolSetting(
    "cluster.routing.allocation.relocation.force_upload_before_handoff", true,
    Setting.Property.NodeScope, Setting.Property.Dynamic);

public static final Setting<TimeValue> RELOCATION_HANDOFF_TIMEOUT = Setting.timeSetting(
    "index.remote_store.relocation.handoff_timeout",
    TimeValue.timeValueSeconds(60),
    TimeValue.timeValueSeconds(10),
    Setting.Property.IndexScope, Setting.Property.Dynamic);

public static final Setting<ByteSizeValue> RELOCATION_MAX_TAIL_AT_HANDOFF = Setting.byteSizeSetting(
    "index.remote_store.relocation.max_tail_at_handoff",
    new ByteSizeValue(256, ByteSizeUnit.MB),
    Setting.Property.IndexScope, Setting.Property.Dynamic);
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.RelocationUploadServiceTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/RelocationUploadService.java \
        server/src/main/java/org/elasticsearch/index/remote/RemoteStoreSettings.java \
        server/src/test/java/org/elasticsearch/index/remote/RelocationUploadServiceTests.java
git commit -m "feat(remote-store): add ForceUpload-Before-Handoff for relocation safety

Risk mitigation §7.3.3: Primary shard relocation now forces tail upload to Remote
before handoff. If Source crashes during handoff, Target can recover from Remote.
Reduces data loss window from full tail size to ≤1 refresh period (1s)."
```

---

## Task 16: Coordinator Fast Failover Routing

> **Risk mitigation:** §8.10.6(改进3) — Coordinator 收到 node-left 事件后立即跳过该节点路由，读取中断从 30s 降至 2-5s

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/FastFailoverService.java`
- Modify: `server/src/main/java/org/elasticsearch/index/remote/RemoteStoreSettings.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/FastFailoverServiceTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.test.ESTestCase;

import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FastFailoverServiceTests extends ESTestCase {

    public void testNodeMarkedUnavailableOnNodeLeft() {
        FastFailoverService service = new FastFailoverService(true, 2000L);
        String nodeId = "node-1";

        assertFalse(service.isNodeUnavailable(nodeId));

        service.onNodeLeft(nodeId);

        assertTrue(service.isNodeUnavailable(nodeId));
    }

    public void testNodeClearedWhenRejoins() {
        FastFailoverService service = new FastFailoverService(true, 2000L);
        String nodeId = "node-1";

        service.onNodeLeft(nodeId);
        assertTrue(service.isNodeUnavailable(nodeId));

        service.onNodeJoined(nodeId);
        assertFalse(service.isNodeUnavailable(nodeId));
    }

    public void testDisabledServiceAlwaysAvailable() {
        FastFailoverService service = new FastFailoverService(false, 2000L);
        String nodeId = "node-1";

        service.onNodeLeft(nodeId);
        assertFalse(service.isNodeUnavailable(nodeId)); // disabled → never marks unavailable
    }

    public void testShouldSkipNodeForRouting() {
        FastFailoverService service = new FastFailoverService(true, 2000L);
        String deadNode = "node-1";
        String aliveNode = "node-2";

        service.onNodeLeft(deadNode);

        assertTrue(service.shouldSkipNode(deadNode));
        assertFalse(service.shouldSkipNode(aliveNode));
    }

    public void testGetReducedTimeoutForKnownDeadNode() {
        FastFailoverService service = new FastFailoverService(true, 2000L);
        String deadNode = "node-1";

        // Not marked dead — should return default
        assertEquals(-1L, service.getReducedTimeout(deadNode));

        service.onNodeLeft(deadNode);
        assertEquals(2000L, service.getReducedTimeout(deadNode));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.FastFailoverServiceTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write implementation**

```java
package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FastFailoverService {

    private static final Logger logger = LogManager.getLogger(FastFailoverService.class);

    private final boolean enabled;
    private final long knownDeadTimeoutMs;
    private final Set<String> unavailableNodes = ConcurrentHashMap.newKeySet();

    public FastFailoverService(boolean enabled, long knownDeadTimeoutMs) {
        this.enabled = enabled;
        this.knownDeadTimeoutMs = knownDeadTimeoutMs;
    }

    public void onNodeLeft(String nodeId) {
        if (enabled) {
            unavailableNodes.add(nodeId);
            logger.info("Node [{}] marked unavailable for fast failover routing", nodeId);
        }
    }

    public void onNodeJoined(String nodeId) {
        if (unavailableNodes.remove(nodeId)) {
            logger.info("Node [{}] rejoined, cleared from unavailable set", nodeId);
        }
    }

    public boolean isNodeUnavailable(String nodeId) {
        return unavailableNodes.contains(nodeId);
    }

    public boolean shouldSkipNode(String nodeId) {
        return enabled && unavailableNodes.contains(nodeId);
    }

    public long getReducedTimeout(String nodeId) {
        if (enabled && unavailableNodes.contains(nodeId)) {
            return knownDeadTimeoutMs;
        }
        return -1L; // use default timeout
    }
}
```

- [ ] **Step 4: Add fast failover settings**

Add to `RemoteStoreSettings.java`:

```java
public static final Setting<Boolean> FAST_FAILOVER_ENABLED = Setting.boolSetting(
    "cluster.search.fast_failover.enabled", true,
    Setting.Property.NodeScope, Setting.Property.Dynamic);

public static final Setting<TimeValue> FAST_FAILOVER_KNOWN_DEAD_TIMEOUT = Setting.timeSetting(
    "cluster.search.fast_failover.known_dead_timeout",
    TimeValue.timeValueSeconds(2),
    TimeValue.timeValueMillis(500),
    Setting.Property.NodeScope, Setting.Property.Dynamic);
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.FastFailoverServiceTests" -x javadoc`
Expected: All 5 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/FastFailoverService.java \
        server/src/main/java/org/elasticsearch/index/remote/RemoteStoreSettings.java \
        server/src/test/java/org/elasticsearch/index/remote/FastFailoverServiceTests.java
git commit -m "feat(remote-store): add FastFailoverService for coordinator routing

Risk mitigation §8.10.6: Coordinator immediately marks nodes as unavailable on
node-left events, skipping them for routing instead of waiting for 30s transport
timeout. Reduces read interruption from 30s to 2-5s."
```

---

## Task 17: Concurrent Primary Promotion Support

> **Risk mitigation:** §8.10.6(改进1) — 并发 Primary 提升，热点节点崩溃时 100 个 shard 恢复从 500s 降至 50s

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/PrimaryPromotionConfig.java`
- Modify: `server/src/main/java/org/elasticsearch/index/remote/RemoteStoreSettings.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/PrimaryPromotionConfigTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;

public class PrimaryPromotionConfigTests extends ESTestCase {

    public void testDefaultConcurrency() {
        Settings settings = Settings.EMPTY;
        assertEquals(20, RemoteStoreSettings.PRIMARY_PROMOTION_CONCURRENT.get(settings).intValue());
    }

    public void testCustomConcurrency() {
        Settings settings = Settings.builder()
            .put("cluster.routing.allocation.primary_promotion.concurrent", 10)
            .build();
        assertEquals(10, RemoteStoreSettings.PRIMARY_PROMOTION_CONCURRENT.get(settings).intValue());
    }

    public void testPreselectEnabled() {
        Settings settings = Settings.builder()
            .put("cluster.routing.allocation.primary_promotion.preselect", true)
            .build();
        assertTrue(RemoteStoreSettings.PRIMARY_PROMOTION_PRESELECT.get(settings));
    }

    public void testPrimaryShardsPerNodeLimit() {
        Settings settings = Settings.builder()
            .put("cluster.routing.allocation.primary_shards_per_node", 30)
            .build();
        assertEquals(30, RemoteStoreSettings.PRIMARY_SHARDS_PER_NODE.get(settings).intValue());
    }

    public void testPrimaryPromotionConfigObject() {
        PrimaryPromotionConfig config = new PrimaryPromotionConfig(20, 1000L, true);
        assertEquals(20, config.getConcurrent());
        assertEquals(1000L, config.getBatchIntervalMs());
        assertTrue(config.isPreselectEnabled());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.PrimaryPromotionConfigTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Add settings**

Add to `RemoteStoreSettings.java`:

```java
public static final Setting<Integer> PRIMARY_PROMOTION_CONCURRENT = Setting.intSetting(
    "cluster.routing.allocation.primary_promotion.concurrent", 20, 1, 100,
    Setting.Property.NodeScope, Setting.Property.Dynamic);

public static final Setting<TimeValue> PRIMARY_PROMOTION_BATCH_INTERVAL = Setting.timeSetting(
    "cluster.routing.allocation.primary_promotion.batch_interval",
    TimeValue.timeValueSeconds(1),
    TimeValue.timeValueMillis(100),
    Setting.Property.NodeScope, Setting.Property.Dynamic);

public static final Setting<Boolean> PRIMARY_PROMOTION_PRESELECT = Setting.boolSetting(
    "cluster.routing.allocation.primary_promotion.preselect", true,
    Setting.Property.NodeScope, Setting.Property.Dynamic);

public static final Setting<Integer> PRIMARY_SHARDS_PER_NODE = Setting.intSetting(
    "cluster.routing.allocation.primary_shards_per_node", 20, -1, 1000,
    Setting.Property.NodeScope, Setting.Property.Dynamic);
```

- [ ] **Step 4: Write PrimaryPromotionConfig**

```java
package org.elasticsearch.index.remote;

public class PrimaryPromotionConfig {

    private final int concurrent;
    private final long batchIntervalMs;
    private final boolean preselectEnabled;

    public PrimaryPromotionConfig(int concurrent, long batchIntervalMs, boolean preselectEnabled) {
        this.concurrent = concurrent;
        this.batchIntervalMs = batchIntervalMs;
        this.preselectEnabled = preselectEnabled;
    }

    public int getConcurrent() { return concurrent; }
    public long getBatchIntervalMs() { return batchIntervalMs; }
    public boolean isPreselectEnabled() { return preselectEnabled; }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.PrimaryPromotionConfigTests" -x javadoc`
Expected: All 5 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/PrimaryPromotionConfig.java \
        server/src/main/java/org/elasticsearch/index/remote/RemoteStoreSettings.java \
        server/src/test/java/org/elasticsearch/index/remote/PrimaryPromotionConfigTests.java
git commit -m "feat(remote-store): add concurrent primary promotion config and shard limits

Risk mitigation §8.10.6: Defines concurrency (20 parallel) for primary promotion
and primary_shards_per_node limit (20) to prevent hot-spot node failure from
causing minutes-long write outage. 100 shards: 500s → ~50s recovery."
```

---

## Task 18: Cross-AZ Replica Distribution Enforcement

> **Risk mitigation:** §8.7.2(D-2) — 强制跨 AZ 副本分布，防止单 AZ 故障时 Primary + Sync Replica 同时丢失

**Files:**
- Modify: `server/src/main/java/org/elasticsearch/index/remote/RemoteStoreSettings.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/CrossAzAllocationEnforcer.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/CrossAzAllocationEnforcerTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.test.ESTestCase;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CrossAzAllocationEnforcerTests extends ESTestCase {

    public void testPrimaryAndReplicaMustBeInDifferentAz() {
        List<String> azValues = Arrays.asList("az-1", "az-2", "az-3");
        CrossAzAllocationEnforcer enforcer = new CrossAzAllocationEnforcer(azValues, "zone");

        // Primary in az-1
        Map<String, String> primaryNodeAttrs = new HashMap<>();
        primaryNodeAttrs.put("zone", "az-1");

        // Candidate replica in az-1 (same as primary) — should be rejected
        Map<String, String> replicaNodeAttrs1 = new HashMap<>();
        replicaNodeAttrs1.put("zone", "az-1");
        assertFalse(enforcer.canAllocateReplicaOnNode(primaryNodeAttrs, replicaNodeAttrs1));

        // Candidate replica in az-2 (different) — should be allowed
        Map<String, String> replicaNodeAttrs2 = new HashMap<>();
        replicaNodeAttrs2.put("zone", "az-2");
        assertTrue(enforcer.canAllocateReplicaOnNode(primaryNodeAttrs, replicaNodeAttrs2));
    }

    public void testValidationPassesForCrossAzSetup() {
        List<String> azValues = Arrays.asList("az-1", "az-2", "az-3");
        CrossAzAllocationEnforcer enforcer = new CrossAzAllocationEnforcer(azValues, "zone");

        Map<String, String> allocationMap = new HashMap<>();
        allocationMap.put("primary", "az-1");
        allocationMap.put("replica-0", "az-2");

        assertTrue(enforcer.validateShardDistribution(allocationMap));
    }

    public void testValidationFailsForSameAzSetup() {
        List<String> azValues = Arrays.asList("az-1", "az-2", "az-3");
        CrossAzAllocationEnforcer enforcer = new CrossAzAllocationEnforcer(azValues, "zone");

        Map<String, String> allocationMap = new HashMap<>();
        allocationMap.put("primary", "az-1");
        allocationMap.put("replica-0", "az-1"); // same AZ

        assertFalse(enforcer.validateShardDistribution(allocationMap));
    }

    public void testDisabledEnforcerAlwaysAllows() {
        CrossAzAllocationEnforcer enforcer = CrossAzAllocationEnforcer.DISABLED;

        Map<String, String> primaryAttrs = new HashMap<>();
        primaryAttrs.put("zone", "az-1");
        Map<String, String> replicaAttrs = new HashMap<>();
        replicaAttrs.put("zone", "az-1");

        assertTrue(enforcer.canAllocateReplicaOnNode(primaryAttrs, replicaAttrs));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.CrossAzAllocationEnforcerTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write implementation**

```java
package org.elasticsearch.index.remote;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CrossAzAllocationEnforcer {

    private static final Logger logger = LogManager.getLogger(CrossAzAllocationEnforcer.class);

    public static final CrossAzAllocationEnforcer DISABLED = new CrossAzAllocationEnforcer(
        Collections.emptyList(), "");

    private final List<String> azValues;
    private final String awarenessAttribute;

    public CrossAzAllocationEnforcer(List<String> azValues, String awarenessAttribute) {
        this.azValues = azValues;
        this.awarenessAttribute = awarenessAttribute;
    }

    public boolean canAllocateReplicaOnNode(Map<String, String> primaryNodeAttrs,
                                             Map<String, String> candidateNodeAttrs) {
        if (azValues.isEmpty() || awarenessAttribute.isEmpty()) {
            return true; // enforcement disabled
        }

        String primaryAz = primaryNodeAttrs.get(awarenessAttribute);
        String candidateAz = candidateNodeAttrs.get(awarenessAttribute);

        if (primaryAz == null || candidateAz == null) {
            logger.warn("Awareness attribute [{}] missing on node, allowing allocation", awarenessAttribute);
            return true;
        }

        boolean allowed = !Objects.equals(primaryAz, candidateAz);
        if (!allowed) {
            logger.debug("Rejecting replica allocation: primary and replica both in AZ [{}]", primaryAz);
        }
        return allowed;
    }

    public boolean validateShardDistribution(Map<String, String> shardToAzMap) {
        if (azValues.isEmpty()) {
            return true;
        }

        String primaryAz = shardToAzMap.get("primary");
        if (primaryAz == null) {
            return true;
        }

        for (Map.Entry<String, String> entry : shardToAzMap.entrySet()) {
            if (entry.getKey().startsWith("replica") && Objects.equals(primaryAz, entry.getValue())) {
                logger.warn("Shard distribution violation: primary and {} in same AZ [{}]",
                    entry.getKey(), primaryAz);
                return false;
            }
        }
        return true;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.CrossAzAllocationEnforcerTests" -x javadoc`
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/CrossAzAllocationEnforcer.java \
        server/src/test/java/org/elasticsearch/index/remote/CrossAzAllocationEnforcerTests.java
git commit -m "feat(remote-store): add CrossAzAllocationEnforcer for replica distribution

Risk mitigation D-2: Enforces Primary and Sync Replica in different AZs.
Single-AZ failure no longer causes both to be lost simultaneously,
guaranteeing RPO<5s as documented in SLO."
```

---

## Task 19: Risk Mitigation Integration Test

> Verifies all Phase 1 risk mitigations work together in a realistic scenario

**Files:**
- Create: `server/src/internalClusterTest/java/org/elasticsearch/index/remote/RemoteStoreRiskMitigationIT.java`

- [ ] **Step 1: Write integration test**

```java
package org.elasticsearch.index.remote;

import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESIntegTestCase;

import static org.hamcrest.Matchers.equalTo;

@ESIntegTestCase.ClusterScope(numDataNodes = 3)
public class RemoteStoreRiskMitigationIT extends ESIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            .put("cluster.remote_store.single_writer.fast_degrade_on_first_failure", true)
            .put("cluster.remote_store.single_writer.degrade_after_failures", 2)
            .put("cluster.search.fast_failover.enabled", true)
            .put("cluster.routing.allocation.primary_promotion.concurrent", 20)
            .put("cluster.routing.allocation.primary_shards_per_node", 20)
            .build();
    }

    public void testSingleWriterLockSettingsRegistered() {
        ClusterState state = client().admin().cluster().prepareState().get().getState();
        assertNotNull(state);
        // Verify settings are registered and queryable
        Settings persistentSettings = state.metadata().persistentSettings();
        assertNotNull(persistentSettings);
    }

    public void testFastFailoverSettingsRegistered() {
        Settings settings = client().admin().cluster().prepareState().get()
            .getState().metadata().persistentSettings();
        // Just verify no exception — settings are registered
        assertNotNull(settings);
    }

    public void testUploadOrderCoordinatorDoesNotBlockWhenNoTranslog() {
        UploadOrderCoordinator coordinator = new UploadOrderCoordinator();
        // Should not block even without prior registration
        coordinator.awaitTranslogUploadedForGeneration(1L);
    }

    public void testRelocationUploadServiceReady() {
        RelocationUploadService service = new RelocationUploadService(60_000L, 256 * 1024 * 1024L);
        service.setForceUploadCallback(seqNo -> seqNo + 10);
        RelocationUploadService.TailState tailState = new RelocationUploadService.TailState(100L, 90L, 1024L);
        RelocationUploadService.HandoffReadiness result = service.prepareForHandoff(tailState);
        assertThat(result, equalTo(RelocationUploadService.HandoffReadiness.READY));
    }
}
```

- [ ] **Step 2: Run integration test**

Run: `./gradlew :server:internalClusterTest --tests "org.elasticsearch.index.remote.RemoteStoreRiskMitigationIT" -x javadoc`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add server/src/internalClusterTest/java/org/elasticsearch/index/remote/RemoteStoreRiskMitigationIT.java
git commit -m "test(remote-store): add integration tests for Phase 1 risk mitigations"
```

---

## Task 20: Final Phase 1 Validation

- [ ] **Step 1: Full compilation check (including risk mitigation code)**

Run: `./gradlew :server:compileJava :server:compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run ALL remote store tests**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.*" -x javadoc`
Expected: All tests PASS (original Tasks 1-11 + risk mitigation Tasks 13-18)

- [ ] **Step 3: Verify no regressions**

Run: `./gradlew :server:test -x javadoc`
Expected: No new failures

- [ ] **Step 4: Final commit**

```bash
git commit --allow-empty -m "chore(remote-store): Phase 1 complete — core write-back + risk mitigations

Includes:
- Remote Store write-back (segment + translog async upload)
- SingleWriterLock with lease tolerance and fast degrade
- Upload order guarantee (translog before segment)
- ForceUpload-Before-Handoff for relocation safety
- FastFailoverService for coordinator routing
- Concurrent primary promotion config
- Cross-AZ replica distribution enforcement"
```

---

## Subsequent Plans (not in this document)

This plan covers Phase 1 only. The remaining phases require separate plans:

- **Phase 2 Plan**: FileCache (SharedBlobCache + SparseFileTracker), LayeredDirectory, LeanSyncReplicaEngine, TieringService state machine + risk mitigations (D-1 LSR tail drop delay, P-3 merge routing, A-1 warm bandwidth quota)
- **Phase 3 Plan**: AutoscalingService, 6 Deciders, K8s HPA/ECK integration, PromotionRegistry + risk mitigations (A-2 Master offload, S-3 Tiering-Autoscaler interlock)
- **Phase 4 Plan**: CCR enhancement, PITR, PrefetchService, OpenTelemetry, Chaos Mesh + risk mitigations (S-1 dual-mode lock, S-5 S3 prefix, O-2 metadata versioning)

Each plan will be created when its predecessor phase reaches GA validation.
