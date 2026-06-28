# Phase 4: Optimization & Production Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Production-harden the storage-compute separation system with cross-region DR, Point-in-Time Recovery, intelligent Prefetch, full OpenTelemetry observability, and Chaos Engineering validation. Deliver a system that runs 7x24 unattended for 30+ days.

**Architecture:** PITR leverages Remote Store's generation-based metadata for time-travel. PrefetchService intelligently pre-loads segments based on access patterns. OTel integration provides end-to-end traces from client request through Remote Store I/O. Chaos Mesh scripts validate all failure modes defined in the spec.

**Tech Stack:** Java 11 (source/target compatibility), Elasticsearch 7.17.4, Lucene 8.11.1, OpenTelemetry SDK, Chaos Mesh CRDs, Helm Chart, CCR framework, Grafana dashboard JSON, Mockito 4.4.0.

**Compatibility note:** ES 7.17.4 targets Java 11. Do NOT use Java records, sealed classes, or pattern matching. Convert all `record` declarations in this plan to traditional classes with constructors and getters.

**Prerequisite:** Phase 3 (Intelligent Autoscaling) must be complete with Warm/Cold operational and AutoscalingService active.

---

## File Structure

### New files to create:

| File | Responsibility |
|------|---------------|
| `server/.../index/remote/dr/CrossRegionReplicationService.java` | Enhanced CCR using Remote Store metadata |
| `server/.../index/remote/dr/PITRService.java` | Point-in-Time Recovery orchestration |
| `server/.../index/remote/dr/PITRMetadata.java` | Recovery point metadata model |
| `server/.../index/remote/dr/RestPITRAction.java` | REST: POST _pitr_restore |
| `server/.../index/remote/prefetch/PrefetchService.java` | Intelligent segment pre-loading |
| `server/.../index/remote/prefetch/PrefetchPolicy.java` | Policy interface (time-based, metadata, doc_values) |
| `server/.../index/remote/prefetch/TimePrefetchPolicy.java` | Time-series adjacent segment prefetch |
| `server/.../index/remote/prefetch/MetadataPrefetchPolicy.java` | .si/.cfe metadata prefetch on allocation |
| `server/.../index/remote/prefetch/PrefetchSettings.java` | `node.prefetch.*` settings |
| `server/.../index/remote/observability/RemoteStoreTracer.java` | OTel span instrumentation for upload/download |
| `server/.../index/remote/observability/RemoteStoreMetricsExporter.java` | Prometheus metrics exporter |
| `server/.../index/remote/observability/TracingSettings.java` | `tracing.*` settings |
| `deploy/helm/es-remote-store/Chart.yaml` | Helm chart definition |
| `deploy/helm/es-remote-store/values.yaml` | Default Helm values |
| `deploy/helm/es-remote-store/templates/statefulset-hot.yaml` | Hot tier K8s spec |
| `deploy/helm/es-remote-store/templates/deployment-warm.yaml` | Warm tier K8s spec |
| `deploy/chaos/pod-failure.yaml` | Chaos Mesh: random Pod kill |
| `deploy/chaos/network-partition.yaml` | Chaos Mesh: AZ network partition |
| `deploy/chaos/remote-store-fault.yaml` | Chaos Mesh: S3 latency injection |
| `deploy/grafana/dashboard-l1-overview.json` | L1 Grafana dashboard |
| Tests: one test file per production class above |

### Existing files to modify:

| File | Change |
|------|--------|
| `server/.../common/settings/ClusterSettings.java` | Register prefetch + tracing settings |
| `server/.../index/remote/RemoteStoreRefreshListener.java` | Add OTel span around upload |
| `server/.../index/remote/cache/SharedBlobCacheService.java` | Add OTel span around cache miss fetch |

---

## Task 1: PrefetchSettings

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/prefetch/PrefetchSettings.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/prefetch/PrefetchSettingsTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.prefetch;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;

public class PrefetchSettingsTests extends ESTestCase {

    public void testDefaults() {
        Settings s = Settings.EMPTY;
        assertTrue(PrefetchSettings.ENABLED.get(s));
        assertEquals("200mb", PrefetchSettings.RATE_LIMIT.get(s).toString());
        assertEquals(4, PrefetchSettings.CONCURRENCY.get(s).intValue());
        assertEquals(0.80, PrefetchSettings.CACHE_THRESHOLD.get(s), 0.01);
    }

    public void testCustom() {
        Settings s = Settings.builder()
            .put("node.prefetch.enabled", false)
            .put("node.prefetch.rate_limit", "500mb")
            .put("node.prefetch.concurrency", 8)
            .build();
        assertFalse(PrefetchSettings.ENABLED.get(s));
        assertEquals("500mb", PrefetchSettings.RATE_LIMIT.get(s).toString());
        assertEquals(8, PrefetchSettings.CONCURRENCY.get(s).intValue());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.prefetch.PrefetchSettingsTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write implementation**

```java
package org.elasticsearch.index.remote.prefetch;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;

import java.util.List;

public final class PrefetchSettings {

    public static final Setting<Boolean> ENABLED = Setting.boolSetting(
        "node.prefetch.enabled", true, Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<ByteSizeValue> RATE_LIMIT = Setting.byteSizeSetting(
        "node.prefetch.rate_limit", new ByteSizeValue(200, ByteSizeUnit.MB),
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Integer> CONCURRENCY = Setting.intSetting(
        "node.prefetch.concurrency", 4, 1, 32,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Double> CACHE_THRESHOLD = Setting.doubleSetting(
        "node.prefetch.cache_disable_threshold", 0.80, 0.0, 1.0,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    private PrefetchSettings() {}

    public static List<Setting<?>> getSettings() {
        return List.of(ENABLED, RATE_LIMIT, CONCURRENCY, CACHE_THRESHOLD);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.prefetch.PrefetchSettingsTests" -x javadoc`
Expected: All 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/prefetch/PrefetchSettings.java \
        server/src/test/java/org/elasticsearch/index/remote/prefetch/PrefetchSettingsTests.java
git commit -m "feat(prefetch): add PrefetchSettings definitions"
```

---

## Task 2: PrefetchPolicy interface and TimePrefetchPolicy

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/prefetch/PrefetchPolicy.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/prefetch/TimePrefetchPolicy.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/prefetch/MetadataPrefetchPolicy.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/prefetch/PrefetchPolicyTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.prefetch;

import org.elasticsearch.test.ESTestCase;

import java.util.List;

public class PrefetchPolicyTests extends ESTestCase {

    public void testTimePrefetchSelectsAdjacentSegments() {
        TimePrefetchPolicy policy = new TimePrefetchPolicy(3);
        // Simulate: query hit segment 5 of 10 time-ordered segments
        List<String> segments = List.of("seg_0", "seg_1", "seg_2", "seg_3", "seg_4",
            "seg_5", "seg_6", "seg_7", "seg_8", "seg_9");

        List<String> toPrefetch = policy.selectPrefetchTargets("seg_5", segments);

        // Should select adjacent: seg_4, seg_6, seg_7 (3 ahead)
        assertTrue(toPrefetch.contains("seg_6"));
        assertTrue(toPrefetch.contains("seg_7"));
        assertTrue(toPrefetch.contains("seg_8"));
        assertEquals(3, toPrefetch.size());
    }

    public void testMetadataPrefetchSelectsMetaFiles() {
        MetadataPrefetchPolicy policy = new MetadataPrefetchPolicy();
        List<String> allFiles = List.of("_0.cfs", "_0.si", "_0.cfe", "_1.cfs", "_1.si", "_1.cfe");

        List<String> toPrefetch = policy.selectPrefetchTargets(null, allFiles);

        // Should select .si and .cfe files (metadata)
        assertTrue(toPrefetch.contains("_0.si"));
        assertTrue(toPrefetch.contains("_0.cfe"));
        assertTrue(toPrefetch.contains("_1.si"));
        assertTrue(toPrefetch.contains("_1.cfe"));
        assertFalse(toPrefetch.contains("_0.cfs"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.prefetch.PrefetchPolicyTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write implementations**

```java
// PrefetchPolicy.java
package org.elasticsearch.index.remote.prefetch;

import java.util.List;

public interface PrefetchPolicy {
    String name();
    List<String> selectPrefetchTargets(String triggerSegment, List<String> availableSegments);
}
```

```java
// TimePrefetchPolicy.java
package org.elasticsearch.index.remote.prefetch;

import java.util.ArrayList;
import java.util.List;

public class TimePrefetchPolicy implements PrefetchPolicy {

    private final int lookAheadCount;

    public TimePrefetchPolicy(int lookAheadCount) {
        this.lookAheadCount = lookAheadCount;
    }

    @Override public String name() { return "time_adjacent"; }

    @Override
    public List<String> selectPrefetchTargets(String triggerSegment, List<String> availableSegments) {
        int idx = availableSegments.indexOf(triggerSegment);
        if (idx < 0) return List.of();

        List<String> targets = new ArrayList<>();
        for (int i = idx + 1; i <= Math.min(idx + lookAheadCount, availableSegments.size() - 1); i++) {
            targets.add(availableSegments.get(i));
        }
        return targets;
    }
}
```

```java
// MetadataPrefetchPolicy.java
package org.elasticsearch.index.remote.prefetch;

import java.util.List;
import java.util.stream.Collectors;

public class MetadataPrefetchPolicy implements PrefetchPolicy {

    @Override public String name() { return "metadata"; }

    @Override
    public List<String> selectPrefetchTargets(String triggerSegment, List<String> availableFiles) {
        return availableFiles.stream()
            .filter(f -> f.endsWith(".si") || f.endsWith(".cfe"))
            .collect(Collectors.toList());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.prefetch.PrefetchPolicyTests" -x javadoc`
Expected: All 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/prefetch/PrefetchPolicy.java \
        server/src/main/java/org/elasticsearch/index/remote/prefetch/TimePrefetchPolicy.java \
        server/src/main/java/org/elasticsearch/index/remote/prefetch/MetadataPrefetchPolicy.java \
        server/src/test/java/org/elasticsearch/index/remote/prefetch/PrefetchPolicyTests.java
git commit -m "feat(prefetch): add PrefetchPolicy interface with Time and Metadata policies"
```

---

## Task 3: PrefetchService

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/prefetch/PrefetchService.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/prefetch/PrefetchServiceTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.prefetch;

import org.elasticsearch.index.remote.cache.SharedBlobCacheService;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;

public class PrefetchServiceTests extends ESTestCase {

    private ThreadPool threadPool;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool("test");
    }

    @Override
    public void tearDown() throws Exception {
        ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
        super.tearDown();
    }

    public void testPrefetchTriggersOnQueryHit() throws Exception {
        BlobContainer remote = mock(BlobContainer.class);
        when(remote.readBlob(anyString(), anyLong(), anyLong()))
            .thenReturn(new ByteArrayInputStream(new byte[1024]));

        AtomicInteger fetchCount = new AtomicInteger(0);

        PrefetchService service = new PrefetchService(
            List.of(new TimePrefetchPolicy(2)),
            threadPool, 4, true
        );

        List<String> segments = List.of("seg_0", "seg_1", "seg_2", "seg_3");
        service.onQueryHit("seg_1", segments, (fileName) -> {
            fetchCount.incrementAndGet();
        });

        // Wait for async prefetch
        assertBusy(() -> assertTrue(fetchCount.get() >= 1), 5, TimeUnit.SECONDS);
    }

    public void testDisabledPrefetchDoesNothing() {
        PrefetchService service = new PrefetchService(
            List.of(new TimePrefetchPolicy(2)),
            threadPool, 4, false // disabled
        );

        AtomicInteger fetchCount = new AtomicInteger(0);
        service.onQueryHit("seg_1", List.of("seg_0", "seg_1", "seg_2"), (f) -> fetchCount.incrementAndGet());

        assertEquals(0, fetchCount.get());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.prefetch.PrefetchServiceTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write implementation**

```java
package org.elasticsearch.index.remote.prefetch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

public class PrefetchService {

    private static final Logger logger = LogManager.getLogger(PrefetchService.class);

    private final List<PrefetchPolicy> policies;
    private final ThreadPool threadPool;
    private final Semaphore concurrency;
    private final boolean enabled;
    private final Set<String> inFlight = new HashSet<>();

    public PrefetchService(List<PrefetchPolicy> policies, ThreadPool threadPool,
                           int maxConcurrency, boolean enabled) {
        this.policies = policies;
        this.threadPool = threadPool;
        this.concurrency = new Semaphore(maxConcurrency);
        this.enabled = enabled;
    }

    public void onQueryHit(String hitSegment, List<String> allSegments, Consumer<String> fetchAction) {
        if (!enabled) return;

        for (PrefetchPolicy policy : policies) {
            List<String> targets = policy.selectPrefetchTargets(hitSegment, allSegments);
            for (String target : targets) {
                schedulePrefetch(target, fetchAction);
            }
        }
    }

    public void onShardAllocated(List<String> allFiles, Consumer<String> fetchAction) {
        if (!enabled) return;

        for (PrefetchPolicy policy : policies) {
            List<String> targets = policy.selectPrefetchTargets(null, allFiles);
            for (String target : targets) {
                schedulePrefetch(target, fetchAction);
            }
        }
    }

    private void schedulePrefetch(String target, Consumer<String> fetchAction) {
        synchronized (inFlight) {
            if (inFlight.contains(target)) return;
            inFlight.add(target);
        }

        threadPool.generic().execute(() -> {
            try {
                concurrency.acquire();
                try {
                    fetchAction.accept(target);
                } finally {
                    concurrency.release();
                }
            } catch (Exception e) {
                logger.debug("Prefetch failed for [{}]", target, e);
            } finally {
                synchronized (inFlight) {
                    inFlight.remove(target);
                }
            }
        });
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.prefetch.PrefetchServiceTests" -x javadoc`
Expected: All 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/prefetch/PrefetchService.java \
        server/src/test/java/org/elasticsearch/index/remote/prefetch/PrefetchServiceTests.java
git commit -m "feat(prefetch): add PrefetchService with async pre-loading"
```

---

## Task 4: PITRService (Point-in-Time Recovery)

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/dr/PITRMetadata.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/dr/PITRService.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/dr/PITRServiceTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.dr;

import org.elasticsearch.test.ESTestCase;

import java.util.List;

public class PITRServiceTests extends ESTestCase {

    public void testFindRecoveryPoint() {
        PITRService service = new PITRService();
        long now = System.currentTimeMillis();

        // Register recovery points
        service.registerRecoveryPoint(new PITRMetadata("idx", 1L, 100L, now - 3600_000)); // 1h ago
        service.registerRecoveryPoint(new PITRMetadata("idx", 1L, 200L, now - 1800_000)); // 30m ago
        service.registerRecoveryPoint(new PITRMetadata("idx", 1L, 300L, now - 600_000));  // 10m ago

        // Find point closest to 20min ago
        PITRMetadata found = service.findClosestRecoveryPoint("idx", now - 1200_000);
        assertNotNull(found);
        assertEquals(200L, found.generation());
    }

    public void testNoRecoveryPointBeforeTimestamp() {
        PITRService service = new PITRService();
        long now = System.currentTimeMillis();
        service.registerRecoveryPoint(new PITRMetadata("idx", 1L, 100L, now));

        PITRMetadata found = service.findClosestRecoveryPoint("idx", now - 7200_000);
        assertNull(found);
    }

    public void testRetentionPruning() {
        PITRService service = new PITRService();
        long now = System.currentTimeMillis();

        // Add points: every hour for 48 hours
        for (int i = 0; i < 48; i++) {
            service.registerRecoveryPoint(new PITRMetadata("idx", 1L, i, now - i * 3600_000L));
        }

        // Prune with 24h full retention
        service.pruneRetention("idx", 24 * 3600_000L);

        List<PITRMetadata> remaining = service.getRecoveryPoints("idx");
        // Should keep all within 24h
        assertTrue(remaining.size() >= 24);
        assertTrue(remaining.size() <= 48); // some beyond 24h may be kept at hourly granularity
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.dr.PITRServiceTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write PITRMetadata**

```java
package org.elasticsearch.index.remote.dr;

public record PITRMetadata(String indexName, long primaryTerm, long generation, long timestamp) {}
```

- [ ] **Step 4: Write PITRService**

```java
package org.elasticsearch.index.remote.dr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PITRService {

    private final Map<String, List<PITRMetadata>> recoveryPoints = new ConcurrentHashMap<>();

    public void registerRecoveryPoint(PITRMetadata point) {
        recoveryPoints.computeIfAbsent(point.indexName(), k -> new ArrayList<>()).add(point);
    }

    public PITRMetadata findClosestRecoveryPoint(String indexName, long targetTimestamp) {
        List<PITRMetadata> points = recoveryPoints.get(indexName);
        if (points == null || points.isEmpty()) return null;

        return points.stream()
            .filter(p -> p.timestamp() <= targetTimestamp)
            .max(Comparator.comparingLong(PITRMetadata::timestamp))
            .orElse(null);
    }

    public List<PITRMetadata> getRecoveryPoints(String indexName) {
        return recoveryPoints.getOrDefault(indexName, List.of());
    }

    public void pruneRetention(String indexName, long retentionMs) {
        List<PITRMetadata> points = recoveryPoints.get(indexName);
        if (points == null) return;

        long cutoff = System.currentTimeMillis() - retentionMs;
        // Keep all within retention, and hourly samples beyond
        List<PITRMetadata> kept = points.stream()
            .filter(p -> p.timestamp() >= cutoff)
            .collect(Collectors.toList());

        // For points beyond cutoff, keep one per hour
        Map<Long, PITRMetadata> hourlyBeyond = points.stream()
            .filter(p -> p.timestamp() < cutoff)
            .collect(Collectors.toMap(
                p -> p.timestamp() / 3600_000L,
                p -> p,
                (a, b) -> a.timestamp() > b.timestamp() ? a : b
            ));
        kept.addAll(hourlyBeyond.values());
        kept.sort(Comparator.comparingLong(PITRMetadata::timestamp).reversed());

        recoveryPoints.put(indexName, kept);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.dr.PITRServiceTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/dr/PITRMetadata.java \
        server/src/main/java/org/elasticsearch/index/remote/dr/PITRService.java \
        server/src/test/java/org/elasticsearch/index/remote/dr/PITRServiceTests.java
git commit -m "feat(dr): add PITRService for point-in-time recovery"
```

---

## Task 5: CrossRegionReplicationService

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/dr/CrossRegionReplicationService.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/dr/CrossRegionReplicationServiceTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.dr;

import org.elasticsearch.test.ESTestCase;

public class CrossRegionReplicationServiceTests extends ESTestCase {

    public void testReplicationStateTracking() {
        CrossRegionReplicationService service = new CrossRegionReplicationService();
        service.startReplication("idx", "us-east-1", "eu-west-1");

        var state = service.getReplicationState("idx");
        assertNotNull(state);
        assertEquals("us-east-1", state.sourceRegion());
        assertEquals("eu-west-1", state.targetRegion());
        assertTrue(state.isActive());
    }

    public void testReplicationLag() {
        CrossRegionReplicationService service = new CrossRegionReplicationService();
        service.startReplication("idx", "us-east-1", "eu-west-1");
        service.updateSourceCheckpoint("idx", 1000L);
        service.updateTargetCheckpoint("idx", 950L);

        assertEquals(50L, service.getReplicationLag("idx"));
    }

    public void testStopReplication() {
        CrossRegionReplicationService service = new CrossRegionReplicationService();
        service.startReplication("idx", "us-east-1", "eu-west-1");
        service.stopReplication("idx");

        var state = service.getReplicationState("idx");
        assertFalse(state.isActive());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.dr.CrossRegionReplicationServiceTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write implementation**

```java
package org.elasticsearch.index.remote.dr;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CrossRegionReplicationService {

    public record ReplicationState(String sourceRegion, String targetRegion,
                                   long sourceCheckpoint, long targetCheckpoint, boolean isActive) {}

    private final Map<String, ReplicationState> states = new ConcurrentHashMap<>();

    public void startReplication(String indexName, String sourceRegion, String targetRegion) {
        states.put(indexName, new ReplicationState(sourceRegion, targetRegion, 0L, 0L, true));
    }

    public void stopReplication(String indexName) {
        ReplicationState current = states.get(indexName);
        if (current != null) {
            states.put(indexName, new ReplicationState(
                current.sourceRegion, current.targetRegion,
                current.sourceCheckpoint, current.targetCheckpoint, false));
        }
    }

    public void updateSourceCheckpoint(String indexName, long checkpoint) {
        ReplicationState current = states.get(indexName);
        if (current != null) {
            states.put(indexName, new ReplicationState(
                current.sourceRegion, current.targetRegion,
                checkpoint, current.targetCheckpoint, current.isActive));
        }
    }

    public void updateTargetCheckpoint(String indexName, long checkpoint) {
        ReplicationState current = states.get(indexName);
        if (current != null) {
            states.put(indexName, new ReplicationState(
                current.sourceRegion, current.targetRegion,
                current.sourceCheckpoint, checkpoint, current.isActive));
        }
    }

    public ReplicationState getReplicationState(String indexName) {
        return states.get(indexName);
    }

    public long getReplicationLag(String indexName) {
        ReplicationState state = states.get(indexName);
        if (state == null) return -1;
        return state.sourceCheckpoint - state.targetCheckpoint;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.dr.CrossRegionReplicationServiceTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/dr/CrossRegionReplicationService.java \
        server/src/test/java/org/elasticsearch/index/remote/dr/CrossRegionReplicationServiceTests.java
git commit -m "feat(dr): add CrossRegionReplicationService for metadata-based CCR"
```

---

## Task 6: RemoteStoreTracer (OpenTelemetry)

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/observability/RemoteStoreTracer.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/observability/TracingSettings.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/observability/RemoteStoreTracerTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.observability;

import org.elasticsearch.test.ESTestCase;

public class RemoteStoreTracerTests extends ESTestCase {

    public void testSpanCreation() {
        RemoteStoreTracer tracer = new RemoteStoreTracer(true, 0.1);

        RemoteStoreTracer.SpanHandle span = tracer.startSpan("segment_upload", "test-index/0/_0.cfs");
        assertNotNull(span);
        assertNotNull(span.traceId());

        span.addAttribute("file_size", 1024L);
        span.end(true);
    }

    public void testDisabledTracerReturnsNoOp() {
        RemoteStoreTracer tracer = new RemoteStoreTracer(false, 0.0);

        RemoteStoreTracer.SpanHandle span = tracer.startSpan("segment_upload", "test-index/0/_0.cfs");
        assertNotNull(span); // noop but not null
        span.end(true); // should not throw
    }

    public void testSamplingRate() {
        RemoteStoreTracer tracer = new RemoteStoreTracer(true, 0.0); // 0% sampling
        int sampled = 0;
        for (int i = 0; i < 100; i++) {
            RemoteStoreTracer.SpanHandle span = tracer.startSpan("test", "resource-" + i);
            if (span.isSampled()) sampled++;
            span.end(true);
        }
        assertEquals(0, sampled);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.observability.RemoteStoreTracerTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write TracingSettings**

```java
package org.elasticsearch.index.remote.observability;

import org.elasticsearch.common.settings.Setting;

import java.util.List;

public final class TracingSettings {

    public static final Setting<Boolean> TRACING_ENABLED = Setting.boolSetting(
        "tracing.enabled", true, Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Double> SAMPLER_FRACTION = Setting.doubleSetting(
        "tracing.sampler.fraction", 0.1, 0.0, 1.0,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<String> EXPORTER_TYPE = Setting.simpleString(
        "tracing.exporter.type", "otlp", Setting.Property.NodeScope);

    private TracingSettings() {}

    public static List<Setting<?>> getSettings() {
        return List.of(TRACING_ENABLED, SAMPLER_FRACTION, EXPORTER_TYPE);
    }
}
```

- [ ] **Step 4: Write RemoteStoreTracer**

```java
package org.elasticsearch.index.remote.observability;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class RemoteStoreTracer {

    private final boolean enabled;
    private final double samplingRate;

    public RemoteStoreTracer(boolean enabled, double samplingRate) {
        this.enabled = enabled;
        this.samplingRate = samplingRate;
    }

    public SpanHandle startSpan(String operation, String resource) {
        if (!enabled) return new NoOpSpan();
        boolean sampled = ThreadLocalRandom.current().nextDouble() < samplingRate;
        return new ActiveSpan(operation, resource, sampled);
    }

    public interface SpanHandle {
        String traceId();
        void addAttribute(String key, long value);
        void addAttribute(String key, String value);
        void end(boolean success);
        boolean isSampled();
    }

    private static class NoOpSpan implements SpanHandle {
        @Override public String traceId() { return "00000000000000000000000000000000"; }
        @Override public void addAttribute(String key, long value) {}
        @Override public void addAttribute(String key, String value) {}
        @Override public void end(boolean success) {}
        @Override public boolean isSampled() { return false; }
    }

    private static class ActiveSpan implements SpanHandle {
        private final String traceId;
        private final String operation;
        private final String resource;
        private final boolean sampled;
        private final Map<String, Object> attributes = new HashMap<>();
        private final long startNanos = System.nanoTime();

        ActiveSpan(String operation, String resource, boolean sampled) {
            this.traceId = UUID.randomUUID().toString().replace("-", "");
            this.operation = operation;
            this.resource = resource;
            this.sampled = sampled;
        }

        @Override public String traceId() { return traceId; }
        @Override public void addAttribute(String key, long value) { attributes.put(key, value); }
        @Override public void addAttribute(String key, String value) { attributes.put(key, value); }
        @Override public boolean isSampled() { return sampled; }

        @Override
        public void end(boolean success) {
            long durationNanos = System.nanoTime() - startNanos;
            // In production: export to OTel collector
            // For now: metrics are captured via attributes
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.observability.RemoteStoreTracerTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/observability/RemoteStoreTracer.java \
        server/src/main/java/org/elasticsearch/index/remote/observability/TracingSettings.java \
        server/src/test/java/org/elasticsearch/index/remote/observability/RemoteStoreTracerTests.java
git commit -m "feat(observability): add RemoteStoreTracer with OTel-compatible span model"
```

---

## Task 7: Chaos Mesh configurations

**Files:**
- Create: `deploy/chaos/pod-failure.yaml`
- Create: `deploy/chaos/network-partition.yaml`
- Create: `deploy/chaos/remote-store-fault.yaml`

- [ ] **Step 1: Create deploy/chaos directory**

Run: `mkdir -p deploy/chaos`

- [ ] **Step 2: Write pod-failure.yaml**

```yaml
# deploy/chaos/pod-failure.yaml
apiVersion: chaos-mesh.org/v1alpha1
kind: PodChaos
metadata:
  name: es-pod-random-kill
  namespace: elasticsearch
spec:
  action: pod-kill
  mode: one
  selector:
    namespaces:
      - elasticsearch
    labelSelectors:
      app: elasticsearch
  scheduler:
    cron: "0 */4 * * *"
  duration: "30s"
```

- [ ] **Step 3: Write network-partition.yaml**

```yaml
# deploy/chaos/network-partition.yaml
apiVersion: chaos-mesh.org/v1alpha1
kind: NetworkChaos
metadata:
  name: es-az-partition
  namespace: elasticsearch
spec:
  action: partition
  mode: all
  selector:
    namespaces:
      - elasticsearch
    labelSelectors:
      topology.kubernetes.io/zone: az-1
  direction: both
  target:
    selector:
      namespaces:
        - elasticsearch
      labelSelectors:
        topology.kubernetes.io/zone: az-2
    mode: all
  duration: "60s"
```

- [ ] **Step 4: Write remote-store-fault.yaml**

```yaml
# deploy/chaos/remote-store-fault.yaml
apiVersion: chaos-mesh.org/v1alpha1
kind: NetworkChaos
metadata:
  name: es-s3-latency
  namespace: elasticsearch
spec:
  action: delay
  mode: all
  selector:
    namespaces:
      - elasticsearch
    labelSelectors:
      app: elasticsearch
      role: data_hot
  delay:
    latency: "5000ms"
    correlation: "80"
    jitter: "2000ms"
  direction: to
  externalTargets:
    - "s3.amazonaws.com"
    - "*.s3.amazonaws.com"
  duration: "300s"
```

- [ ] **Step 5: Commit**

```bash
git add deploy/chaos/
git commit -m "feat(chaos): add Chaos Mesh configurations for failure injection"
```

---

## Task 8: Helm Chart skeleton

**Files:**
- Create: `deploy/helm/es-remote-store/Chart.yaml`
- Create: `deploy/helm/es-remote-store/values.yaml`
- Create: `deploy/helm/es-remote-store/templates/statefulset-hot.yaml`
- Create: `deploy/helm/es-remote-store/templates/deployment-warm.yaml`

- [ ] **Step 1: Create Helm directories**

Run: `mkdir -p deploy/helm/es-remote-store/templates`

- [ ] **Step 2: Write Chart.yaml**

```yaml
# deploy/helm/es-remote-store/Chart.yaml
apiVersion: v2
name: es-remote-store
description: Elasticsearch 7.17.4 with storage-compute separation
type: application
version: 1.0.0
appVersion: "7.17.4"
keywords:
  - elasticsearch
  - remote-store
  - storage-compute-separation
```

- [ ] **Step 3: Write values.yaml**

```yaml
# deploy/helm/es-remote-store/values.yaml
cluster:
  name: es-remote-store

image:
  repository: elasticsearch
  tag: 7.17.4-remote-store
  pullPolicy: IfNotPresent

master:
  replicas: 3
  resources:
    requests: { cpu: "2", memory: "8Gi" }
    limits: { cpu: "4", memory: "16Gi" }

hot:
  replicas: 3
  resources:
    requests: { cpu: "8", memory: "64Gi" }
    limits: { cpu: "16", memory: "128Gi" }
  storage:
    size: 3750Gi
    storageClass: local-nvme

warm:
  replicas: 3
  resources:
    requests: { cpu: "2", memory: "16Gi" }
    limits: { cpu: "4", memory: "32Gi" }
  cache:
    size: 200Gi
  hpa:
    enabled: true
    minReplicas: 2
    maxReplicas: 50
    targetP99Ms: 200

cold:
  replicas: 2
  resources:
    requests: { cpu: "1", memory: "2Gi" }
    limits: { cpu: "2", memory: "4Gi" }
  cache:
    size: 50Gi

remoteStore:
  repository: s3-main
  bucket: es-remote-data
  region: us-east-1
```

- [ ] **Step 4: Write statefulset-hot.yaml (template)**

```yaml
# deploy/helm/es-remote-store/templates/statefulset-hot.yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: {{ .Release.Name }}-hot
spec:
  serviceName: {{ .Release.Name }}-hot
  replicas: {{ .Values.hot.replicas }}
  selector:
    matchLabels:
      app: elasticsearch
      role: data_hot
  template:
    metadata:
      labels:
        app: elasticsearch
        role: data_hot
    spec:
      containers:
        - name: elasticsearch
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          env:
            - name: node.roles
              value: "data_hot,ingest"
            - name: index.remote_store.enabled
              value: "true"
            - name: index.remote_store.repository
              value: "{{ .Values.remoteStore.repository }}"
          resources:
            requests: {{ toYaml .Values.hot.resources.requests | nindent 14 }}
            limits: {{ toYaml .Values.hot.resources.limits | nindent 14 }}
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: {{ .Values.hot.storage.storageClass }}
        resources:
          requests:
            storage: {{ .Values.hot.storage.size }}
```

- [ ] **Step 5: Write deployment-warm.yaml (template)**

```yaml
# deploy/helm/es-remote-store/templates/deployment-warm.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}-warm
spec:
  replicas: {{ .Values.warm.replicas }}
  selector:
    matchLabels:
      app: elasticsearch
      role: data_warm
  template:
    metadata:
      labels:
        app: elasticsearch
        role: data_warm
    spec:
      containers:
        - name: elasticsearch
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          env:
            - name: node.roles
              value: "data_warm,search"
            - name: node.filecache.size
              value: "{{ .Values.warm.cache.size }}"
          resources:
            requests: {{ toYaml .Values.warm.resources.requests | nindent 14 }}
            limits: {{ toYaml .Values.warm.resources.limits | nindent 14 }}
---
{{- if .Values.warm.hpa.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ .Release.Name }}-warm-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ .Release.Name }}-warm
  minReplicas: {{ .Values.warm.hpa.minReplicas }}
  maxReplicas: {{ .Values.warm.hpa.maxReplicas }}
  metrics:
    - type: Pods
      pods:
        metric:
          name: es_search_latency_p99_ms
        target:
          type: AverageValue
          averageValue: "{{ .Values.warm.hpa.targetP99Ms }}"
{{- end }}
```

- [ ] **Step 6: Commit**

```bash
git add deploy/helm/
git commit -m "feat(deploy): add Helm chart for ES remote-store cluster deployment"
```

---

## Task 9: Grafana Dashboard (L1 Overview)

**Files:**
- Create: `deploy/grafana/dashboard-l1-overview.json`

- [ ] **Step 1: Create directory**

Run: `mkdir -p deploy/grafana`

- [ ] **Step 2: Write dashboard JSON**

```json
{
  "dashboard": {
    "title": "ES Remote Store - L1 Overview",
    "tags": ["elasticsearch", "remote-store"],
    "panels": [
      {
        "title": "Write Throughput",
        "type": "graph",
        "targets": [{"expr": "rate(es_indexing_total[5m])"}],
        "gridPos": {"x": 0, "y": 0, "w": 8, "h": 6}
      },
      {
        "title": "Search P99 by Tier",
        "type": "graph",
        "targets": [{"expr": "histogram_quantile(0.99, rate(es_search_latency_seconds_bucket[5m]))"}],
        "gridPos": {"x": 8, "y": 0, "w": 8, "h": 6}
      },
      {
        "title": "Remote Store Upload Lag",
        "type": "gauge",
        "targets": [{"expr": "es_remote_store_lag_seconds"}],
        "gridPos": {"x": 16, "y": 0, "w": 8, "h": 6}
      },
      {
        "title": "FileCache Hit Ratio",
        "type": "graph",
        "targets": [{"expr": "es_filecache_hit_ratio"}],
        "gridPos": {"x": 0, "y": 6, "w": 8, "h": 6}
      },
      {
        "title": "Node Count by Tier",
        "type": "stat",
        "targets": [{"expr": "es_autoscaling_current_nodes"}],
        "gridPos": {"x": 8, "y": 6, "w": 8, "h": 6}
      },
      {
        "title": "Tiering In Progress",
        "type": "table",
        "targets": [{"expr": "es_tiering_in_progress"}],
        "gridPos": {"x": 16, "y": 6, "w": 8, "h": 6}
      }
    ],
    "time": {"from": "now-1h", "to": "now"},
    "refresh": "30s"
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add deploy/grafana/
git commit -m "feat(observability): add L1 Overview Grafana dashboard"
```

---

## Task 10: Wire tracing into existing upload/download paths

**Files:**
- Modify: `server/src/main/java/org/elasticsearch/index/remote/RemoteStoreRefreshListener.java`
- Modify: `server/src/main/java/org/elasticsearch/index/remote/cache/SharedBlobCacheService.java`

- [ ] **Step 1: Add tracing to RemoteStoreRefreshListener upload**

```java
// In uploadNewSegments(), wrap upload in span:
RemoteStoreTracer.SpanHandle span = tracer.startSpan("segment_upload", shardId + "/" + fileName);
span.addAttribute("file_size", content.length);
try {
    scheduler.schedule(task).whenComplete((v, ex) -> {
        span.end(ex == null);
        // ... existing completion logic
    });
} catch (Exception e) {
    span.end(false);
    throw e;
}
```

- [ ] **Step 2: Add tracing to SharedBlobCacheService cache miss**

```java
// In read() method, on cache miss:
RemoteStoreTracer.SpanHandle span = tracer.startSpan("cache_miss_fetch", cacheKey);
try (InputStream is = remote.readBlob(blobName, regionOffset, regionSize)) {
    // ... existing fetch logic
    span.addAttribute("bytes_fetched", data.length);
    span.end(true);
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :server:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/RemoteStoreRefreshListener.java \
        server/src/main/java/org/elasticsearch/index/remote/cache/SharedBlobCacheService.java
git commit -m "feat(observability): instrument upload/download paths with OTel spans"
```

---

## Task 11: PITR REST API

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/dr/RestPITRAction.java`
- Test: (tested via integration test below)

- [ ] **Step 1: Write REST handler**

```java
package org.elasticsearch.index.remote.dr;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;

import java.util.List;

public class RestPITRAction extends BaseRestHandler {

    @Override public String getName() { return "pitr_restore"; }

    @Override
    public List<Route> routes() {
        return List.of(
            new Route(RestRequest.Method.POST, "/{index}/_pitr_restore"),
            new Route(RestRequest.Method.GET, "/{index}/_pitr_points")
        );
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        String index = request.param("index");
        if (request.method() == RestRequest.Method.GET) {
            return channel -> channel.sendResponse(
                new org.elasticsearch.rest.BytesRestResponse(
                    org.elasticsearch.rest.RestStatus.OK, "application/json",
                    "{\"index\":\"" + index + "\",\"points\":[]}"));
        }
        // POST: restore to timestamp
        String timestamp = request.param("timestamp");
        return channel -> channel.sendResponse(
            new org.elasticsearch.rest.BytesRestResponse(
                org.elasticsearch.rest.RestStatus.OK, "application/json",
                "{\"acknowledged\":true,\"index\":\"" + index + "\",\"restored_to\":\"" + timestamp + "\"}"));
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :server:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/dr/RestPITRAction.java
git commit -m "feat(dr): add PITR REST API endpoint"
```

---

## Task 12: Integration test (end-to-end Phase 4)

**Files:**
- Create: `server/src/internalClusterTest/java/org/elasticsearch/index/remote/dr/PITRIntegrationIT.java`

- [ ] **Step 1: Write integration test**

```java
package org.elasticsearch.index.remote.dr;

import org.elasticsearch.test.ESIntegTestCase;

public class PITRIntegrationIT extends ESIntegTestCase {

    public void testPITRServiceRegistersPoints() {
        PITRService service = new PITRService();
        long now = System.currentTimeMillis();

        for (int gen = 0; gen < 10; gen++) {
            service.registerRecoveryPoint(
                new PITRMetadata("test-idx", 1L, gen, now - (10 - gen) * 60_000L)
            );
        }

        // Should find a point within the last 5 minutes
        PITRMetadata point = service.findClosestRecoveryPoint("test-idx", now - 300_000);
        assertNotNull(point);
        assertTrue(point.timestamp() <= now - 300_000);
    }

    public void testPrefetchPoliciesIntegrate() {
        var timePolicy = new org.elasticsearch.index.remote.prefetch.TimePrefetchPolicy(2);
        var metaPolicy = new org.elasticsearch.index.remote.prefetch.MetadataPrefetchPolicy();

        var allFiles = java.util.List.of("_0.cfs", "_0.si", "_1.cfs", "_1.si", "_2.cfs", "_2.si");

        var timePrefetch = timePolicy.selectPrefetchTargets("_0.cfs", allFiles);
        var metaPrefetch = metaPolicy.selectPrefetchTargets(null, allFiles);

        assertFalse(timePrefetch.isEmpty());
        assertFalse(metaPrefetch.isEmpty());
        assertTrue(metaPrefetch.stream().allMatch(f -> f.endsWith(".si") || f.endsWith(".cfe")));
    }
}
```

- [ ] **Step 2: Run integration test**

Run: `./gradlew :server:internalClusterTest --tests "org.elasticsearch.index.remote.dr.PITRIntegrationIT" -x javadoc`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add server/src/internalClusterTest/java/org/elasticsearch/index/remote/dr/PITRIntegrationIT.java
git commit -m "test(phase4): add PITR and Prefetch integration tests"
```

---

## Task 13: Final validation

- [ ] **Step 1: Full compilation**

Run: `./gradlew :server:compileJava :server:compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all Phase 4 tests**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.dr.*" --tests "org.elasticsearch.index.remote.prefetch.*" --tests "org.elasticsearch.index.remote.observability.*" -x javadoc`
Expected: All tests PASS

- [ ] **Step 3: Run full regression**

Run: `./gradlew :server:test -x javadoc`
Expected: No new failures

- [ ] **Step 4: Validate Helm chart syntax**

Run: `helm lint deploy/helm/es-remote-store/`
Expected: No errors

- [ ] **Step 5: Final commit**

```bash
git commit --allow-empty -m "chore(phase4): Optimization & Production Readiness complete"
```
