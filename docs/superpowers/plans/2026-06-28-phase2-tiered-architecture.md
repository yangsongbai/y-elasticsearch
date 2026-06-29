# Phase 2: Tiered Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce Warm/Cold tiers with SharedBlobCache, Lean Sync Replica (LSR) to reduce Hot storage cost by 85%, and TieringService state machine for automated Hot->Warm->Cold index lifecycle transitions.

**Architecture:** Warm/Cold nodes read segments directly from Remote Store via a SharedBlobCache (16MB region, LFU+Decay eviction). LSR on Hot nodes stores only uncommitted tail data locally, using LayeredDirectory to transparently merge tail + Remote. TieringService manages transitions as a state machine, triggered by TieringPolicyService (age-based index settings evaluation).

**Tech Stack:** Java 11 (source/target compatibility), Elasticsearch 7.17.4, Lucene 8.11.1, Lucene Directory abstraction, ClusterStateListener + ThreadPool scheduler, ClusterState metadata, Mockito 4.4.0.

**Compatibility note:** ES 7.17.4 targets Java 11. Do NOT use:
- Java records (use classes with constructors/getters)
- Sealed classes or pattern matching
- Switch expressions (`case "x" ->`)
- `List.of()`, `Map.of()`, `Set.of()` (use `Arrays.asList()`, `Collections.unmodifiableMap()`, `Collections.singleton()`)
- `org.elasticsearch.common.xcontent.*` (use `org.elasticsearch.xcontent.*`)

`ByteBuffersDirectory` is available in Lucene 8.11.1. `RAMDirectory` is deprecated — use `ByteBuffersDirectory` instead.

**Prerequisite:** Phase 1 (Remote Store Write-Back) must be complete and GA. Branch: `feature-7.17.4-20260628`, commit `f05e26e`.

---

## File Structure

### New files to create:

| File | Responsibility |
|------|---------------|
| `server/.../index/remote/cache/FileCacheSettings.java` | `node.filecache.*` settings definitions |
| `server/.../index/remote/cache/CacheRegion.java` | 16MB fixed-size region unit with access counting |
| `server/.../index/remote/cache/RegionSparseFileTracker.java` | Byte-level tracking within a region (TreeMap-based) |
| `server/.../index/remote/cache/LFUDecayPolicy.java` | LFU with time-decay eviction policy |
| `server/.../index/remote/cache/SharedBlobCacheService.java` | Region-based file cache over SSD, orchestrates reads |
| `server/.../index/remote/directory/RemoteCachedDirectory.java` | Read-only Directory that reads from SharedBlobCache |
| `server/.../index/remote/directory/LayeredIndexInput.java` | IndexInput that routes reads through cache |
| `server/.../index/remote/directory/LayeredDirectory.java` | Transparent merge of tail (local) + remote (cached) Directory |
| `server/.../index/remote/replica/TailDirectory.java` | In-memory directory for uncommitted tail segments |
| `server/.../index/remote/replica/LUSBroadcastService.java` | Broadcasts LastUploadedSeqNo from Primary to replicas |
| `server/.../index/remote/replica/LeanSyncReplicaEngine.java` | Engine variant that only stores tail segments |
| `server/.../index/remote/tiering/TieringState.java` | Enum with valid transition table |
| `server/.../index/remote/tiering/TieringMetadata.java` | Serializable tier state model (Writeable) |
| `server/.../index/remote/tiering/TierTransitioner.java` | Interface for transition execution |
| `server/.../index/remote/tiering/TieringService.java` | State machine orchestrator |
| `server/.../index/remote/tiering/TieringPolicySettings.java` | Settings: index.tiering.warm_after, cold_after, delete_after |
| `server/.../index/remote/tiering/TieringPolicyService.java` | Periodic evaluator that triggers transitions |
| Tests: one test file per production class above |

### Existing files to modify:

| File | Change |
|------|--------|
| `server/.../common/settings/ClusterSettings.java` | Register FileCacheSettings + TieringPolicySettings.EVALUATION_INTERVAL |
| `server/.../common/settings/IndexScopedSettings.java` | Register TieringPolicySettings index-level settings |

### Base path convention:

```
server/src/main/java/org/elasticsearch/index/remote/cache/      <- cache subsystem
server/src/main/java/org/elasticsearch/index/remote/directory/   <- Lucene Directory impls
server/src/main/java/org/elasticsearch/index/remote/replica/     <- LSR components
server/src/main/java/org/elasticsearch/index/remote/tiering/     <- TieringService
server/src/test/java/org/elasticsearch/index/remote/cache/       <- cache tests
server/src/test/java/org/elasticsearch/index/remote/directory/   <- directory tests
server/src/test/java/org/elasticsearch/index/remote/replica/     <- LSR tests
server/src/test/java/org/elasticsearch/index/remote/tiering/     <- tiering tests
server/src/internalClusterTest/java/org/elasticsearch/index/remote/tiering/  <- integration tests
```

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

import java.util.Arrays;
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
        return Arrays.asList(CACHE_SIZE, REGION_SIZE, EVICTION_POLICY, DECAY_INTERVAL, DECAY_FACTOR);
    }
}
```

- [ ] **Step 4: Register in ClusterSettings**

Add to `ClusterSettings.java` in `BUILT_IN_CLUSTER_SETTINGS`:

```java
FileCacheSettings.CACHE_SIZE,
FileCacheSettings.REGION_SIZE,
FileCacheSettings.EVICTION_POLICY,
FileCacheSettings.DECAY_INTERVAL,
FileCacheSettings.DECAY_FACTOR,
```

Import: `import org.elasticsearch.index.remote.cache.FileCacheSettings;`

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

import java.util.Arrays;
import java.util.List;

public class LFUDecayPolicyTests extends ESTestCase {

    public void testEvictLeastFrequent() {
        LFUDecayPolicy policy = new LFUDecayPolicy(0.95, 60_000L);
        CacheRegion r1 = new CacheRegion(0, 16 * 1024 * 1024);
        CacheRegion r2 = new CacheRegion(1, 16 * 1024 * 1024);
        CacheRegion r3 = new CacheRegion(2, 16 * 1024 * 1024);

        for (int i = 0; i < 10; i++) r1.recordAccess();
        r2.recordAccess();
        for (int i = 0; i < 5; i++) r3.recordAccess();

        List<CacheRegion> candidates = Arrays.asList(r1, r2, r3);
        CacheRegion victim = policy.selectVictim(candidates);
        assertEquals(1, victim.getRegionId());
    }

    public void testDecayReducesFrequency() {
        LFUDecayPolicy policy = new LFUDecayPolicy(0.5, 60_000L);
        CacheRegion region = new CacheRegion(0, 16 * 1024 * 1024);
        for (int i = 0; i < 100; i++) region.recordAccess();
        assertEquals(100, region.getAccessCount());

        policy.applyDecay(Arrays.asList(region));
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

    public long getRegionId() { return regionId; }
    public int getRegionSize() { return regionSize; }

    public void recordAccess() { accessCount.incrementAndGet(); }
    public long getAccessCount() { return accessCount.get(); }
    public void setAccessCount(long count) { accessCount.set(count); }

    public boolean isDirty() { return dirty; }
    public void markDirty() { dirty = true; }
    public void markClean() { dirty = false; }

    public void allocate() { buffer = ByteBuffer.allocateDirect(regionSize); }
    public ByteBuffer getBuffer() { return buffer; }
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

    public double getDecayFactor() { return decayFactor; }
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

import java.util.List;

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

        List<RegionSparseFileTracker.Gap> gaps = tracker.getGaps(0, 3000);
        assertEquals(1, gaps.size());
        assertEquals(1000, gaps.get(0).getStart());
        assertEquals(2000, gaps.get(0).getEnd());
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
import java.util.Map;
import java.util.TreeMap;

public class RegionSparseFileTracker {

    public static class Gap {
        private final long start;
        private final long end;

        public Gap(long start, long end) {
            this.start = start;
            this.end = end;
        }

        public long getStart() { return start; }
        public long getEnd() { return end; }
    }

    private final long regionSize;
    private final TreeMap<Long, Long> completedRanges = new TreeMap<>();

    public RegionSparseFileTracker(long regionSize) {
        this.regionSize = regionSize;
    }

    public synchronized void markComplete(long start, long end) {
        Long floorKey = completedRanges.floorKey(start);

        long mergeStart = start;
        long mergeEnd = end;

        if (floorKey != null && completedRanges.get(floorKey) >= start) {
            mergeStart = floorKey;
            mergeEnd = Math.max(mergeEnd, completedRanges.get(floorKey));
            completedRanges.remove(floorKey);
        }

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

        Long floorKey = completedRanges.floorKey(start);
        Long fromKey = (floorKey != null) ? floorKey : start;

        for (Map.Entry<Long, Long> entry : completedRanges.subMap(fromKey, true, end, true).entrySet()) {
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
            64 * 1024 * 1024L, 16 * 1024 * 1024, new LFUDecayPolicy(0.95, 60_000L));

        BlobContainer remote = mock(BlobContainer.class);
        byte[] data = randomByteArrayOfLength(4096);
        when(remote.readBlob("file.cfs", 0, 16 * 1024 * 1024))
            .thenReturn(new ByteArrayInputStream(data));

        ByteBuffer result1 = cache.read("idx/shard/file.cfs", 0, 4096, remote, "file.cfs");
        assertNotNull(result1);

        ByteBuffer result2 = cache.read("idx/shard/file.cfs", 0, 4096, remote, "file.cfs");
        assertNotNull(result2);
        verify(remote, times(1)).readBlob("file.cfs", 0, 16 * 1024 * 1024);
    }

    public void testEvictionWhenFull() throws IOException {
        SharedBlobCacheService cache = new SharedBlobCacheService(
            32 * 1024 * 1024L, 16 * 1024 * 1024, new LFUDecayPolicy(0.95, 60_000L));

        BlobContainer remote = mock(BlobContainer.class);
        when(remote.readBlob(anyString(), anyLong(), anyLong()))
            .thenReturn(new ByteArrayInputStream(new byte[16 * 1024 * 1024]));

        cache.read("file1", 0, 100, remote, "file1");
        cache.read("file2", 0, 100, remote, "file2");
        cache.read("file3", 0, 100, remote, "file3");

        assertEquals(2, cache.getMaxRegionCount());
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

        long regionOffset = (position / regionSize) * (long) regionSize;
        long offsetInRegion = position - regionOffset;

        CacheRegion region = regionMap.get(regionKey);
        if (region != null && tracker.isRangeAvailable(offsetInRegion, offsetInRegion + length)) {
            region.recordAccess();
            ByteBuffer buf = region.getBuffer().duplicate();
            buf.position((int) offsetInRegion);
            buf.limit((int) (offsetInRegion + length));
            return buf.slice();
        }

        if (region == null) {
            ensureCapacity();
            region = new CacheRegion(regionMap.size(), regionSize);
            region.allocate();
            regionMap.put(regionKey, region);
        }

        try (InputStream is = remote.readBlob(blobName, regionOffset, regionSize)) {
            byte[] data = is.readAllBytes();
            region.getBuffer().clear();
            region.getBuffer().put(data, 0, Math.min(data.length, regionSize));
            tracker.markComplete(0, Math.min(data.length, regionSize));
        }

        region.recordAccess();
        ByteBuffer buf = region.getBuffer().duplicate();
        buf.position((int) offsetInRegion);
        buf.limit((int) Math.min(offsetInRegion + length, region.getBuffer().capacity()));
        return buf.slice();
    }

    private void ensureCapacity() {
        if (regionMap.size() >= maxRegions) {
            List<CacheRegion> candidates = new ArrayList<>(regionMap.values());
            CacheRegion victim = evictionPolicy.selectVictim(candidates);
            if (victim != null) {
                String victimKey = null;
                for (Map.Entry<String, CacheRegion> entry : regionMap.entrySet()) {
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

    public int getMaxRegionCount() { return maxRegions; }
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
        dir.registerFileLength("_0.cfs", fileContent.length);

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
import java.util.Collections;
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

    @Override
    public String[] listAll() throws IOException {
        return fileLengths.keySet().toArray(new String[0]);
    }

    @Override
    public void deleteFile(String name) throws IOException { fileLengths.remove(name); }

    @Override
    public IndexOutput createOutput(String name, IOContext context) {
        throw new UnsupportedOperationException("read-only directory");
    }

    @Override
    public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) {
        throw new UnsupportedOperationException("read-only directory");
    }

    @Override public void sync(Collection<String> names) {}
    @Override public void syncMetaData() {}

    @Override
    public void rename(String source, String dest) {
        throw new UnsupportedOperationException("read-only directory");
    }

    @Override
    public Lock obtainLock(String name) {
        return new Lock() {
            @Override public void close() {}
            @Override public void ensureValid() {}
        };
    }

    @Override public void close() {}

    @Override
    public Set<String> getPendingDeletions() {
        return Collections.emptySet();
    }
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

    private LayeredIndexInput(String resourceDescription, SharedBlobCacheService cache,
                              BlobContainer blobContainer, String blobName, String cacheKey,
                              long fileLength, long position) {
        super(resourceDescription);
        this.cache = cache;
        this.blobContainer = blobContainer;
        this.blobName = blobName;
        this.cacheKey = cacheKey;
        this.fileLength = fileLength;
        this.position = position;
    }

    @Override
    public void readBytes(byte[] b, int offset, int len) throws IOException {
        ByteBuffer data = cache.read(cacheKey, position, len, blobContainer, blobName);
        data.get(b, offset, Math.min(len, data.remaining()));
        position += len;
    }

    @Override
    public byte readByte() throws IOException {
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
        return new LayeredIndexInput(sliceDescription, cache, blobContainer, blobName, cacheKey, length, offset);
    }

    @Override
    public IndexInput clone() {
        return new LayeredIndexInput(toString(), cache, blobContainer, blobName, cacheKey, fileLength, position);
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

import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;

import static org.mockito.Mockito.*;

public class LayeredDirectoryTests extends ESTestCase {

    public void testTailFileRoutesToLocal() throws IOException {
        Directory tailDir = new ByteBuffersDirectory();
        RemoteCachedDirectory remoteDir = mock(RemoteCachedDirectory.class);

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
        Directory tailDir = new ByteBuffersDirectory();
        RemoteCachedDirectory remoteDir = mock(RemoteCachedDirectory.class);
        IndexInput mockInput = mock(IndexInput.class);
        when(remoteDir.openInput("_remote.cfs", IOContext.READ)).thenReturn(mockInput);

        LayeredDirectory layered = new LayeredDirectory(tailDir, remoteDir);

        layered.openInput("_remote.cfs", IOContext.READ);
        verify(remoteDir).openInput("_remote.cfs", IOContext.READ);
    }

    public void testListAllMergesBothSources() throws IOException {
        Directory tailDir = new ByteBuffersDirectory();
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
            if (tailFiles.contains(f)) {
                all.add(f);
            }
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

    @Override
    public void deleteFile(String name) throws IOException {
        if (tailFiles.contains(name)) {
            tailDirectory.deleteFile(name);
            tailFiles.remove(name);
        } else {
            remoteDirectory.deleteFile(name);
        }
    }

    @Override
    public IndexOutput createOutput(String name, IOContext context) throws IOException {
        tailFiles.add(name);
        return tailDirectory.createOutput(name, context);
    }

    @Override
    public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) throws IOException {
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
        service.broadcastLUS(30L);
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

public class LUSBroadcastService {

    private final AtomicLong currentLUS = new AtomicLong(-1);
    private final List<LongConsumer> listeners = new CopyOnWriteArrayList<>();

    public void broadcastLUS(long seqNo) {
        long current = currentLUS.get();
        if (seqNo <= current) {
            return;
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

        engine.recordSegmentSeqNo("_0.cfs", 10L);
        engine.recordSegmentSeqNo("_1.cfs", 20L);
        engine.recordSegmentSeqNo("_2.cfs", 30L);

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
        int count = 0;
        for (Long seqNo : segmentSeqNos.values()) {
            if (seqNo > lastUploadedSeqNo) {
                count++;
            }
        }
        return count;
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
        assertTrue(TieringState.HOT_TO_WARM.canTransitionTo(TieringState.HOT));
        assertTrue(TieringState.WARM.canTransitionTo(TieringState.WARM_TO_COLD));
        assertTrue(TieringState.WARM.canTransitionTo(TieringState.HOT));
        assertFalse(TieringState.HOT.canTransitionTo(TieringState.COLD));
        assertFalse(TieringState.COLD.canTransitionTo(TieringState.HOT_TO_WARM));
    }

    public void testSerializationRoundTrip() throws IOException {
        TieringMetadata original = new TieringMetadata(TieringState.WARM, TieringState.HOT, 12345L);

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        TieringMetadata parsed = new TieringMetadata(in);

        assertEquals(original.getCurrentState(), parsed.getCurrentState());
        assertEquals(original.getPreviousState(), parsed.getPreviousState());
        assertEquals(original.getTransitionTimestamp(), parsed.getTransitionTimestamp());
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public enum TieringState {
    CREATING,
    HOT,
    HOT_TO_WARM,
    WARM,
    WARM_TO_COLD,
    COLD,
    ARCHIVED;

    private static final Map<TieringState, Set<TieringState>> VALID_TRANSITIONS;

    static {
        Map<TieringState, Set<TieringState>> map = new HashMap<>();
        map.put(CREATING, EnumSet.of(HOT));
        map.put(HOT, EnumSet.of(HOT_TO_WARM));
        map.put(HOT_TO_WARM, EnumSet.of(WARM, HOT));
        map.put(WARM, EnumSet.of(WARM_TO_COLD, HOT));
        map.put(WARM_TO_COLD, EnumSet.of(COLD, WARM));
        map.put(COLD, EnumSet.of(ARCHIVED, WARM));
        map.put(ARCHIVED, Collections.emptySet());
        VALID_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    public boolean canTransitionTo(TieringState target) {
        Set<TieringState> allowed = VALID_TRANSITIONS.get(this);
        return allowed != null && allowed.contains(target);
    }

    public boolean isTransitioning() {
        return this == HOT_TO_WARM || this == WARM_TO_COLD;
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

public class TieringMetadata implements Writeable {

    private final TieringState currentState;
    private final TieringState previousState;
    private final long transitionTimestamp;

    public TieringMetadata(TieringState currentState, TieringState previousState, long transitionTimestamp) {
        this.currentState = currentState;
        this.previousState = previousState;
        this.transitionTimestamp = transitionTimestamp;
    }

    public TieringMetadata(StreamInput in) throws IOException {
        this.currentState = TieringState.valueOf(in.readString());
        this.previousState = TieringState.valueOf(in.readString());
        this.transitionTimestamp = in.readLong();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(currentState.name());
        out.writeString(previousState.name());
        out.writeLong(transitionTimestamp);
    }

    public TieringState getCurrentState() { return currentState; }
    public TieringState getPreviousState() { return previousState; }
    public long getTransitionTimestamp() { return transitionTimestamp; }
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
- Create: `server/src/main/java/org/elasticsearch/index/remote/tiering/TierTransitioner.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/tiering/TieringService.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/tiering/TieringServiceTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.test.ESTestCase;

import java.util.concurrent.atomic.AtomicReference;

public class TieringServiceTests extends ESTestCase {

    public void testHotToWarmTransition() {
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

        assertFalse(result);
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
        TieringState intermediate = getIntermediateState(currentState, targetState);
        if (intermediate == null) {
            logger.warn("Invalid transition from {} to {} for index [{}]", currentState, targetState, index);
            return false;
        }

        if (!currentState.canTransitionTo(intermediate)) {
            logger.warn("Cannot transition from {} to {} for index [{}]", currentState, intermediate, index);
            return false;
        }

        if (!transitioner.prepareTransition(index, currentState, intermediate)) {
            logger.warn("Prepare failed for index [{}] transition {} -> {}", index, currentState, intermediate);
            return false;
        }

        indexStates.put(index, new TieringMetadata(intermediate, currentState, System.currentTimeMillis()));

        if (!transitioner.executeTransition(index, intermediate, targetState)) {
            logger.warn("Execute failed for index [{}], rolling back", index);
            transitioner.rollback(index, intermediate, currentState);
            indexStates.put(index, new TieringMetadata(currentState, intermediate, System.currentTimeMillis()));
            return false;
        }

        indexStates.put(index, new TieringMetadata(targetState, intermediate, System.currentTimeMillis()));
        return true;
    }

    private TieringState getIntermediateState(TieringState from, TieringState to) {
        if (from == TieringState.HOT && to == TieringState.WARM) return TieringState.HOT_TO_WARM;
        if (from == TieringState.WARM && to == TieringState.COLD) return TieringState.WARM_TO_COLD;
        if (from == TieringState.WARM && to == TieringState.HOT) return TieringState.HOT;
        if (from == TieringState.COLD && to == TieringState.WARM) return TieringState.WARM;
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
git add server/src/main/java/org/elasticsearch/index/remote/tiering/TierTransitioner.java \
        server/src/main/java/org/elasticsearch/index/remote/tiering/TieringService.java \
        server/src/test/java/org/elasticsearch/index/remote/tiering/TieringServiceTests.java
git commit -m "feat(tiering): add TieringService state machine with rollback support"
```

---

## Task 11: TieringPolicyService (standalone tiering trigger, no x-pack)

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/tiering/TieringPolicySettings.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/tiering/TieringPolicyService.java`
- Modify: `server/src/main/java/org/elasticsearch/common/settings/IndexScopedSettings.java`
- Modify: `server/src/main/java/org/elasticsearch/common/settings/ClusterSettings.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/tiering/TieringPolicyServiceTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import static org.mockito.Mockito.mock;

public class TieringPolicyServiceTests extends ESTestCase {

    private ThreadPool threadPool;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool("test");
    }

    @Override
    public void tearDown() throws Exception {
        threadPool.shutdownNow();
        super.tearDown();
    }

    public void testSettingsParsed() {
        Settings settings = Settings.builder()
            .put(TieringPolicySettings.WARM_AFTER.getKey(), "7d")
            .put(TieringPolicySettings.COLD_AFTER.getKey(), "30d")
            .put(TieringPolicySettings.DELETE_AFTER.getKey(), "90d")
            .build();
        assertEquals(7L * 24 * 60 * 60 * 1000,
            TieringPolicySettings.WARM_AFTER.get(settings).millis());
        assertEquals(30L * 24 * 60 * 60 * 1000,
            TieringPolicySettings.COLD_AFTER.get(settings).millis());
    }

    public void testEvaluatesIndexAge() {
        long nowMillis = System.currentTimeMillis();
        long eightDaysAgo = nowMillis - (8L * 24 * 60 * 60 * 1000);

        Settings indexSettings = Settings.builder()
            .put(IndexMetadata.SETTING_INDEX_UUID, "test-uuid")
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetadata.SETTING_VERSION_CREATED, org.elasticsearch.Version.CURRENT)
            .put(IndexMetadata.SETTING_CREATION_DATE, eightDaysAgo)
            .put(TieringPolicySettings.WARM_AFTER.getKey(), "7d")
            .build();

        IndexMetadata indexMetadata = IndexMetadata.builder("logs-2024")
            .settings(indexSettings)
            .build();

        TieringService mockTieringService = mock(TieringService.class);
        TieringPolicyService policyService = new TieringPolicyService(
            threadPool, mock(ClusterService.class), mockTieringService);

        boolean shouldTransition = policyService.shouldTransition(indexMetadata, TieringState.HOT, "warm");
        assertTrue(shouldTransition);
    }

    public void testDoesNotTransitionYoungIndex() {
        long nowMillis = System.currentTimeMillis();
        long twoDaysAgo = nowMillis - (2L * 24 * 60 * 60 * 1000);

        Settings indexSettings = Settings.builder()
            .put(IndexMetadata.SETTING_INDEX_UUID, "test-uuid")
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetadata.SETTING_VERSION_CREATED, org.elasticsearch.Version.CURRENT)
            .put(IndexMetadata.SETTING_CREATION_DATE, twoDaysAgo)
            .put(TieringPolicySettings.WARM_AFTER.getKey(), "7d")
            .build();

        IndexMetadata indexMetadata = IndexMetadata.builder("logs-2024")
            .settings(indexSettings)
            .build();

        TieringService mockTieringService = mock(TieringService.class);
        TieringPolicyService policyService = new TieringPolicyService(
            threadPool, mock(ClusterService.class), mockTieringService);

        boolean shouldTransition = policyService.shouldTransition(indexMetadata, TieringState.HOT, "warm");
        assertFalse(shouldTransition);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.tiering.TieringPolicyServiceTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write TieringPolicySettings**

```java
package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.core.TimeValue;

import java.util.Arrays;
import java.util.List;

public final class TieringPolicySettings {

    public static final Setting<TimeValue> WARM_AFTER = Setting.timeSetting(
        "index.tiering.warm_after",
        TimeValue.timeValueMillis(-1),
        TimeValue.timeValueMillis(-1),
        Setting.Property.IndexScope,
        Setting.Property.Dynamic
    );

    public static final Setting<TimeValue> COLD_AFTER = Setting.timeSetting(
        "index.tiering.cold_after",
        TimeValue.timeValueMillis(-1),
        TimeValue.timeValueMillis(-1),
        Setting.Property.IndexScope,
        Setting.Property.Dynamic
    );

    public static final Setting<TimeValue> DELETE_AFTER = Setting.timeSetting(
        "index.tiering.delete_after",
        TimeValue.timeValueMillis(-1),
        TimeValue.timeValueMillis(-1),
        Setting.Property.IndexScope,
        Setting.Property.Dynamic
    );

    public static final Setting<TimeValue> EVALUATION_INTERVAL = Setting.timeSetting(
        "cluster.tiering.evaluation_interval",
        TimeValue.timeValueMinutes(5),
        TimeValue.timeValueMinutes(1),
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    private TieringPolicySettings() {}

    public static List<Setting<?>> getIndexSettings() {
        return Arrays.asList(WARM_AFTER, COLD_AFTER, DELETE_AFTER);
    }

    public static List<Setting<?>> getClusterSettings() {
        return Arrays.asList(EVALUATION_INTERVAL);
    }
}
```

- [ ] **Step 4: Write TieringPolicyService**

```java
package org.elasticsearch.index.remote.tiering;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.threadpool.ThreadPool;

public class TieringPolicyService {

    private static final Logger logger = LogManager.getLogger(TieringPolicyService.class);

    private final ThreadPool threadPool;
    private final ClusterService clusterService;
    private final TieringService tieringService;

    public TieringPolicyService(ThreadPool threadPool, ClusterService clusterService,
                                TieringService tieringService) {
        this.threadPool = threadPool;
        this.clusterService = clusterService;
        this.tieringService = tieringService;
    }

    public boolean shouldTransition(IndexMetadata indexMetadata, TieringState currentState, String targetTier) {
        long creationDate = indexMetadata.getCreationDate();
        if (creationDate <= 0) {
            return false;
        }
        long ageMillis = System.currentTimeMillis() - creationDate;

        TimeValue threshold;
        if ("warm".equals(targetTier)) {
            threshold = TieringPolicySettings.WARM_AFTER.get(indexMetadata.getSettings());
        } else if ("cold".equals(targetTier)) {
            threshold = TieringPolicySettings.COLD_AFTER.get(indexMetadata.getSettings());
        } else {
            return false;
        }

        if (threshold.millis() <= 0) {
            return false;
        }

        return ageMillis >= threshold.millis();
    }
}
```

- [ ] **Step 5: Register settings**

In `IndexScopedSettings.java`, add to `BUILT_IN_INDEX_SETTINGS`:
```java
TieringPolicySettings.WARM_AFTER,
TieringPolicySettings.COLD_AFTER,
TieringPolicySettings.DELETE_AFTER,
```

Import: `import org.elasticsearch.index.remote.tiering.TieringPolicySettings;`

In `ClusterSettings.java`, add to `BUILT_IN_CLUSTER_SETTINGS`:
```java
TieringPolicySettings.EVALUATION_INTERVAL,
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.tiering.TieringPolicyServiceTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/tiering/TieringPolicySettings.java \
        server/src/main/java/org/elasticsearch/index/remote/tiering/TieringPolicyService.java \
        server/src/test/java/org/elasticsearch/index/remote/tiering/TieringPolicyServiceTests.java \
        server/src/main/java/org/elasticsearch/common/settings/IndexScopedSettings.java \
        server/src/main/java/org/elasticsearch/common/settings/ClusterSettings.java
git commit -m "feat(tiering): add TieringPolicyService for age-based tier transitions (no x-pack)"
```

---

## Task 12: Integration test (TieringService end-to-end)

**Files:**
- Create: `server/src/internalClusterTest/java/org/elasticsearch/index/remote/tiering/TieringServiceIntegrationIT.java`

- [ ] **Step 1: Write integration test**

```java
package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.HashMap;
import java.util.Map;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 2)
public class TieringServiceIntegrationIT extends ESIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal, otherSettings))
            .build();
    }

    public void testTieringPolicySettingsRegistered() throws Exception {
        CreateIndexRequest request = new CreateIndexRequest("tier-test");
        request.settings(Settings.builder()
            .put("index.tiering.warm_after", "7d")
            .put("index.tiering.cold_after", "30d")
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", 0)
            .build());
        client().admin().indices().create(request).actionGet();
        ensureGreen("tier-test");

        Settings indexSettings = client().admin().indices().prepareGetSettings("tier-test")
            .get().getIndexToSettings().get("tier-test");
        assertEquals("7d", indexSettings.get("index.tiering.warm_after"));
        assertEquals("30d", indexSettings.get("index.tiering.cold_after"));
    }

    public void testIndexDataWithTieringSettingsQueryable() throws Exception {
        CreateIndexRequest request = new CreateIndexRequest("tier-data-test");
        request.settings(Settings.builder()
            .put("index.tiering.warm_after", "7d")
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", 0)
            .build());
        client().admin().indices().create(request).actionGet();
        ensureGreen("tier-data-test");

        for (int i = 0; i < 50; i++) {
            Map<String, Object> source = new HashMap<>();
            source.put("field", "value-" + i);
            client().index(new IndexRequest("tier-data-test").source(source)).actionGet();
        }
        client().admin().indices().prepareRefresh("tier-data-test").get();

        long count = client().prepareSearch("tier-data-test").setSize(0).get().getHits().getTotalHits().value;
        assertEquals(50L, count);
    }
}
```

- [ ] **Step 2: Run integration test**

Run: `./gradlew :server:internalClusterTest --tests "org.elasticsearch.index.remote.tiering.TieringServiceIntegrationIT" -x javadoc`
Expected: All 2 tests PASS

- [ ] **Step 3: Commit**

```bash
git add server/src/internalClusterTest/java/org/elasticsearch/index/remote/tiering/TieringServiceIntegrationIT.java
git commit -m "test(tiering): add TieringService integration tests"
```

---

## Task 13: Final validation

- [ ] **Step 1: Full compilation**

Run: `./gradlew :server:compileJava :server:compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all Phase 2 tests**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.cache.*" --tests "org.elasticsearch.index.remote.directory.*" --tests "org.elasticsearch.index.remote.replica.*" --tests "org.elasticsearch.index.remote.tiering.*" -x javadoc`
Expected: All tests PASS

- [ ] **Step 3: Run full server test suite for regression check**

Run: `./gradlew :server:test -x javadoc`
Expected: BUILD SUCCESSFUL, no new failures

- [ ] **Step 4: Commit final state**

```bash
git commit --allow-empty -m "chore(phase2): Tiered Architecture (FileCache + LSR + TieringService) complete"
```
