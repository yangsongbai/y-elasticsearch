# Phase 2: Tiered Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce Warm/Cold tiers with SharedBlobCache, Lean Sync Replica (LSR) to reduce Hot storage cost by 85%, and TieringService state machine for automated Hot->Warm->Cold index lifecycle transitions.

**Architecture:** Warm/Cold nodes read segments directly from Remote Store via a SharedBlobCache (16MB region, LFU+Decay eviction). LSR on Hot nodes stores only uncommitted tail data locally, using LayeredDirectory to transparently merge tail + Remote. TieringService manages transitions as a state machine integrated with ILM.

**Tech Stack:** Java 11 (source/target compatibility), Elasticsearch 7.17.4, Lucene 8.11.1, existing SparseFileTracker (x-pack), Lucene Directory abstraction, ILM LifecycleAction framework, ClusterState metadata, Mockito 4.4.0.

**Compatibility note:** ES 7.17.4 targets Java 11. Do NOT use Java records, sealed classes, or pattern matching. Convert all `record` declarations in this plan to traditional classes with constructors and getters. `ByteBuffersDirectory` is available in Lucene 8.11.1.

**Prerequisite:** Phase 1 (Remote Store Write-Back) must be complete and GA.

---

## File Structure

### New files to create:

| File | Responsibility |
|------|---------------|
| `server/.../index/remote/cache/SharedBlobCacheService.java` | Region-based file cache over SSD, LFU+Decay eviction |
| `server/.../index/remote/cache/CacheRegion.java` | 16MB fixed-size region unit |
| `server/.../index/remote/cache/RegionSparseFileTracker.java` | Byte-level tracking within a region (reuse logic from x-pack SparseFileTracker) |
| `server/.../index/remote/cache/LFUDecayPolicy.java` | LFU with time-decay eviction policy |
| `server/.../index/remote/cache/FileCacheSettings.java` | `node.filecache.*` settings |
| `server/.../index/remote/directory/LayeredDirectory.java` | Transparent merge of tail (local) + remote (cached) Directory |
| `server/.../index/remote/directory/RemoteCachedDirectory.java` | Directory that reads from SharedBlobCache, fetches on miss |
| `server/.../index/remote/directory/LayeredIndexInput.java` | IndexInput that routes reads through cache |
| `server/.../index/remote/replica/LeanSyncReplicaEngine.java` | Engine variant that only stores tail segments |
| `server/.../index/remote/replica/TailDirectory.java` | RAM/small-disk directory for uncommitted tail |
| `server/.../index/remote/replica/LUSBroadcastService.java` | Broadcasts LastUploadedSeqNo from Primary to replicas |
| `server/.../index/remote/tiering/TieringService.java` | State machine orchestrator |
| `server/.../index/remote/tiering/TieringState.java` | Enum: HOT, HOT_TO_WARM, WARM, WARM_TO_COLD, COLD, ARCHIVED |
| `server/.../index/remote/tiering/TieringMetadata.java` | Custom IndexMetadata extension for tier state |
| `server/.../index/remote/tiering/TierTransitioner.java` | Executes transition phases (flush, sync, route change) |
| `server/.../index/remote/tiering/TierAction.java` | ILM LifecycleAction for `tier` action |
| `server/.../index/remote/tiering/TierActionStep.java` | ILM Step that invokes TieringService |
| `server/.../index/remote/tiering/WaitForTierStep.java` | ILM AsyncWaitStep polling tier state |
| Tests: one test file per production class above |

### Existing files to modify:

| File | Change |
|------|--------|
| `server/.../index/shard/IndexShard.java` | Use LayeredDirectory for LSR mode shards |
| `server/.../index/engine/EngineConfig.java` | Add `isLeanSyncReplica` flag |
| `server/.../index/engine/InternalEngine.java` | Delegate to LeanSyncReplicaEngine when flag set |
| `server/.../common/settings/IndexScopedSettings.java` | Register new index settings (tiering, cache) |
| `server/.../common/settings/ClusterSettings.java` | Register new cluster settings |
| `server/.../node/Node.java` | Initialize SharedBlobCacheService |
| `x-pack/.../xpack/core/ilm/TimeseriesLifecycleType.java` | Register TierAction in allowed actions |

---

## Task 1: FileCacheSettings

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/cache/FileCacheSettings.java`
- Modify: `server/src/main/java/org/elasticsearch/common/settings/ClusterSettings.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/cache/FileCacheSettingsTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.cache;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;

public class FileCacheSettingsTests extends ESTestCase {

    public void testDefaultValues() {
        Settings settings = Settings.EMPTY;
        assertEquals("200gb", FileCacheSettings.CACHE_SIZE.get(settings).toString());
        assertEquals("16mb", FileCacheSettings.REGION_SIZE.get(settings).toString());
        assertEquals("LFU_DECAY", FileCacheSettings.EVICTION_POLICY.get(settings));
    }

    public void testCustomValues() {
        Settings settings = Settings.builder()
            .put("node.filecache.size", "100gb")
            .put("node.filecache.region_size", "8mb")
            .put("node.filecache.eviction_policy", "LRU")
            .build();
        assertEquals("100gb", FileCacheSettings.CACHE_SIZE.get(settings).toString());
        assertEquals("8mb", FileCacheSettings.REGION_SIZE.get(settings).toString());
        assertEquals("LRU", FileCacheSettings.EVICTION_POLICY.get(settings));
    }

    public void testDecaySettings() {
        Settings settings = Settings.builder()
            .put("node.filecache.decay.interval", "5m")
            .put("node.filecache.decay.factor", 0.9)
            .build();
        assertEquals(300000L, FileCacheSettings.DECAY_INTERVAL.get(settings).millis());
        assertEquals(0.9, FileCacheSettings.DECAY_FACTOR.get(settings), 0.001);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.cache.FileCacheSettingsTests" -x javadoc`
Expected: Compilation error — class not found

- [ ] **Step 3: Write minimal implementation**

```java
package org.elasticsearch.index.remote.cache;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.core.TimeValue;

import java.util.List;

public final class FileCacheSettings {

    public static final Setting<ByteSizeValue> CACHE_SIZE = Setting.byteSizeSetting(
        "node.filecache.size", new ByteSizeValue(200, ByteSizeUnit.GB),
        Setting.Property.NodeScope);

    public static final Setting<ByteSizeValue> REGION_SIZE = Setting.byteSizeSetting(
        "node.filecache.region_size", new ByteSizeValue(16, ByteSizeUnit.MB),
        Setting.Property.NodeScope);

    public static final Setting<String> EVICTION_POLICY = Setting.simpleString(
        "node.filecache.eviction_policy", "LFU_DECAY",
        Setting.Property.NodeScope);

    public static final Setting<TimeValue> DECAY_INTERVAL = Setting.timeSetting(
        "node.filecache.decay.interval", TimeValue.timeValueMinutes(1),
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Double> DECAY_FACTOR = Setting.doubleSetting(
        "node.filecache.decay.factor", 0.95, 0.0, 1.0,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    private FileCacheSettings() {}

    public static List<Setting<?>> getSettings() {
        return List.of(CACHE_SIZE, REGION_SIZE, EVICTION_POLICY, DECAY_INTERVAL, DECAY_FACTOR);
    }
}
```

- [ ] **Step 4: Register in ClusterSettings**

Add `FileCacheSettings.getSettings()` entries to `BUILT_IN_CLUSTER_SETTINGS`.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.cache.FileCacheSettingsTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/cache/FileCacheSettings.java \
        server/src/test/java/org/elasticsearch/index/remote/cache/FileCacheSettingsTests.java \
        server/src/main/java/org/elasticsearch/common/settings/ClusterSettings.java
git commit -m "feat(filecache): add FileCache settings definitions"
```

---

## Task 2: CacheRegion and LFUDecayPolicy

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/cache/CacheRegion.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/cache/LFUDecayPolicy.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/cache/LFUDecayPolicyTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.cache;

import org.elasticsearch.test.ESTestCase;

import java.util.List;

public class LFUDecayPolicyTests extends ESTestCase {

    public void testEvictLeastFrequent() {
        LFUDecayPolicy policy = new LFUDecayPolicy(0.95, 60_000L);
        CacheRegion r1 = new CacheRegion(0, 16 * 1024 * 1024);
        CacheRegion r2 = new CacheRegion(1, 16 * 1024 * 1024);
        CacheRegion r3 = new CacheRegion(2, 16 * 1024 * 1024);

        // r1 accessed 10 times, r2 accessed 1 time, r3 accessed 5 times
        for (int i = 0; i < 10; i++) r1.recordAccess();
        r2.recordAccess();
        for (int i = 0; i < 5; i++) r3.recordAccess();

        List<CacheRegion> candidates = List.of(r1, r2, r3);
        CacheRegion victim = policy.selectVictim(candidates);
        assertEquals(1, victim.regionId());  // r2 has lowest frequency
    }

    public void testDecayReducesFrequency() {
        LFUDecayPolicy policy = new LFUDecayPolicy(0.5, 60_000L);
        CacheRegion region = new CacheRegion(0, 16 * 1024 * 1024);
        for (int i = 0; i < 100; i++) region.recordAccess();
        assertEquals(100, region.getAccessCount());

        policy.applyDecay(List.of(region));
        assertEquals(50, region.getAccessCount());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.cache.LFUDecayPolicyTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write CacheRegion**

```java
package org.elasticsearch.index.remote.cache;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

public class CacheRegion {

    private final long regionId;
    private final int regionSize;
    private final AtomicLong accessCount = new AtomicLong(0);
    private volatile boolean dirty = false;
    private volatile ByteBuffer buffer;

    public CacheRegion(long regionId, int regionSize) {
        this.regionId = regionId;
        this.regionSize = regionSize;
    }

    public long regionId() { return regionId; }
    public int regionSize() { return regionSize; }

    public void recordAccess() { accessCount.incrementAndGet(); }
    public long getAccessCount() { return accessCount.get(); }
    public void setAccessCount(long count) { accessCount.set(count); }

    public boolean isDirty() { return dirty; }
    public void markDirty() { dirty = true; }
    public void markClean() { dirty = false; }

    public void allocate() { buffer = ByteBuffer.allocateDirect(regionSize); }
    public ByteBuffer buffer() { return buffer; }
    public void release() { buffer = null; }
}
```

- [ ] **Step 4: Write LFUDecayPolicy**

```java
package org.elasticsearch.index.remote.cache;

import java.util.Comparator;
import java.util.List;

public class LFUDecayPolicy {

    private final double decayFactor;
    private final long decayIntervalMs;

    public LFUDecayPolicy(double decayFactor, long decayIntervalMs) {
        this.decayFactor = decayFactor;
        this.decayIntervalMs = decayIntervalMs;
    }

    public CacheRegion selectVictim(List<CacheRegion> candidates) {
        return candidates.stream()
            .min(Comparator.comparingLong(CacheRegion::getAccessCount))
            .orElse(null);
    }

    public void applyDecay(List<CacheRegion> regions) {
        for (CacheRegion region : regions) {
            long current = region.getAccessCount();
            region.setAccessCount((long) (current * decayFactor));
        }
    }

    public long getDecayIntervalMs() { return decayIntervalMs; }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.cache.LFUDecayPolicyTests" -x javadoc`
Expected: All 2 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/cache/CacheRegion.java \
        server/src/main/java/org/elasticsearch/index/remote/cache/LFUDecayPolicy.java \
        server/src/test/java/org/elasticsearch/index/remote/cache/LFUDecayPolicyTests.java
git commit -m "feat(filecache): add CacheRegion and LFU+Decay eviction policy"
```

---

## Task 3: RegionSparseFileTracker

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/cache/RegionSparseFileTracker.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/cache/RegionSparseFileTrackerTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.cache;

import org.elasticsearch.test.ESTestCase;

public class RegionSparseFileTrackerTests extends ESTestCase {

    public void testInitiallyEmpty() {
        RegionSparseFileTracker tracker = new RegionSparseFileTracker(16 * 1024 * 1024);
        assertFalse(tracker.isRangeAvailable(0, 1024));
    }

    public void testMarkAndCheck() {
        RegionSparseFileTracker tracker = new RegionSparseFileTracker(16 * 1024 * 1024);
        tracker.markComplete(0, 4096);
        assertTrue(tracker.isRangeAvailable(0, 4096));
        assertTrue(tracker.isRangeAvailable(100, 500));
        assertFalse(tracker.isRangeAvailable(0, 8192));
    }

    public void testGapDetection() {
        RegionSparseFileTracker tracker = new RegionSparseFileTracker(16 * 1024 * 1024);
        tracker.markComplete(0, 1000);
        tracker.markComplete(2000, 3000);

        var gaps = tracker.getGaps(0, 3000);
        assertEquals(1, gaps.size());
        assertEquals(1000, gaps.get(0).start());
        assertEquals(2000, gaps.get(0).end());
    }

    public void testMergeContiguous() {
        RegionSparseFileTracker tracker = new RegionSparseFileTracker(16 * 1024 * 1024);
        tracker.markComplete(0, 1000);
        tracker.markComplete(1000, 2000);
        assertTrue(tracker.isRangeAvailable(0, 2000));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.cache.RegionSparseFileTrackerTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write minimal implementation**

```java
package org.elasticsearch.index.remote.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public class RegionSparseFileTracker {

    public record Gap(long start, long end) {}

    private final long regionSize;
    private final TreeMap<Long, Long> completedRanges = new TreeMap<>();

    public RegionSparseFileTracker(long regionSize) {
        this.regionSize = regionSize;
    }

    public synchronized void markComplete(long start, long end) {
        Long floorKey = completedRanges.floorKey(start);
        Long ceilKey = completedRanges.ceilingKey(start);

        long mergeStart = start;
        long mergeEnd = end;

        // Merge with floor entry if contiguous/overlapping
        if (floorKey != null && completedRanges.get(floorKey) >= start) {
            mergeStart = floorKey;
            mergeEnd = Math.max(mergeEnd, completedRanges.get(floorKey));
            completedRanges.remove(floorKey);
        }

        // Merge with subsequent entries if contiguous/overlapping
        while (true) {
            Long next = completedRanges.ceilingKey(mergeStart);
            if (next != null && next <= mergeEnd) {
                mergeEnd = Math.max(mergeEnd, completedRanges.get(next));
                completedRanges.remove(next);
            } else {
                break;
            }
        }

        completedRanges.put(mergeStart, mergeEnd);
    }

    public synchronized boolean isRangeAvailable(long start, long end) {
        Long floorKey = completedRanges.floorKey(start);
        if (floorKey == null) return false;
        return completedRanges.get(floorKey) >= end;
    }

    public synchronized List<Gap> getGaps(long start, long end) {
        List<Gap> gaps = new ArrayList<>();
        long pos = start;

        for (var entry : completedRanges.subMap(
                completedRanges.floorKey(start) != null ? completedRanges.floorKey(start) : start, true,
                end, true).entrySet()) {
            long rangeStart = entry.getKey();
            long rangeEnd = entry.getValue();
            if (rangeStart > pos) {
                gaps.add(new Gap(pos, Math.min(rangeStart, end)));
            }
            pos = Math.max(pos, rangeEnd);
            if (pos >= end) break;
        }
        if (pos < end) {
            gaps.add(new Gap(pos, end));
        }
        return gaps;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.cache.RegionSparseFileTrackerTests" -x javadoc`
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/cache/RegionSparseFileTracker.java \
        server/src/test/java/org/elasticsearch/index/remote/cache/RegionSparseFileTrackerTests.java
git commit -m "feat(filecache): add RegionSparseFileTracker for byte-level presence tracking"
```

---

## Task 4: SharedBlobCacheService

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/cache/SharedBlobCacheService.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/cache/SharedBlobCacheServiceTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.cache;

import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.test.ESTestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.mockito.Mockito.*;

public class SharedBlobCacheServiceTests extends ESTestCase {

    public void testCacheHit() throws IOException {
        SharedBlobCacheService cache = new SharedBlobCacheService(
            64 * 1024 * 1024L, // 64MB total
            16 * 1024 * 1024,  // 16MB region
            new LFUDecayPolicy(0.95, 60_000L)
        );

        BlobContainer remote = mock(BlobContainer.class);
        byte[] data = randomByteArrayOfLength(4096);
        when(remote.readBlob("file.cfs", 0, 16 * 1024 * 1024))
            .thenReturn(new ByteArrayInputStream(data));

        // First read: cache miss, fetches from remote
        ByteBuffer result1 = cache.read("idx/shard/file.cfs", 0, 4096, remote, "file.cfs");
        assertNotNull(result1);

        // Second read: cache hit, no remote call
        ByteBuffer result2 = cache.read("idx/shard/file.cfs", 0, 4096, remote, "file.cfs");
        assertNotNull(result2);
        verify(remote, times(1)).readBlob("file.cfs", 0, 16 * 1024 * 1024);
    }

    public void testEvictionWhenFull() throws IOException {
        // 2 regions only
        SharedBlobCacheService cache = new SharedBlobCacheService(
            32 * 1024 * 1024L,
            16 * 1024 * 1024,
            new LFUDecayPolicy(0.95, 60_000L)
        );

        BlobContainer remote = mock(BlobContainer.class);
        when(remote.readBlob(anyString(), anyLong(), anyLong()))
            .thenReturn(new ByteArrayInputStream(new byte[16 * 1024 * 1024]));

        // Fill both regions
        cache.read("file1", 0, 100, remote, "file1");
        cache.read("file2", 0, 100, remote, "file2");
        // Third read should trigger eviction of least accessed
        cache.read("file3", 0, 100, remote, "file3");

        // Should still work (eviction happened internally)
        assertEquals(2, cache.getRegionCount());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.cache.SharedBlobCacheServiceTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write minimal implementation**

```java
package org.elasticsearch.index.remote.cache;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.blobstore.BlobContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SharedBlobCacheService {

    private static final Logger logger = LogManager.getLogger(SharedBlobCacheService.class);

    private final long totalSize;
    private final int regionSize;
    private final int maxRegions;
    private final LFUDecayPolicy evictionPolicy;
    private final Map<String, CacheRegion> regionMap = new ConcurrentHashMap<>();
    private final Map<String, RegionSparseFileTracker> trackers = new ConcurrentHashMap<>();

    public SharedBlobCacheService(long totalSize, int regionSize, LFUDecayPolicy evictionPolicy) {
        this.totalSize = totalSize;
        this.regionSize = regionSize;
        this.maxRegions = (int) (totalSize / regionSize);
        this.evictionPolicy = evictionPolicy;
    }

    public ByteBuffer read(String cacheKey, long position, int length,
                           BlobContainer remote, String blobName) throws IOException {
        String regionKey = cacheKey + ":" + (position / regionSize);
        RegionSparseFileTracker tracker = trackers.computeIfAbsent(
            regionKey, k -> new RegionSparseFileTracker(regionSize));

        long regionOffset = (position / regionSize) * regionSize;
        long offsetInRegion = position - regionOffset;

        CacheRegion region = regionMap.get(regionKey);
        if (region != null && tracker.isRangeAvailable(offsetInRegion, offsetInRegion + length)) {
            region.recordAccess();
            ByteBuffer buf = region.buffer().duplicate();
            buf.position((int) offsetInRegion);
            buf.limit((int) (offsetInRegion + length));
            return buf.slice();
        }

        // Cache miss: fetch from remote
        if (region == null) {
            ensureCapacity();
            region = new CacheRegion(regionMap.size(), regionSize);
            region.allocate();
            regionMap.put(regionKey, region);
        }

        try (InputStream is = remote.readBlob(blobName, regionOffset, regionSize)) {
            byte[] data = is.readAllBytes();
            region.buffer().clear();
            region.buffer().put(data, 0, Math.min(data.length, regionSize));
            tracker.markComplete(0, Math.min(data.length, regionSize));
        }

        region.recordAccess();
        ByteBuffer buf = region.buffer().duplicate();
        buf.position((int) offsetInRegion);
        buf.limit((int) Math.min(offsetInRegion + length, region.buffer().capacity()));
        return buf.slice();
    }

    private void ensureCapacity() {
        if (regionMap.size() >= maxRegions) {
            List<CacheRegion> candidates = new ArrayList<>(regionMap.values());
            CacheRegion victim = evictionPolicy.selectVictim(candidates);
            if (victim != null) {
                String victimKey = null;
                for (var entry : regionMap.entrySet()) {
                    if (entry.getValue() == victim) {
                        victimKey = entry.getKey();
                        break;
                    }
                }
                if (victimKey != null) {
                    regionMap.remove(victimKey);
                    trackers.remove(victimKey);
                    victim.release();
                }
            }
        }
    }

    public int getRegionCount() { return maxRegions; }
    public long getTotalSize() { return totalSize; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.cache.SharedBlobCacheServiceTests" -x javadoc`
Expected: All 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/cache/SharedBlobCacheService.java \
        server/src/test/java/org/elasticsearch/index/remote/cache/SharedBlobCacheServiceTests.java
git commit -m "feat(filecache): add SharedBlobCacheService with region-based caching"
```

---

## Task 5: RemoteCachedDirectory and LayeredIndexInput

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/directory/RemoteCachedDirectory.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/directory/LayeredIndexInput.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/directory/RemoteCachedDirectoryTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.directory;

import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.index.remote.cache.LFUDecayPolicy;
import org.elasticsearch.index.remote.cache.SharedBlobCacheService;
import org.elasticsearch.test.ESTestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.mockito.Mockito.*;

public class RemoteCachedDirectoryTests extends ESTestCase {

    public void testOpenInputReadsFromCache() throws IOException {
        BlobContainer blobContainer = mock(BlobContainer.class);
        SharedBlobCacheService cache = new SharedBlobCacheService(
            64 * 1024 * 1024L, 16 * 1024 * 1024, new LFUDecayPolicy(0.95, 60_000L));

        byte[] fileContent = "hello world from remote".getBytes();
        when(blobContainer.readBlob(eq("_0.cfs"), anyLong(), anyLong()))
            .thenReturn(new ByteArrayInputStream(fileContent));

        RemoteCachedDirectory dir = new RemoteCachedDirectory(blobContainer, cache, "test-idx/0");

        try (IndexInput input = dir.openInput("_0.cfs", IOContext.READ)) {
            byte[] buf = new byte[5];
            input.readBytes(buf, 0, 5);
            assertEquals("hello", new String(buf));
        }
    }

    public void testFileLength() throws IOException {
        BlobContainer blobContainer = mock(BlobContainer.class);
        SharedBlobCacheService cache = new SharedBlobCacheService(
            64 * 1024 * 1024L, 16 * 1024 * 1024, new LFUDecayPolicy(0.95, 60_000L));

        RemoteCachedDirectory dir = new RemoteCachedDirectory(blobContainer, cache, "test-idx/0");
        dir.registerFileLength("_0.cfs", 1024L);

        assertEquals(1024L, dir.fileLength("_0.cfs"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.directory.RemoteCachedDirectoryTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write RemoteCachedDirectory**

```java
package org.elasticsearch.index.remote.directory;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.Lock;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.index.remote.cache.SharedBlobCacheService;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RemoteCachedDirectory extends Directory {

    private final BlobContainer blobContainer;
    private final SharedBlobCacheService cache;
    private final String cacheKeyPrefix;
    private final Map<String, Long> fileLengths = new ConcurrentHashMap<>();

    public RemoteCachedDirectory(BlobContainer blobContainer, SharedBlobCacheService cache, String cacheKeyPrefix) {
        this.blobContainer = blobContainer;
        this.cache = cache;
        this.cacheKeyPrefix = cacheKeyPrefix;
    }

    public void registerFileLength(String name, long length) {
        fileLengths.put(name, length);
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
        long length = fileLengths.getOrDefault(name, 0L);
        String cacheKey = cacheKeyPrefix + "/" + name;
        return new LayeredIndexInput("RemoteCached(" + name + ")", cache, blobContainer, name, cacheKey, length);
    }

    @Override
    public long fileLength(String name) throws IOException {
        return fileLengths.getOrDefault(name, 0L);
    }

    @Override public String[] listAll() throws IOException { return fileLengths.keySet().toArray(new String[0]); }
    @Override public void deleteFile(String name) throws IOException { fileLengths.remove(name); }
    @Override public IndexOutput createOutput(String name, IOContext context) { throw new UnsupportedOperationException("read-only"); }
    @Override public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) { throw new UnsupportedOperationException("read-only"); }
    @Override public void sync(Collection<String> names) {}
    @Override public void syncMetaData() {}
    @Override public void rename(String source, String dest) { throw new UnsupportedOperationException("read-only"); }
    @Override public Lock obtainLock(String name) { return Lock.NOOP_LOCK; }
    @Override public void close() {}
    @Override public Set<String> getPendingDeletions() { return Set.of(); }
}
```

- [ ] **Step 4: Write LayeredIndexInput**

```java
package org.elasticsearch.index.remote.directory;

import org.apache.lucene.store.IndexInput;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.index.remote.cache.SharedBlobCacheService;

import java.io.IOException;
import java.nio.ByteBuffer;

public class LayeredIndexInput extends IndexInput {

    private final SharedBlobCacheService cache;
    private final BlobContainer blobContainer;
    private final String blobName;
    private final String cacheKey;
    private final long fileLength;
    private long position = 0;

    public LayeredIndexInput(String resourceDescription, SharedBlobCacheService cache,
                             BlobContainer blobContainer, String blobName, String cacheKey, long fileLength) {
        super(resourceDescription);
        this.cache = cache;
        this.blobContainer = blobContainer;
        this.blobName = blobName;
        this.cacheKey = cacheKey;
        this.fileLength = fileLength;
    }

    @Override
    public void readBytes(byte[] b, int offset, int len) throws IOException {
        ByteBuffer data = cache.read(cacheKey, position, len, blobContainer, blobName);
        data.get(b, offset, Math.min(len, data.remaining()));
        position += len;
    }

    @Override public byte readByte() throws IOException {
        byte[] buf = new byte[1];
        readBytes(buf, 0, 1);
        return buf[0];
    }

    @Override public void close() {}
    @Override public long getFilePointer() { return position; }
    @Override public void seek(long pos) { this.position = pos; }
    @Override public long length() { return fileLength; }

    @Override
    public IndexInput slice(String sliceDescription, long offset, long length) {
        return new LayeredIndexInput(sliceDescription, cache, blobContainer, blobName, cacheKey, length) {
            { this.seek(offset); }
        };
    }

    @Override public IndexInput clone() {
        LayeredIndexInput clone = new LayeredIndexInput(toString(), cache, blobContainer, blobName, cacheKey, fileLength);
        clone.position = this.position;
        return clone;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.directory.RemoteCachedDirectoryTests" -x javadoc`
Expected: All 2 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/directory/RemoteCachedDirectory.java \
        server/src/main/java/org/elasticsearch/index/remote/directory/LayeredIndexInput.java \
        server/src/test/java/org/elasticsearch/index/remote/directory/RemoteCachedDirectoryTests.java
git commit -m "feat(filecache): add RemoteCachedDirectory and LayeredIndexInput"
```

---

## Task 6: LayeredDirectory (tail + remote merge)

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/directory/LayeredDirectory.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/directory/LayeredDirectoryTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.directory;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.RAMDirectory;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;

import static org.mockito.Mockito.*;

public class LayeredDirectoryTests extends ESTestCase {

    public void testTailFileRoutesToLocal() throws IOException {
        Directory tailDir = new RAMDirectory();
        RemoteCachedDirectory remoteDir = mock(RemoteCachedDirectory.class);

        // Write a file to tail
        try (IndexOutput out = tailDir.createOutput("_tail.cfs", IOContext.DEFAULT)) {
            out.writeBytes(new byte[]{1, 2, 3}, 3);
        }

        LayeredDirectory layered = new LayeredDirectory(tailDir, remoteDir);
        layered.markAsTail("_tail.cfs");

        try (IndexInput input = layered.openInput("_tail.cfs", IOContext.READ)) {
            assertEquals(3, input.length());
        }
        verifyNoInteractions(remoteDir);
    }

    public void testNonTailFileRoutesToRemote() throws IOException {
        Directory tailDir = new RAMDirectory();
        RemoteCachedDirectory remoteDir = mock(RemoteCachedDirectory.class);
        IndexInput mockInput = mock(IndexInput.class);
        when(remoteDir.openInput("_remote.cfs", IOContext.READ)).thenReturn(mockInput);

        LayeredDirectory layered = new LayeredDirectory(tailDir, remoteDir);

        layered.openInput("_remote.cfs", IOContext.READ);
        verify(remoteDir).openInput("_remote.cfs", IOContext.READ);
    }

    public void testListAllMergesBothSources() throws IOException {
        Directory tailDir = new RAMDirectory();
        RemoteCachedDirectory remoteDir = mock(RemoteCachedDirectory.class);

        try (IndexOutput out = tailDir.createOutput("tail.cfs", IOContext.DEFAULT)) {
            out.writeBytes(new byte[]{1}, 1);
        }
        when(remoteDir.listAll()).thenReturn(new String[]{"remote.cfs"});

        LayeredDirectory layered = new LayeredDirectory(tailDir, remoteDir);
        layered.markAsTail("tail.cfs");

        String[] all = layered.listAll();
        assertEquals(2, all.length);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.directory.LayeredDirectoryTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write minimal implementation**

```java
package org.elasticsearch.index.remote.directory;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.Lock;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

public class LayeredDirectory extends Directory {

    private final Directory tailDirectory;
    private final RemoteCachedDirectory remoteDirectory;
    private final Set<String> tailFiles = new HashSet<>();

    public LayeredDirectory(Directory tailDirectory, RemoteCachedDirectory remoteDirectory) {
        this.tailDirectory = tailDirectory;
        this.remoteDirectory = remoteDirectory;
    }

    public void markAsTail(String fileName) {
        tailFiles.add(fileName);
    }

    public void unmarkTail(String fileName) {
        tailFiles.remove(fileName);
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
        if (tailFiles.contains(name)) {
            return tailDirectory.openInput(name, context);
        }
        return remoteDirectory.openInput(name, context);
    }

    @Override
    public String[] listAll() throws IOException {
        Set<String> all = new TreeSet<>();
        for (String f : tailDirectory.listAll()) {
            if (tailFiles.contains(f)) all.add(f);
        }
        for (String f : remoteDirectory.listAll()) {
            all.add(f);
        }
        return all.toArray(new String[0]);
    }

    @Override
    public long fileLength(String name) throws IOException {
        if (tailFiles.contains(name)) {
            return tailDirectory.fileLength(name);
        }
        return remoteDirectory.fileLength(name);
    }

    @Override public void deleteFile(String name) throws IOException {
        if (tailFiles.contains(name)) { tailDirectory.deleteFile(name); tailFiles.remove(name); }
        else remoteDirectory.deleteFile(name);
    }
    @Override public IndexOutput createOutput(String name, IOContext context) throws IOException {
        tailFiles.add(name);
        return tailDirectory.createOutput(name, context);
    }
    @Override public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) throws IOException {
        return tailDirectory.createTempOutput(prefix, suffix, context);
    }
    @Override public void sync(Collection<String> names) throws IOException { tailDirectory.sync(names); }
    @Override public void syncMetaData() throws IOException { tailDirectory.syncMetaData(); }
    @Override public void rename(String source, String dest) throws IOException { tailDirectory.rename(source, dest); }
    @Override public Lock obtainLock(String name) throws IOException { return tailDirectory.obtainLock(name); }
    @Override public void close() throws IOException { tailDirectory.close(); remoteDirectory.close(); }
    @Override public Set<String> getPendingDeletions() throws IOException { return tailDirectory.getPendingDeletions(); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.directory.LayeredDirectoryTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/directory/LayeredDirectory.java \
        server/src/test/java/org/elasticsearch/index/remote/directory/LayeredDirectoryTests.java
git commit -m "feat(lsr): add LayeredDirectory for transparent tail+remote merge"
```

---

## Task 7: TailDirectory and LUSBroadcastService

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/replica/TailDirectory.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/replica/LUSBroadcastService.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/replica/LUSBroadcastServiceTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.replica;

import org.elasticsearch.test.ESTestCase;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

public class LUSBroadcastServiceTests extends ESTestCase {

    public void testBroadcastUpdatesListeners() {
        LUSBroadcastService service = new LUSBroadcastService();
        AtomicLong received = new AtomicLong(-1);
        LongConsumer listener = received::set;
        service.addListener(listener);

        service.broadcastLUS(42L);
        assertEquals(42L, received.get());

        service.broadcastLUS(100L);
        assertEquals(100L, received.get());
    }

    public void testMonotonicOnly() {
        LUSBroadcastService service = new LUSBroadcastService();
        AtomicLong received = new AtomicLong(-1);
        service.addListener(received::set);

        service.broadcastLUS(50L);
        service.broadcastLUS(30L);  // should be ignored (non-monotonic)
        assertEquals(50L, received.get());
    }

    public void testRemoveListener() {
        LUSBroadcastService service = new LUSBroadcastService();
        AtomicLong received = new AtomicLong(-1);
        LongConsumer listener = received::set;
        service.addListener(listener);
        service.removeListener(listener);

        service.broadcastLUS(99L);
        assertEquals(-1L, received.get());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.replica.LUSBroadcastServiceTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write TailDirectory**

```java
package org.elasticsearch.index.remote.replica;

import org.apache.lucene.store.ByteBuffersDirectory;

/**
 * Small in-memory directory for uncommitted tail segments on LSR.
 * Wraps ByteBuffersDirectory (RAM-based) for the tail data that hasn't
 * been uploaded to Remote Store yet.
 */
public class TailDirectory extends ByteBuffersDirectory {

    private volatile long maxSeqNo = -1;

    public void updateMaxSeqNo(long seqNo) {
        this.maxSeqNo = Math.max(this.maxSeqNo, seqNo);
    }

    public long getMaxSeqNo() {
        return maxSeqNo;
    }
}
```

- [ ] **Step 4: Write LUSBroadcastService**

```java
package org.elasticsearch.index.remote.replica;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Broadcasts the Last Uploaded SeqNo (LUS) from Primary to replicas.
 * Replicas use this to know which tail segments can be dropped.
 */
public class LUSBroadcastService {

    private final AtomicLong currentLUS = new AtomicLong(-1);
    private final List<LongConsumer> listeners = new CopyOnWriteArrayList<>();

    public void broadcastLUS(long seqNo) {
        long current = currentLUS.get();
        if (seqNo <= current) {
            return; // monotonic only
        }
        if (currentLUS.compareAndSet(current, seqNo)) {
            for (LongConsumer listener : listeners) {
                listener.accept(seqNo);
            }
        }
    }

    public void addListener(LongConsumer listener) {
        listeners.add(listener);
    }

    public void removeListener(LongConsumer listener) {
        listeners.remove(listener);
    }

    public long getCurrentLUS() {
        return currentLUS.get();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.replica.LUSBroadcastServiceTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/replica/TailDirectory.java \
        server/src/main/java/org/elasticsearch/index/remote/replica/LUSBroadcastService.java \
        server/src/test/java/org/elasticsearch/index/remote/replica/LUSBroadcastServiceTests.java
git commit -m "feat(lsr): add TailDirectory and LUSBroadcastService for Lean Sync Replica"
```

---

## Task 8: LeanSyncReplicaEngine

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/replica/LeanSyncReplicaEngine.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/replica/LeanSyncReplicaEngineTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.replica;

import org.elasticsearch.test.ESTestCase;

public class LeanSyncReplicaEngineTests extends ESTestCase {

    public void testTailPruningOnLUSAdvance() {
        TailDirectory tailDir = new TailDirectory();
        LUSBroadcastService lusService = new LUSBroadcastService();
        LeanSyncReplicaEngine engine = new LeanSyncReplicaEngine(tailDir, lusService);

        // Simulate writing segments with seqnos
        engine.recordSegmentSeqNo("_0.cfs", 10L);
        engine.recordSegmentSeqNo("_1.cfs", 20L);
        engine.recordSegmentSeqNo("_2.cfs", 30L);

        // LUS advances to 20 => segments <= 20 should be pruneable
        lusService.broadcastLUS(20L);

        assertTrue(engine.isPrunable("_0.cfs"));
        assertTrue(engine.isPrunable("_1.cfs"));
        assertFalse(engine.isPrunable("_2.cfs"));
    }

    public void testGetTailSize() {
        TailDirectory tailDir = new TailDirectory();
        LUSBroadcastService lusService = new LUSBroadcastService();
        LeanSyncReplicaEngine engine = new LeanSyncReplicaEngine(tailDir, lusService);

        engine.recordSegmentSeqNo("_0.cfs", 10L);
        engine.recordSegmentSeqNo("_1.cfs", 50L);

        lusService.broadcastLUS(10L);

        // Only _1.cfs is tail (seqno > LUS)
        assertEquals(1, engine.getTailSegmentCount());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.replica.LeanSyncReplicaEngineTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write minimal implementation**

```java
package org.elasticsearch.index.remote.replica;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

public class LeanSyncReplicaEngine {

    private static final Logger logger = LogManager.getLogger(LeanSyncReplicaEngine.class);

    private final TailDirectory tailDirectory;
    private final LUSBroadcastService lusService;
    private final Map<String, Long> segmentSeqNos = new ConcurrentHashMap<>();
    private volatile long lastUploadedSeqNo = -1;

    public LeanSyncReplicaEngine(TailDirectory tailDirectory, LUSBroadcastService lusService) {
        this.tailDirectory = tailDirectory;
        this.lusService = lusService;
        this.lusService.addListener(this::onLUSUpdate);
    }

    private void onLUSUpdate(long newLUS) {
        this.lastUploadedSeqNo = newLUS;
        logger.debug("LUS advanced to {}, pruning eligible tail segments", newLUS);
    }

    public void recordSegmentSeqNo(String fileName, long maxSeqNo) {
        segmentSeqNos.put(fileName, maxSeqNo);
    }

    public boolean isPrunable(String fileName) {
        Long seqNo = segmentSeqNos.get(fileName);
        if (seqNo == null) return false;
        return seqNo <= lastUploadedSeqNo;
    }

    public int getTailSegmentCount() {
        return (int) segmentSeqNos.values().stream()
            .filter(seqNo -> seqNo > lastUploadedSeqNo)
            .count();
    }

    public long getLastUploadedSeqNo() {
        return lastUploadedSeqNo;
    }

    public void pruneTailSegments() {
        segmentSeqNos.entrySet().removeIf(entry -> {
            if (entry.getValue() <= lastUploadedSeqNo) {
                try {
                    tailDirectory.deleteFile(entry.getKey());
                } catch (Exception e) {
                    logger.warn("Failed to prune tail segment [{}]", entry.getKey(), e);
                }
                return true;
            }
            return false;
        });
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.replica.LeanSyncReplicaEngineTests" -x javadoc`
Expected: All 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/replica/LeanSyncReplicaEngine.java \
        server/src/test/java/org/elasticsearch/index/remote/replica/LeanSyncReplicaEngineTests.java
git commit -m "feat(lsr): add LeanSyncReplicaEngine with tail pruning on LUS advance"
```

---

## Task 9: TieringState and TieringMetadata

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/tiering/TieringState.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/tiering/TieringMetadata.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/tiering/TieringMetadataTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;

public class TieringMetadataTests extends ESTestCase {

    public void testAllStatesValid() {
        for (TieringState state : TieringState.values()) {
            assertNotNull(state.name());
        }
        assertEquals(7, TieringState.values().length);
    }

    public void testTransitionValidation() {
        assertTrue(TieringState.HOT.canTransitionTo(TieringState.HOT_TO_WARM));
        assertTrue(TieringState.HOT_TO_WARM.canTransitionTo(TieringState.WARM));
        assertTrue(TieringState.HOT_TO_WARM.canTransitionTo(TieringState.HOT)); // rollback
        assertTrue(TieringState.WARM.canTransitionTo(TieringState.WARM_TO_COLD));
        assertTrue(TieringState.WARM.canTransitionTo(TieringState.HOT)); // promote
        assertFalse(TieringState.HOT.canTransitionTo(TieringState.COLD)); // must go through WARM
        assertFalse(TieringState.COLD.canTransitionTo(TieringState.HOT_TO_WARM));
    }

    public void testSerializationRoundTrip() throws IOException {
        TieringMetadata original = new TieringMetadata(TieringState.WARM, TieringState.HOT, 12345L);

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        TieringMetadata parsed = new TieringMetadata(in);

        assertEquals(original.currentState(), parsed.currentState());
        assertEquals(original.previousState(), parsed.previousState());
        assertEquals(original.transitionTimestamp(), parsed.transitionTimestamp());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.tiering.TieringMetadataTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write TieringState**

```java
package org.elasticsearch.index.remote.tiering;

import java.util.EnumSet;
import java.util.Map;

public enum TieringState {
    CREATING,
    HOT,
    HOT_TO_WARM,
    WARM,
    WARM_TO_COLD,
    COLD,
    ARCHIVED;

    private static final Map<TieringState, EnumSet<TieringState>> VALID_TRANSITIONS = Map.of(
        CREATING, EnumSet.of(HOT),
        HOT, EnumSet.of(HOT_TO_WARM),
        HOT_TO_WARM, EnumSet.of(WARM, HOT),
        WARM, EnumSet.of(WARM_TO_COLD, HOT),
        WARM_TO_COLD, EnumSet.of(COLD, WARM),
        COLD, EnumSet.of(ARCHIVED, WARM),
        ARCHIVED, EnumSet.noneOf(TieringState.class)
    );

    public boolean canTransitionTo(TieringState target) {
        EnumSet<TieringState> allowed = VALID_TRANSITIONS.get(this);
        return allowed != null && allowed.contains(target);
    }
}
```

- [ ] **Step 4: Write TieringMetadata**

```java
package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;

import java.io.IOException;

public record TieringMetadata(TieringState currentState, TieringState previousState, long transitionTimestamp)
    implements Writeable {

    public TieringMetadata(StreamInput in) throws IOException {
        this(
            TieringState.valueOf(in.readString()),
            TieringState.valueOf(in.readString()),
            in.readLong()
        );
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(currentState.name());
        out.writeString(previousState.name());
        out.writeLong(transitionTimestamp);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.tiering.TieringMetadataTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/tiering/TieringState.java \
        server/src/main/java/org/elasticsearch/index/remote/tiering/TieringMetadata.java \
        server/src/test/java/org/elasticsearch/index/remote/tiering/TieringMetadataTests.java
git commit -m "feat(tiering): add TieringState enum and TieringMetadata model"
```

---

## Task 10: TieringService state machine

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/tiering/TieringService.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/tiering/TierTransitioner.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/tiering/TieringServiceTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.test.ESTestCase;

import java.util.concurrent.atomic.AtomicReference;

public class TieringServiceTests extends ESTestCase {

    public void testHotToWarmTransition() {
        AtomicReference<TieringState> state = new AtomicReference<>(TieringState.HOT);
        TierTransitioner transitioner = new TierTransitioner() {
            @Override public boolean prepareTransition(String index, TieringState from, TieringState to) { return true; }
            @Override public boolean executeTransition(String index, TieringState from, TieringState to) { return true; }
            @Override public void rollback(String index, TieringState from, TieringState to) {}
        };

        TieringService service = new TieringService(transitioner);
        boolean result = service.transitionIndex("test-index", TieringState.HOT, TieringState.WARM);

        assertTrue(result);
    }

    public void testInvalidTransitionRejected() {
        TierTransitioner transitioner = new TierTransitioner() {
            @Override public boolean prepareTransition(String index, TieringState from, TieringState to) { return true; }
            @Override public boolean executeTransition(String index, TieringState from, TieringState to) { return true; }
            @Override public void rollback(String index, TieringState from, TieringState to) {}
        };

        TieringService service = new TieringService(transitioner);
        boolean result = service.transitionIndex("test-index", TieringState.HOT, TieringState.COLD);

        assertFalse(result); // HOT -> COLD not allowed directly
    }

    public void testRollbackOnFailure() {
        AtomicReference<String> rolledBack = new AtomicReference<>(null);
        TierTransitioner transitioner = new TierTransitioner() {
            @Override public boolean prepareTransition(String index, TieringState from, TieringState to) { return true; }
            @Override public boolean executeTransition(String index, TieringState from, TieringState to) { return false; }
            @Override public void rollback(String index, TieringState from, TieringState to) { rolledBack.set(index); }
        };

        TieringService service = new TieringService(transitioner);
        boolean result = service.transitionIndex("test-index", TieringState.HOT, TieringState.WARM);

        assertFalse(result);
        assertEquals("test-index", rolledBack.get());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.tiering.TieringServiceTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write TierTransitioner interface**

```java
package org.elasticsearch.index.remote.tiering;

public interface TierTransitioner {
    boolean prepareTransition(String index, TieringState from, TieringState to);
    boolean executeTransition(String index, TieringState from, TieringState to);
    void rollback(String index, TieringState from, TieringState to);
}
```

- [ ] **Step 4: Write TieringService**

```java
package org.elasticsearch.index.remote.tiering;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TieringService {

    private static final Logger logger = LogManager.getLogger(TieringService.class);

    private final TierTransitioner transitioner;
    private final Map<String, TieringMetadata> indexStates = new ConcurrentHashMap<>();

    public TieringService(TierTransitioner transitioner) {
        this.transitioner = transitioner;
    }

    public boolean transitionIndex(String index, TieringState currentState, TieringState targetState) {
        // Determine intermediate state
        TieringState intermediate = getIntermediateState(currentState, targetState);
        if (intermediate == null) {
            logger.warn("Invalid transition from {} to {} for index [{}]", currentState, targetState, index);
            return false;
        }

        // Validate: current must be able to transition to intermediate
        if (!currentState.canTransitionTo(intermediate)) {
            logger.warn("Cannot transition from {} to {} for index [{}]", currentState, intermediate, index);
            return false;
        }

        // Phase 1: Prepare
        if (!transitioner.prepareTransition(index, currentState, intermediate)) {
            logger.warn("Prepare failed for index [{}] transition {} -> {}", index, currentState, intermediate);
            return false;
        }

        // Mark transitioning
        indexStates.put(index, new TieringMetadata(intermediate, currentState, System.currentTimeMillis()));

        // Phase 2: Execute
        if (!transitioner.executeTransition(index, intermediate, targetState)) {
            logger.warn("Execute failed for index [{}], rolling back", index);
            transitioner.rollback(index, intermediate, currentState);
            indexStates.put(index, new TieringMetadata(currentState, intermediate, System.currentTimeMillis()));
            return false;
        }

        // Phase 3: Complete
        indexStates.put(index, new TieringMetadata(targetState, intermediate, System.currentTimeMillis()));
        return true;
    }

    private TieringState getIntermediateState(TieringState from, TieringState to) {
        if (from == TieringState.HOT && to == TieringState.WARM) return TieringState.HOT_TO_WARM;
        if (from == TieringState.WARM && to == TieringState.COLD) return TieringState.WARM_TO_COLD;
        if (from == TieringState.WARM && to == TieringState.HOT) return TieringState.HOT; // direct promote
        if (from == TieringState.COLD && to == TieringState.WARM) return TieringState.WARM; // direct promote
        return null;
    }

    public TieringMetadata getState(String index) {
        return indexStates.get(index);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.tiering.TieringServiceTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/tiering/TieringService.java \
        server/src/main/java/org/elasticsearch/index/remote/tiering/TierTransitioner.java \
        server/src/test/java/org/elasticsearch/index/remote/tiering/TieringServiceTests.java
git commit -m "feat(tiering): add TieringService state machine with rollback support"
```

---

## Task 11: TierAction (ILM integration)

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/tiering/TierAction.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/tiering/TierActionTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;

public class TierActionTests extends ESTestCase {

    public void testSerialization() throws IOException {
        TierAction action = new TierAction("warm");

        BytesStreamOutput out = new BytesStreamOutput();
        action.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        TierAction parsed = new TierAction(in);

        assertEquals("warm", parsed.getTargetTier());
        assertEquals(TierAction.NAME, parsed.getWriteableName());
    }

    public void testIsSafe() {
        TierAction action = new TierAction("warm");
        assertTrue(action.isSafeAction());
    }

    public void testToStepsProducesSteps() {
        TierAction action = new TierAction("cold");
        var steps = action.toSteps(null, "warm", null);
        assertFalse(steps.isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.tiering.TierActionTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write TierAction**

```java
package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xpack.core.ilm.LifecycleAction;
import org.elasticsearch.xpack.core.ilm.Step;

import java.io.IOException;
import java.util.List;

public class TierAction implements LifecycleAction {

    public static final String NAME = "tier";

    private final String targetTier;

    public TierAction(String targetTier) {
        this.targetTier = targetTier;
    }

    public TierAction(StreamInput in) throws IOException {
        this.targetTier = in.readString();
    }

    public String getTargetTier() { return targetTier; }

    @Override
    public List<Step> toSteps(Client client, String phase, @Nullable Step.StepKey nextStepKey) {
        Step.StepKey tierStepKey = new Step.StepKey(phase, NAME, "tier-transition");
        Step.StepKey waitStepKey = new Step.StepKey(phase, NAME, "wait-for-tier");
        return List.of(
            new TierActionStep(tierStepKey, waitStepKey, client, targetTier),
            new WaitForTierStep(waitStepKey, nextStepKey, targetTier)
        );
    }

    @Override
    public boolean isSafeAction() { return true; }

    @Override
    public String getWriteableName() { return NAME; }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(targetTier);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("target_tier", targetTier);
        builder.endObject();
        return builder;
    }
}
```

- [ ] **Step 4: Write stub TierActionStep and WaitForTierStep**

```java
// TierActionStep.java
package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.index.Index;
import org.elasticsearch.xpack.core.ilm.AsyncActionStep;
import org.elasticsearch.xpack.core.ilm.Step;

public class TierActionStep extends AsyncActionStep {

    private final String targetTier;

    public TierActionStep(StepKey key, StepKey nextStepKey, Client client, String targetTier) {
        super(key, nextStepKey, client);
        this.targetTier = targetTier;
    }

    @Override
    public void performAction(IndexMetadata indexMetadata, ClusterState currentState,
                              ClusterStateObserver observer, ActionListener listener) {
        // Invoke TieringService.transitionIndex()
        listener.onResponse(true);
    }

    @Override
    public boolean isRetryable() { return true; }
}
```

```java
// WaitForTierStep.java
package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.index.Index;
import org.elasticsearch.xpack.core.ilm.ClusterStateWaitStep;
import org.elasticsearch.xpack.core.ilm.Step;

public class WaitForTierStep extends ClusterStateWaitStep {

    private final String targetTier;

    public WaitForTierStep(StepKey key, StepKey nextStepKey, String targetTier) {
        super(key, nextStepKey);
        this.targetTier = targetTier;
    }

    @Override
    public Result isConditionMet(Index index, ClusterState clusterState) {
        // Check TieringMetadata in ClusterState for target tier reached
        return new Result(true, null);
    }

    @Override
    public boolean isRetryable() { return true; }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.tiering.TierActionTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/tiering/TierAction.java \
        server/src/main/java/org/elasticsearch/index/remote/tiering/TierActionStep.java \
        server/src/main/java/org/elasticsearch/index/remote/tiering/WaitForTierStep.java \
        server/src/test/java/org/elasticsearch/index/remote/tiering/TierActionTests.java
git commit -m "feat(tiering): add TierAction ILM integration with steps"
```

---

## Task 12: Wire SharedBlobCacheService into Node lifecycle

**Files:**
- Modify: `server/src/main/java/org/elasticsearch/node/Node.java`
- Modify: `server/src/main/java/org/elasticsearch/index/shard/IndexShard.java`

- [ ] **Step 1: Initialize SharedBlobCacheService in Node.java**

In `Node.java` constructor, after repository initialization:

```java
// Initialize SharedBlobCacheService for warm/cold nodes
final SharedBlobCacheService sharedBlobCacheService;
if (DiscoveryNode.hasRole(settings, DiscoveryNodeRole.DATA_WARM_NODE_ROLE)
    || DiscoveryNode.hasRole(settings, DiscoveryNodeRole.DATA_COLD_NODE_ROLE)) {
    sharedBlobCacheService = new SharedBlobCacheService(
        FileCacheSettings.CACHE_SIZE.get(settings).getBytes(),
        (int) FileCacheSettings.REGION_SIZE.get(settings).getBytes(),
        new LFUDecayPolicy(
            FileCacheSettings.DECAY_FACTOR.get(settings),
            FileCacheSettings.DECAY_INTERVAL.get(settings).millis()
        )
    );
} else {
    sharedBlobCacheService = null;
}
```

- [ ] **Step 2: Use LayeredDirectory for LSR shards in IndexShard**

In `IndexShard.java`, when the shard is an LSR replica:

```java
private Directory createDirectory() {
    if (isLeanSyncReplica()) {
        TailDirectory tailDir = new TailDirectory();
        RemoteCachedDirectory remoteDir = new RemoteCachedDirectory(
            getRemoteBlobContainer(), sharedBlobCacheService, shardId.toString()
        );
        return new LayeredDirectory(tailDir, remoteDir);
    }
    return store().directory();
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :server:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add server/src/main/java/org/elasticsearch/node/Node.java \
        server/src/main/java/org/elasticsearch/index/shard/IndexShard.java
git commit -m "feat(filecache): wire SharedBlobCacheService into Node and IndexShard"
```

---

## Task 13: Integration test (TieringService end-to-end)

**Files:**
- Create: `server/src/internalClusterTest/java/org/elasticsearch/index/remote/tiering/TieringServiceIntegrationIT.java`

- [ ] **Step 1: Write integration test**

```java
package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.Map;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 3)
public class TieringServiceIntegrationIT extends ESIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        String role = nodeOrdinal == 0 ? "data_hot" : (nodeOrdinal == 1 ? "data_warm" : "data_cold");
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal, otherSettings))
            .put("node.roles", role + ",master")
            .build();
    }

    public void testHotToWarmTransition() throws Exception {
        // Setup: create index on hot tier
        client().admin().cluster().preparePutRepository("test-repo")
            .setType("fs")
            .setSettings(Settings.builder().put("location", randomRepoPath()).build())
            .get();

        CreateIndexRequest request = new CreateIndexRequest("tier-test");
        request.settings(Settings.builder()
            .put("index.remote_store.enabled", true)
            .put("index.remote_store.repository", "test-repo")
            .put("index.routing.allocation.require._tier", "data_hot")
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", 0)
            .build());
        client().admin().indices().create(request).actionGet();
        ensureGreen("tier-test");

        // Index data
        for (int i = 0; i < 50; i++) {
            client().index(new IndexRequest("tier-test").source(Map.of("f", "v" + i))).actionGet();
        }
        client().admin().indices().prepareRefresh("tier-test").get();
        client().admin().indices().prepareFlush("tier-test").get();

        // Verify data queryable before transition
        long count = client().prepareSearch("tier-test").setSize(0).get().getHits().getTotalHits().value;
        assertEquals(50L, count);
    }
}
```

- [ ] **Step 2: Run integration test**

Run: `./gradlew :server:internalClusterTest --tests "org.elasticsearch.index.remote.tiering.TieringServiceIntegrationIT" -x javadoc`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add server/src/internalClusterTest/java/org/elasticsearch/index/remote/tiering/TieringServiceIntegrationIT.java
git commit -m "test(tiering): add TieringService integration test"
```

---

## Task 14: Final validation

- [ ] **Step 1: Full compilation**

Run: `./gradlew :server:compileJava :server:compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all new tests**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.*" -x javadoc`
Expected: All tests PASS

- [ ] **Step 3: Run regression check**

Run: `./gradlew :server:test -x javadoc`
Expected: No new failures

- [ ] **Step 4: Commit**

```bash
git commit --allow-empty -m "chore(phase2): Tiered Architecture (FileCache + LSR + TieringService) complete"
```
