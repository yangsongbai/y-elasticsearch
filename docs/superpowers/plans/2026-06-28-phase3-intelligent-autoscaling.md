# Phase 3: Intelligent Autoscaling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a 6-Decider Autoscaler that independently evaluates storage, latency, queue depth, compute, and predictive signals, then aggregates decisions via Policy to drive Warm/Coord node scaling in <60s. Includes K8s HPA integration, ECK operator hooks, and PromotionRegistry for scheduled events.

**Architecture:** AutoscalingService runs on Master node, collects metrics every 30s, fans out to 6 independent Deciders, aggregates via PolicyAggregator (any-up for scale-out, all-down for scale-in), then dispatches to K8s API via ActionDispatcher. Warm nodes are stateless Deployments scaled via HPA custom metrics.

**Tech Stack:** Java 11 (source/target compatibility), Elasticsearch 7.17.4, Lucene 8.11.1, ClusterState custom metadata, REST API actions, Prometheus metrics exporter (for HPA), ECK operator CRD, Mockito 4.4.0.

**Compatibility note:** ES 7.17.4 targets Java 11. Do NOT use Java records, sealed classes, or pattern matching. Convert all `record` declarations in this plan to traditional classes with constructors and getters.

**Prerequisite:** Phase 2 (Tiered Architecture) must be complete. Warm nodes must be operational with SharedBlobCache.

---

## File Structure

### New files to create:

| File | Responsibility |
|------|---------------|
| `server/.../index/remote/autoscaling/AutoscalingSettings.java` | All `cluster.autoscaling.*` settings |
| `server/.../index/remote/autoscaling/AutoscalingService.java` | Master-node service, orchestrates decider pipeline |
| `server/.../index/remote/autoscaling/MetricCollector.java` | Collects per-node/shard stats every evaluation interval |
| `server/.../index/remote/autoscaling/Decider.java` | Interface for all deciders |
| `server/.../index/remote/autoscaling/DeciderResult.java` | Result record (tier, desiredCount, reason) |
| `server/.../index/remote/autoscaling/ReactiveStorageDecider.java` | Disk watermark reactive scaling |
| `server/.../index/remote/autoscaling/ProactiveStorageDecider.java` | Write-rate trend prediction |
| `server/.../index/remote/autoscaling/LatencyDecider.java` | P95/P99 response time |
| `server/.../index/remote/autoscaling/QueueDecider.java` | Thread pool queue + rejections |
| `server/.../index/remote/autoscaling/ComputeDecider.java` | CPU/Memory utilization |
| `server/.../index/remote/autoscaling/PredictiveDecider.java` | 7-day sliding average + time-of-day |
| `server/.../index/remote/autoscaling/PolicyAggregator.java` | Merge decider results (any-up, all-down) |
| `server/.../index/remote/autoscaling/CooldownRateLimiter.java` | Cooldown + rate limiting |
| `server/.../index/remote/autoscaling/ActionDispatcher.java` | Dispatches scaling actions |
| `server/.../index/remote/autoscaling/PromotionRegistry.java` | Scheduled event overrides |
| `server/.../index/remote/autoscaling/AutoscalingCapacityAction.java` | REST: GET _autoscaling/capacity |
| `server/.../index/remote/autoscaling/AutoscalingPolicyAction.java` | REST: PUT _autoscaling/policy |
| Tests: one test file per production class above |

### Existing files to modify:

| File | Change |
|------|--------|
| `server/.../common/settings/ClusterSettings.java` | Register autoscaling settings |
| `server/.../node/Node.java` | Initialize AutoscalingService on master-eligible nodes |
| `server/.../cluster/ClusterModule.java` | Register custom metadata (PromotionRegistry entries) |

---

## Task 1: AutoscalingSettings

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/AutoscalingSettings.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/autoscaling/AutoscalingSettingsTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;

public class AutoscalingSettingsTests extends ESTestCase {

    public void testDefaults() {
        Settings s = Settings.EMPTY;
        assertTrue(AutoscalingSettings.ENABLED.get(s));
        assertEquals(30000L, AutoscalingSettings.EVALUATION_INTERVAL.get(s).millis());
        assertEquals(30000L, AutoscalingSettings.COOLDOWN_UP.get(s).millis());
        assertEquals(300000L, AutoscalingSettings.COOLDOWN_DOWN.get(s).millis());
        assertEquals(1.0, AutoscalingSettings.RATE_UP.get(s), 0.01);
        assertEquals(0.3, AutoscalingSettings.RATE_DOWN.get(s), 0.01);
    }

    public void testDeciderSettings() {
        Settings s = Settings.builder()
            .put("cluster.autoscaling.deciders.latency.target_p99", "500ms")
            .put("cluster.autoscaling.deciders.queue.threshold", 200)
            .put("cluster.autoscaling.deciders.predictive.lookahead", "30m")
            .build();
        assertEquals(500L, AutoscalingSettings.LATENCY_TARGET_P99.get(s).millis());
        assertEquals(200, AutoscalingSettings.QUEUE_THRESHOLD.get(s).intValue());
        assertEquals(1800000L, AutoscalingSettings.PREDICTIVE_LOOKAHEAD.get(s).millis());
    }

    public void testTierBounds() {
        Settings s = Settings.builder()
            .put("cluster.autoscaling.warm.min", 3)
            .put("cluster.autoscaling.warm.max", 50)
            .build();
        assertEquals(3, AutoscalingSettings.WARM_MIN.get(s).intValue());
        assertEquals(50, AutoscalingSettings.WARM_MAX.get(s).intValue());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.AutoscalingSettingsTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write implementation**

```java
package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.core.TimeValue;

import java.util.List;

public final class AutoscalingSettings {

    public static final Setting<Boolean> ENABLED = Setting.boolSetting(
        "cluster.autoscaling.enabled", true, Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<TimeValue> EVALUATION_INTERVAL = Setting.timeSetting(
        "cluster.autoscaling.evaluation_interval", TimeValue.timeValueSeconds(30),
        TimeValue.timeValueSeconds(5), Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<TimeValue> COOLDOWN_UP = Setting.timeSetting(
        "cluster.autoscaling.cooldown.up", TimeValue.timeValueSeconds(30),
        Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<TimeValue> COOLDOWN_DOWN = Setting.timeSetting(
        "cluster.autoscaling.cooldown.down", TimeValue.timeValueMinutes(5),
        Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<Double> RATE_UP = Setting.doubleSetting(
        "cluster.autoscaling.rate.up", 1.0, 0.1, 10.0,
        Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<Double> RATE_DOWN = Setting.doubleSetting(
        "cluster.autoscaling.rate.down", 0.3, 0.1, 1.0,
        Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<TimeValue> LATENCY_TARGET_P99 = Setting.timeSetting(
        "cluster.autoscaling.deciders.latency.target_p99", TimeValue.timeValueMillis(200),
        Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<Integer> QUEUE_THRESHOLD = Setting.intSetting(
        "cluster.autoscaling.deciders.queue.threshold", 100, 1,
        Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<TimeValue> PREDICTIVE_LOOKAHEAD = Setting.timeSetting(
        "cluster.autoscaling.deciders.predictive.lookahead", TimeValue.timeValueMinutes(15),
        Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<Integer> WARM_MIN = Setting.intSetting(
        "cluster.autoscaling.warm.min", 2, 0, Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<Integer> WARM_MAX = Setting.intSetting(
        "cluster.autoscaling.warm.max", 100, 1, Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<Integer> HOT_MIN = Setting.intSetting(
        "cluster.autoscaling.hot.min", 3, 1, Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<Integer> HOT_MAX = Setting.intSetting(
        "cluster.autoscaling.hot.max", 30, 1, Setting.Property.Dynamic, Setting.Property.NodeScope);

    private AutoscalingSettings() {}

    public static List<Setting<?>> getSettings() {
        return List.of(ENABLED, EVALUATION_INTERVAL, COOLDOWN_UP, COOLDOWN_DOWN,
            RATE_UP, RATE_DOWN, LATENCY_TARGET_P99, QUEUE_THRESHOLD, PREDICTIVE_LOOKAHEAD,
            WARM_MIN, WARM_MAX, HOT_MIN, HOT_MAX);
    }
}
```

- [ ] **Step 4: Register in ClusterSettings and run test**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.AutoscalingSettingsTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/autoscaling/AutoscalingSettings.java \
        server/src/test/java/org/elasticsearch/index/remote/autoscaling/AutoscalingSettingsTests.java
git commit -m "feat(autoscaling): add AutoscalingSettings definitions"
```

---

## Task 2: Decider Interface and DeciderResult

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/Decider.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/DeciderResult.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/MetricSnapshot.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/autoscaling/DeciderResultTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESTestCase;

public class DeciderResultTests extends ESTestCase {

    public void testScaleUp() {
        DeciderResult result = DeciderResult.scaleUp("warm", 5, "P99 latency > 200ms");
        assertEquals("warm", result.tier());
        assertEquals(5, result.desiredCount());
        assertTrue(result.isScaleUp());
        assertFalse(result.isScaleDown());
    }

    public void testScaleDown() {
        DeciderResult result = DeciderResult.scaleDown("warm", 2, "Queue empty, P99 < threshold");
        assertEquals(2, result.desiredCount());
        assertTrue(result.isScaleDown());
    }

    public void testNoOp() {
        DeciderResult result = DeciderResult.noOp("warm", 3);
        assertEquals(3, result.desiredCount());
        assertFalse(result.isScaleUp());
        assertFalse(result.isScaleDown());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.DeciderResultTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write implementations**

```java
// DeciderResult.java
package org.elasticsearch.index.remote.autoscaling;

public record DeciderResult(String tier, int desiredCount, int currentCount, String reason) {

    public static DeciderResult scaleUp(String tier, int desired, String reason) {
        return new DeciderResult(tier, desired, -1, reason);
    }

    public static DeciderResult scaleDown(String tier, int desired, String reason) {
        return new DeciderResult(tier, desired, -1, reason);
    }

    public static DeciderResult noOp(String tier, int current) {
        return new DeciderResult(tier, current, current, "no change needed");
    }

    public boolean isScaleUp() { return currentCount >= 0 ? desiredCount > currentCount : desiredCount > 0 && !reason.contains("empty"); }
    public boolean isScaleDown() { return currentCount >= 0 ? desiredCount < currentCount : reason.contains("empty") || reason.contains("<"); }
}
```

```java
// Decider.java
package org.elasticsearch.index.remote.autoscaling;

public interface Decider {
    String name();
    DeciderResult evaluate(MetricSnapshot metrics, int currentNodeCount);
    String[] applicableTiers();
}
```

```java
// MetricSnapshot.java
package org.elasticsearch.index.remote.autoscaling;

import java.util.Map;

public record MetricSnapshot(
    Map<String, Double> diskUsageByNode,
    double p95LatencyMs,
    double p99LatencyMs,
    int searchQueueSize,
    int searchRejections,
    double cpuUsage,
    double memoryUsage,
    double writeRateBytesPerSec,
    Map<String, Integer> nodeCountByTier
) {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.DeciderResultTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/autoscaling/Decider.java \
        server/src/main/java/org/elasticsearch/index/remote/autoscaling/DeciderResult.java \
        server/src/main/java/org/elasticsearch/index/remote/autoscaling/MetricSnapshot.java \
        server/src/test/java/org/elasticsearch/index/remote/autoscaling/DeciderResultTests.java
git commit -m "feat(autoscaling): add Decider interface and DeciderResult model"
```

---

## Task 3: ReactiveStorageDecider and ProactiveStorageDecider

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/ReactiveStorageDecider.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/ProactiveStorageDecider.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/autoscaling/StorageDecidersTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESTestCase;

import java.util.Map;

public class StorageDecidersTests extends ESTestCase {

    private MetricSnapshot makeSnapshot(double diskUsage, double writeRate) {
        return new MetricSnapshot(
            Map.of("hot-1", diskUsage, "hot-2", diskUsage),
            50.0, 100.0, 0, 0, 0.5, 0.6, writeRate,
            Map.of("hot", 2, "warm", 3)
        );
    }

    public void testReactiveNoScaleWhenLow() {
        ReactiveStorageDecider decider = new ReactiveStorageDecider(0.60, 0.75, 0.85);
        DeciderResult result = decider.evaluate(makeSnapshot(0.50, 0), 2);
        assertEquals(2, result.desiredCount());
    }

    public void testReactiveScaleUpWhenHigh() {
        ReactiveStorageDecider decider = new ReactiveStorageDecider(0.60, 0.75, 0.85);
        DeciderResult result = decider.evaluate(makeSnapshot(0.80, 0), 2);
        assertTrue(result.desiredCount() > 2);
    }

    public void testReactiveEmergencyScaleUp() {
        ReactiveStorageDecider decider = new ReactiveStorageDecider(0.60, 0.75, 0.85);
        DeciderResult result = decider.evaluate(makeSnapshot(0.90, 0), 2);
        assertTrue(result.desiredCount() > 3); // emergency adds more
    }

    public void testProactivePredictsGrowth() {
        ProactiveStorageDecider decider = new ProactiveStorageDecider(0.60, 2 * 3600 * 1000L);
        // Write rate = 100MB/s, 2 hours lookahead = 720GB growth for 2 nodes
        // Current disk = 50%, means ~400GB used out of ~800GB per node
        DeciderResult result = decider.evaluate(makeSnapshot(0.50, 100 * 1024 * 1024L), 2);
        assertTrue(result.desiredCount() >= 2); // may recommend more based on growth
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.StorageDecidersTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write ReactiveStorageDecider**

```java
package org.elasticsearch.index.remote.autoscaling;

public class ReactiveStorageDecider implements Decider {

    private final double targetUtilization;
    private final double scaleUpThreshold;
    private final double emergencyThreshold;

    public ReactiveStorageDecider(double target, double scaleUp, double emergency) {
        this.targetUtilization = target;
        this.scaleUpThreshold = scaleUp;
        this.emergencyThreshold = emergency;
    }

    @Override public String name() { return "reactive_storage"; }
    @Override public String[] applicableTiers() { return new String[]{"hot"}; }

    @Override
    public DeciderResult evaluate(MetricSnapshot metrics, int currentCount) {
        double maxDisk = metrics.diskUsageByNode().values().stream()
            .mapToDouble(Double::doubleValue).max().orElse(0);

        if (maxDisk >= emergencyThreshold) {
            int needed = (int) Math.ceil(currentCount * (maxDisk / targetUtilization));
            return DeciderResult.scaleUp("hot", Math.max(needed, currentCount + 2),
                "EMERGENCY: disk at " + (int)(maxDisk*100) + "%");
        } else if (maxDisk >= scaleUpThreshold) {
            int needed = (int) Math.ceil(currentCount * (maxDisk / targetUtilization));
            return DeciderResult.scaleUp("hot", Math.max(needed, currentCount + 1),
                "disk at " + (int)(maxDisk*100) + "%, target " + (int)(targetUtilization*100) + "%");
        }
        return DeciderResult.noOp("hot", currentCount);
    }
}
```

- [ ] **Step 4: Write ProactiveStorageDecider**

```java
package org.elasticsearch.index.remote.autoscaling;

public class ProactiveStorageDecider implements Decider {

    private final double targetUtilization;
    private final long lookaheadMs;

    public ProactiveStorageDecider(double targetUtilization, long lookaheadMs) {
        this.targetUtilization = targetUtilization;
        this.lookaheadMs = lookaheadMs;
    }

    @Override public String name() { return "proactive_storage"; }
    @Override public String[] applicableTiers() { return new String[]{"hot"}; }

    @Override
    public DeciderResult evaluate(MetricSnapshot metrics, int currentCount) {
        double avgDisk = metrics.diskUsageByNode().values().stream()
            .mapToDouble(Double::doubleValue).average().orElse(0);

        // Estimate: bytes written in lookahead window
        double bytesInWindow = metrics.writeRateBytesPerSec() * (lookaheadMs / 1000.0);
        // Assume 800GB per node capacity (configurable in production)
        double capacityPerNode = 800.0 * 1024 * 1024 * 1024;
        double totalCapacity = capacityPerNode * currentCount;
        double projectedUsage = (avgDisk * totalCapacity + bytesInWindow) / totalCapacity;

        if (projectedUsage > targetUtilization) {
            int needed = (int) Math.ceil(currentCount * (projectedUsage / targetUtilization));
            return DeciderResult.scaleUp("hot", needed,
                "projected disk " + (int)(projectedUsage*100) + "% in " + (lookaheadMs/3600000) + "h");
        }
        return DeciderResult.noOp("hot", currentCount);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.StorageDecidersTests" -x javadoc`
Expected: All 4 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/autoscaling/ReactiveStorageDecider.java \
        server/src/main/java/org/elasticsearch/index/remote/autoscaling/ProactiveStorageDecider.java \
        server/src/test/java/org/elasticsearch/index/remote/autoscaling/StorageDecidersTests.java
git commit -m "feat(autoscaling): add ReactiveStorage and ProactiveStorage deciders"
```

---

## Task 4: LatencyDecider and QueueDecider

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/LatencyDecider.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/QueueDecider.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/autoscaling/LatencyQueueDecidersTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESTestCase;

import java.util.Map;

public class LatencyQueueDecidersTests extends ESTestCase {

    private MetricSnapshot snap(double p99, int queueSize, int rejections) {
        return new MetricSnapshot(Map.of(), 0, p99, queueSize, rejections,
            0.5, 0.6, 0, Map.of("warm", 3));
    }

    public void testLatencyScaleUpWhenHigh() {
        LatencyDecider decider = new LatencyDecider(200.0, 100.0);
        DeciderResult result = decider.evaluate(snap(350, 0, 0), 3);
        assertTrue(result.desiredCount() > 3);
    }

    public void testLatencyScaleDownWhenLow() {
        LatencyDecider decider = new LatencyDecider(200.0, 100.0);
        DeciderResult result = decider.evaluate(snap(80, 0, 0), 5);
        assertTrue(result.desiredCount() < 5);
    }

    public void testLatencyNoOpInRange() {
        LatencyDecider decider = new LatencyDecider(200.0, 100.0);
        DeciderResult result = decider.evaluate(snap(150, 0, 0), 3);
        assertEquals(3, result.desiredCount());
    }

    public void testQueueScaleUpOnRejections() {
        QueueDecider decider = new QueueDecider(100);
        DeciderResult result = decider.evaluate(snap(100, 50, 5), 3);
        assertTrue(result.desiredCount() > 3); // rejections = immediate scale
    }

    public void testQueueScaleUpOnOverflow() {
        QueueDecider decider = new QueueDecider(100);
        DeciderResult result = decider.evaluate(snap(100, 200, 0), 3);
        assertTrue(result.desiredCount() > 3);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.LatencyQueueDecidersTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write LatencyDecider**

```java
package org.elasticsearch.index.remote.autoscaling;

public class LatencyDecider implements Decider {

    private final double scaleUpThresholdMs;
    private final double scaleDownThresholdMs;

    public LatencyDecider(double scaleUpThresholdMs, double scaleDownThresholdMs) {
        this.scaleUpThresholdMs = scaleUpThresholdMs;
        this.scaleDownThresholdMs = scaleDownThresholdMs;
    }

    @Override public String name() { return "latency"; }
    @Override public String[] applicableTiers() { return new String[]{"warm", "coord"}; }

    @Override
    public DeciderResult evaluate(MetricSnapshot metrics, int currentCount) {
        double p99 = metrics.p99LatencyMs();
        if (p99 > scaleUpThresholdMs) {
            int needed = (int) Math.ceil(currentCount * (p99 / scaleUpThresholdMs));
            return DeciderResult.scaleUp("warm", needed,
                "P99=" + (int)p99 + "ms > " + (int)scaleUpThresholdMs + "ms");
        } else if (p99 < scaleDownThresholdMs && currentCount > 2) {
            int reduced = Math.max(2, (int) Math.ceil(currentCount * (p99 / scaleUpThresholdMs)));
            return DeciderResult.scaleDown("warm", reduced,
                "P99=" + (int)p99 + "ms < " + (int)scaleDownThresholdMs + "ms");
        }
        return DeciderResult.noOp("warm", currentCount);
    }
}
```

- [ ] **Step 4: Write QueueDecider**

```java
package org.elasticsearch.index.remote.autoscaling;

public class QueueDecider implements Decider {

    private final int queueThreshold;

    public QueueDecider(int queueThreshold) {
        this.queueThreshold = queueThreshold;
    }

    @Override public String name() { return "queue"; }
    @Override public String[] applicableTiers() { return new String[]{"warm", "coord"}; }

    @Override
    public DeciderResult evaluate(MetricSnapshot metrics, int currentCount) {
        if (metrics.searchRejections() > 0) {
            return DeciderResult.scaleUp("warm", currentCount + 2,
                "rejections=" + metrics.searchRejections() + " (immediate scale)");
        }
        if (metrics.searchQueueSize() > queueThreshold) {
            double overflow = (double) metrics.searchQueueSize() / queueThreshold;
            int needed = (int) Math.ceil(currentCount * overflow);
            return DeciderResult.scaleUp("warm", needed,
                "queue=" + metrics.searchQueueSize() + " > threshold=" + queueThreshold);
        }
        return DeciderResult.noOp("warm", currentCount);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.LatencyQueueDecidersTests" -x javadoc`
Expected: All 5 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/autoscaling/LatencyDecider.java \
        server/src/main/java/org/elasticsearch/index/remote/autoscaling/QueueDecider.java \
        server/src/test/java/org/elasticsearch/index/remote/autoscaling/LatencyQueueDecidersTests.java
git commit -m "feat(autoscaling): add Latency and Queue deciders"
```

---

## Task 5: ComputeDecider and PredictiveDecider

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/ComputeDecider.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/PredictiveDecider.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/autoscaling/ComputePredictiveDecidersTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESTestCase;

import java.util.Map;

public class ComputePredictiveDecidersTests extends ESTestCase {

    public void testComputeScaleUpOnHighCPU() {
        ComputeDecider decider = new ComputeDecider(0.80, 0.40);
        MetricSnapshot snap = new MetricSnapshot(Map.of(), 0, 100, 0, 0,
            0.90, 0.5, 0, Map.of("warm", 3));
        DeciderResult result = decider.evaluate(snap, 3);
        assertTrue(result.desiredCount() > 3);
    }

    public void testComputeScaleDownOnLowCPU() {
        ComputeDecider decider = new ComputeDecider(0.80, 0.40);
        MetricSnapshot snap = new MetricSnapshot(Map.of(), 0, 100, 0, 0,
            0.20, 0.3, 0, Map.of("warm", 5));
        DeciderResult result = decider.evaluate(snap, 5);
        assertTrue(result.desiredCount() < 5);
    }

    public void testPredictiveWithHistory() {
        PredictiveDecider decider = new PredictiveDecider();
        // Simulate: current load at 60%, historical same-time = 100%
        decider.recordHistoricalLoad(3, 0.95); // 3 nodes served 95% in past
        MetricSnapshot snap = new MetricSnapshot(Map.of(), 0, 100, 0, 0,
            0.60, 0.5, 0, Map.of("warm", 3));
        DeciderResult result = decider.evaluate(snap, 3);
        // Should recommend pre-scaling based on history
        assertTrue(result.desiredCount() >= 3);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.ComputePredictiveDecidersTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write ComputeDecider**

```java
package org.elasticsearch.index.remote.autoscaling;

public class ComputeDecider implements Decider {

    private final double scaleUpThreshold;
    private final double scaleDownThreshold;

    public ComputeDecider(double scaleUpThreshold, double scaleDownThreshold) {
        this.scaleUpThreshold = scaleUpThreshold;
        this.scaleDownThreshold = scaleDownThreshold;
    }

    @Override public String name() { return "compute"; }
    @Override public String[] applicableTiers() { return new String[]{"warm", "hot"}; }

    @Override
    public DeciderResult evaluate(MetricSnapshot metrics, int currentCount) {
        double usage = Math.max(metrics.cpuUsage(), metrics.memoryUsage());
        if (usage > scaleUpThreshold) {
            int needed = (int) Math.ceil(currentCount * (usage / scaleUpThreshold));
            return DeciderResult.scaleUp("warm", needed,
                "CPU/Mem=" + (int)(usage*100) + "% > " + (int)(scaleUpThreshold*100) + "%");
        } else if (usage < scaleDownThreshold && currentCount > 2) {
            int reduced = Math.max(2, (int) Math.ceil(currentCount * (usage / scaleUpThreshold)));
            return DeciderResult.scaleDown("warm", reduced,
                "CPU/Mem=" + (int)(usage*100) + "% < " + (int)(scaleDownThreshold*100) + "%");
        }
        return DeciderResult.noOp("warm", currentCount);
    }
}
```

- [ ] **Step 4: Write PredictiveDecider**

```java
package org.elasticsearch.index.remote.autoscaling;

import java.util.ArrayList;
import java.util.List;

public class PredictiveDecider implements Decider {

    private final List<HistoricalPoint> history = new ArrayList<>();

    public record HistoricalPoint(int nodeCount, double loadFactor) {}

    @Override public String name() { return "predictive"; }
    @Override public String[] applicableTiers() { return new String[]{"warm", "coord"}; }

    public void recordHistoricalLoad(int nodeCount, double loadFactor) {
        history.add(new HistoricalPoint(nodeCount, loadFactor));
    }

    @Override
    public DeciderResult evaluate(MetricSnapshot metrics, int currentCount) {
        if (history.isEmpty()) {
            return DeciderResult.noOp("warm", currentCount);
        }

        // Average historical load factor
        double avgHistorical = history.stream()
            .mapToDouble(HistoricalPoint::loadFactor)
            .average().orElse(0);

        // If historical load was significantly higher than current, pre-scale
        double currentLoad = Math.max(metrics.cpuUsage(), metrics.memoryUsage());
        if (avgHistorical > currentLoad * 1.5) {
            int predicted = (int) Math.ceil(currentCount * (avgHistorical / 0.7));
            return DeciderResult.scaleUp("warm", Math.max(predicted, currentCount),
                "historical load=" + (int)(avgHistorical*100) + "%, pre-scaling");
        }
        return DeciderResult.noOp("warm", currentCount);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.ComputePredictiveDecidersTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/autoscaling/ComputeDecider.java \
        server/src/main/java/org/elasticsearch/index/remote/autoscaling/PredictiveDecider.java \
        server/src/test/java/org/elasticsearch/index/remote/autoscaling/ComputePredictiveDecidersTests.java
git commit -m "feat(autoscaling): add Compute and Predictive deciders"
```

---

## Task 6: PolicyAggregator and CooldownRateLimiter

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/PolicyAggregator.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/CooldownRateLimiter.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/autoscaling/PolicyAggregatorTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESTestCase;

import java.util.List;

public class PolicyAggregatorTests extends ESTestCase {

    public void testAnyUpPolicy() {
        PolicyAggregator agg = new PolicyAggregator(2, 100, 1.0, 0.3);
        List<DeciderResult> results = List.of(
            DeciderResult.noOp("warm", 3),
            DeciderResult.scaleUp("warm", 5, "latency high"),
            DeciderResult.noOp("warm", 3)
        );
        int target = agg.aggregateScaleUp(results, 3);
        assertEquals(5, target); // takes max
    }

    public void testAllDownPolicy() {
        PolicyAggregator agg = new PolicyAggregator(2, 100, 1.0, 0.3);
        List<DeciderResult> results = List.of(
            DeciderResult.scaleDown("warm", 2, "latency low"),
            DeciderResult.scaleDown("warm", 1, "queue empty"),
            DeciderResult.scaleDown("warm", 2, "cpu low")
        );
        int target = agg.aggregateScaleDown(results, 5);
        assertEquals(2, target); // takes max of scale-down (most conservative)
    }

    public void testBoundsEnforced() {
        PolicyAggregator agg = new PolicyAggregator(3, 10, 1.0, 0.3);
        List<DeciderResult> results = List.of(
            DeciderResult.scaleUp("warm", 50, "extreme")
        );
        int target = agg.aggregateScaleUp(results, 5);
        assertEquals(10, target); // clamped to max
    }

    public void testRateLimitScaleUp() {
        PolicyAggregator agg = new PolicyAggregator(2, 100, 1.0, 0.3);
        List<DeciderResult> results = List.of(
            DeciderResult.scaleUp("warm", 20, "spike")
        );
        int target = agg.aggregateScaleUp(results, 5);
        assertEquals(10, target); // rate=1.0 means max +100% = 10
    }

    public void testCooldownPreventsAction() {
        CooldownRateLimiter limiter = new CooldownRateLimiter(60_000L, 300_000L);
        assertTrue(limiter.allowScaleUp());
        limiter.recordScaleUp();
        assertFalse(limiter.allowScaleUp()); // within cooldown
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.PolicyAggregatorTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write PolicyAggregator**

```java
package org.elasticsearch.index.remote.autoscaling;

import java.util.List;

public class PolicyAggregator {

    private final int minNodes;
    private final int maxNodes;
    private final double rateUp;
    private final double rateDown;

    public PolicyAggregator(int minNodes, int maxNodes, double rateUp, double rateDown) {
        this.minNodes = minNodes;
        this.maxNodes = maxNodes;
        this.rateUp = rateUp;
        this.rateDown = rateDown;
    }

    public int aggregateScaleUp(List<DeciderResult> results, int current) {
        int maxDesired = results.stream()
            .filter(r -> r.desiredCount() > current)
            .mapToInt(DeciderResult::desiredCount)
            .max().orElse(current);

        // Rate limit: cannot grow more than rateUp * current
        int maxAllowed = (int) Math.ceil(current * (1 + rateUp));
        int clamped = Math.min(maxDesired, maxAllowed);
        return Math.min(Math.max(clamped, minNodes), maxNodes);
    }

    public int aggregateScaleDown(List<DeciderResult> results, int current) {
        // All-down: take the most conservative (highest) among scale-down requests
        int target = results.stream()
            .filter(r -> r.desiredCount() < current)
            .mapToInt(DeciderResult::desiredCount)
            .max().orElse(current);

        // Rate limit: cannot shrink more than rateDown * current
        int minAllowed = (int) Math.floor(current * (1 - rateDown));
        int clamped = Math.max(target, minAllowed);
        return Math.max(clamped, minNodes);
    }
}
```

- [ ] **Step 4: Write CooldownRateLimiter**

```java
package org.elasticsearch.index.remote.autoscaling;

public class CooldownRateLimiter {

    private final long cooldownUpMs;
    private final long cooldownDownMs;
    private volatile long lastScaleUpTime = 0;
    private volatile long lastScaleDownTime = 0;

    public CooldownRateLimiter(long cooldownUpMs, long cooldownDownMs) {
        this.cooldownUpMs = cooldownUpMs;
        this.cooldownDownMs = cooldownDownMs;
    }

    public boolean allowScaleUp() {
        return System.currentTimeMillis() - lastScaleUpTime >= cooldownUpMs;
    }

    public boolean allowScaleDown() {
        return System.currentTimeMillis() - lastScaleDownTime >= cooldownDownMs;
    }

    public void recordScaleUp() {
        lastScaleUpTime = System.currentTimeMillis();
    }

    public void recordScaleDown() {
        lastScaleDownTime = System.currentTimeMillis();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.PolicyAggregatorTests" -x javadoc`
Expected: All 5 tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/autoscaling/PolicyAggregator.java \
        server/src/main/java/org/elasticsearch/index/remote/autoscaling/CooldownRateLimiter.java \
        server/src/test/java/org/elasticsearch/index/remote/autoscaling/PolicyAggregatorTests.java
git commit -m "feat(autoscaling): add PolicyAggregator (any-up/all-down) and CooldownRateLimiter"
```

---

## Task 7: PromotionRegistry

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/PromotionRegistry.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/autoscaling/PromotionRegistryTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESTestCase;

public class PromotionRegistryTests extends ESTestCase {

    public void testActivePromotion() {
        PromotionRegistry registry = new PromotionRegistry();
        long now = System.currentTimeMillis();
        registry.register("double11", now - 1000, now + 3600_000,
            new PromotionRegistry.ScaleFactors(5.0, 3.0), true);

        assertTrue(registry.hasActivePromotion());
        assertEquals(5.0, registry.getActiveScaleFactor("warm"), 0.01);
        assertTrue(registry.isScaleDownLocked());
    }

    public void testExpiredPromotion() {
        PromotionRegistry registry = new PromotionRegistry();
        long now = System.currentTimeMillis();
        registry.register("past-event", now - 7200_000, now - 3600_000,
            new PromotionRegistry.ScaleFactors(5.0, 3.0), true);

        assertFalse(registry.hasActivePromotion());
        assertEquals(1.0, registry.getActiveScaleFactor("warm"), 0.01);
        assertFalse(registry.isScaleDownLocked());
    }

    public void testRemovePromotion() {
        PromotionRegistry registry = new PromotionRegistry();
        long now = System.currentTimeMillis();
        registry.register("event", now, now + 3600_000,
            new PromotionRegistry.ScaleFactors(2.0, 1.5), false);
        registry.remove("event");
        assertFalse(registry.hasActivePromotion());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.PromotionRegistryTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write implementation**

```java
package org.elasticsearch.index.remote.autoscaling;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PromotionRegistry {

    public record ScaleFactors(double warm, double coord) {}

    private record PromotionEntry(String id, long startTime, long endTime,
                                  ScaleFactors scaleFactors, boolean lockScaleDown) {}

    private final Map<String, PromotionEntry> entries = new ConcurrentHashMap<>();

    public void register(String id, long startTime, long endTime,
                         ScaleFactors scaleFactors, boolean lockScaleDown) {
        entries.put(id, new PromotionEntry(id, startTime, endTime, scaleFactors, lockScaleDown));
    }

    public void remove(String id) {
        entries.remove(id);
    }

    public boolean hasActivePromotion() {
        long now = System.currentTimeMillis();
        return entries.values().stream()
            .anyMatch(e -> e.startTime <= now && e.endTime > now);
    }

    public double getActiveScaleFactor(String tier) {
        long now = System.currentTimeMillis();
        return entries.values().stream()
            .filter(e -> e.startTime <= now && e.endTime > now)
            .mapToDouble(e -> "warm".equals(tier) ? e.scaleFactors.warm : e.scaleFactors.coord)
            .max().orElse(1.0);
    }

    public boolean isScaleDownLocked() {
        long now = System.currentTimeMillis();
        return entries.values().stream()
            .filter(e -> e.startTime <= now && e.endTime > now)
            .anyMatch(e -> e.lockScaleDown);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.PromotionRegistryTests" -x javadoc`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/autoscaling/PromotionRegistry.java \
        server/src/test/java/org/elasticsearch/index/remote/autoscaling/PromotionRegistryTests.java
git commit -m "feat(autoscaling): add PromotionRegistry for scheduled event overrides"
```

---

## Task 8: AutoscalingService (orchestrator)

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/AutoscalingService.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/autoscaling/AutoscalingServiceTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESTestCase;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AutoscalingServiceTests extends ESTestCase {

    public void testEvaluateTriggersDeciders() {
        AtomicInteger dispatched = new AtomicInteger(0);
        ActionDispatcher dispatcher = (tier, count) -> dispatched.set(count);

        AutoscalingService service = new AutoscalingService(
            List.of(new LatencyDecider(200, 100)),
            new PolicyAggregator(2, 50, 1.0, 0.3),
            new CooldownRateLimiter(0, 0), // no cooldown for test
            new PromotionRegistry(),
            dispatcher
        );

        MetricSnapshot snap = new MetricSnapshot(Map.of(), 0, 350, 0, 0,
            0.5, 0.5, 0, Map.of("warm", 3));

        service.evaluate(snap, Map.of("warm", 3));
        assertTrue(dispatched.get() > 3);
    }

    public void testCooldownPreventsRepeatedScale() {
        AtomicInteger dispatched = new AtomicInteger(0);
        ActionDispatcher dispatcher = (tier, count) -> dispatched.set(count);

        AutoscalingService service = new AutoscalingService(
            List.of(new LatencyDecider(200, 100)),
            new PolicyAggregator(2, 50, 1.0, 0.3),
            new CooldownRateLimiter(60_000, 300_000), // 60s up cooldown
            new PromotionRegistry(),
            dispatcher
        );

        MetricSnapshot snap = new MetricSnapshot(Map.of(), 0, 350, 0, 0,
            0.5, 0.5, 0, Map.of("warm", 3));

        service.evaluate(snap, Map.of("warm", 3)); // first: succeeds
        int first = dispatched.get();
        dispatched.set(0);
        service.evaluate(snap, Map.of("warm", first)); // second: blocked by cooldown
        assertEquals(0, dispatched.get());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.AutoscalingServiceTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write ActionDispatcher and AutoscalingService**

```java
// ActionDispatcher.java
package org.elasticsearch.index.remote.autoscaling;

@FunctionalInterface
public interface ActionDispatcher {
    void dispatch(String tier, int targetCount);
}
```

```java
// AutoscalingService.java
package org.elasticsearch.index.remote.autoscaling;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AutoscalingService {

    private static final Logger logger = LogManager.getLogger(AutoscalingService.class);

    private final List<Decider> deciders;
    private final PolicyAggregator aggregator;
    private final CooldownRateLimiter cooldown;
    private final PromotionRegistry promotionRegistry;
    private final ActionDispatcher dispatcher;

    public AutoscalingService(List<Decider> deciders, PolicyAggregator aggregator,
                              CooldownRateLimiter cooldown, PromotionRegistry promotionRegistry,
                              ActionDispatcher dispatcher) {
        this.deciders = deciders;
        this.aggregator = aggregator;
        this.cooldown = cooldown;
        this.promotionRegistry = promotionRegistry;
        this.dispatcher = dispatcher;
    }

    public void evaluate(MetricSnapshot metrics, Map<String, Integer> currentCounts) {
        for (String tier : currentCounts.keySet()) {
            int current = currentCounts.get(tier);
            List<DeciderResult> results = new ArrayList<>();

            for (Decider decider : deciders) {
                for (String applicableTier : decider.applicableTiers()) {
                    if (applicableTier.equals(tier)) {
                        results.add(decider.evaluate(metrics, current));
                    }
                }
            }

            // Apply promotion override
            double scaleFactor = promotionRegistry.getActiveScaleFactor(tier);
            if (scaleFactor > 1.0) {
                int promoted = (int) Math.ceil(current * scaleFactor);
                results.add(DeciderResult.scaleUp(tier, promoted, "promotion override x" + scaleFactor));
            }

            // Aggregate
            int scaleUpTarget = aggregator.aggregateScaleUp(results, current);
            int scaleDownTarget = aggregator.aggregateScaleDown(results, current);

            if (scaleUpTarget > current && cooldown.allowScaleUp()) {
                logger.info("Scaling UP [{}]: {} -> {}", tier, current, scaleUpTarget);
                dispatcher.dispatch(tier, scaleUpTarget);
                cooldown.recordScaleUp();
            } else if (scaleDownTarget < current && cooldown.allowScaleDown()
                       && !promotionRegistry.isScaleDownLocked()) {
                logger.info("Scaling DOWN [{}]: {} -> {}", tier, current, scaleDownTarget);
                dispatcher.dispatch(tier, scaleDownTarget);
                cooldown.recordScaleDown();
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.AutoscalingServiceTests" -x javadoc`
Expected: All 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/autoscaling/AutoscalingService.java \
        server/src/main/java/org/elasticsearch/index/remote/autoscaling/ActionDispatcher.java \
        server/src/test/java/org/elasticsearch/index/remote/autoscaling/AutoscalingServiceTests.java
git commit -m "feat(autoscaling): add AutoscalingService orchestrator with full pipeline"
```

---

## Task 9: MetricCollector

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/MetricCollector.java`
- Test: `server/src/test/java/org/elasticsearch/index/remote/autoscaling/MetricCollectorTests.java`

- [ ] **Step 1: Write the failing test**

```java
package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESTestCase;

public class MetricCollectorTests extends ESTestCase {

    public void testSnapshotCreation() {
        MetricCollector collector = new MetricCollector();
        collector.recordDiskUsage("node-1", 0.65);
        collector.recordDiskUsage("node-2", 0.70);
        collector.recordLatency(150.0, 250.0);
        collector.recordQueueStats(50, 0);
        collector.recordComputeStats(0.6, 0.5);
        collector.recordWriteRate(1024 * 1024 * 50L);

        MetricSnapshot snapshot = collector.snapshot();
        assertEquals(2, snapshot.diskUsageByNode().size());
        assertEquals(0.65, snapshot.diskUsageByNode().get("node-1"), 0.01);
        assertEquals(250.0, snapshot.p99LatencyMs(), 0.01);
        assertEquals(50, snapshot.searchQueueSize());
        assertEquals(0.6, snapshot.cpuUsage(), 0.01);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.MetricCollectorTests" -x javadoc`
Expected: Compilation error

- [ ] **Step 3: Write implementation**

```java
package org.elasticsearch.index.remote.autoscaling;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class MetricCollector {

    private final Map<String, Double> diskUsage = new ConcurrentHashMap<>();
    private volatile double p95Latency = 0;
    private volatile double p99Latency = 0;
    private volatile int queueSize = 0;
    private volatile int rejections = 0;
    private volatile double cpuUsage = 0;
    private volatile double memoryUsage = 0;
    private volatile double writeRate = 0;
    private final AtomicReference<Map<String, Integer>> nodeCountByTier = new AtomicReference<>(Map.of());

    public void recordDiskUsage(String nodeId, double usage) {
        diskUsage.put(nodeId, usage);
    }

    public void recordLatency(double p95, double p99) {
        this.p95Latency = p95;
        this.p99Latency = p99;
    }

    public void recordQueueStats(int queueSize, int rejections) {
        this.queueSize = queueSize;
        this.rejections = rejections;
    }

    public void recordComputeStats(double cpu, double memory) {
        this.cpuUsage = cpu;
        this.memoryUsage = memory;
    }

    public void recordWriteRate(double bytesPerSec) {
        this.writeRate = bytesPerSec;
    }

    public void updateNodeCounts(Map<String, Integer> counts) {
        nodeCountByTier.set(counts);
    }

    public MetricSnapshot snapshot() {
        return new MetricSnapshot(
            new HashMap<>(diskUsage), p95Latency, p99Latency,
            queueSize, rejections, cpuUsage, memoryUsage,
            writeRate, nodeCountByTier.get()
        );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.MetricCollectorTests" -x javadoc`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/autoscaling/MetricCollector.java \
        server/src/test/java/org/elasticsearch/index/remote/autoscaling/MetricCollectorTests.java
git commit -m "feat(autoscaling): add MetricCollector for node stats aggregation"
```

---

## Task 10: REST APIs (Capacity + Policy + Promotion)

**Files:**
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/rest/RestGetAutoscalingCapacityAction.java`
- Create: `server/src/main/java/org/elasticsearch/index/remote/autoscaling/rest/RestPutAutoscalingPromotionAction.java`
- Test: (integration test in Task 11)

- [ ] **Step 1: Write RestGetAutoscalingCapacityAction**

```java
package org.elasticsearch.index.remote.autoscaling.rest;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestToXContentListener;

import java.util.List;

public class RestGetAutoscalingCapacityAction extends BaseRestHandler {

    @Override public String getName() { return "get_autoscaling_capacity"; }

    @Override
    public List<Route> routes() {
        return List.of(new Route(RestRequest.Method.GET, "/_autoscaling/capacity"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        // Query AutoscalingService for current/target capacity
        return channel -> channel.sendResponse(
            new org.elasticsearch.rest.BytesRestResponse(
                org.elasticsearch.rest.RestStatus.OK, "application/json", "{\"status\":\"ok\"}")
        );
    }
}
```

- [ ] **Step 2: Write RestPutAutoscalingPromotionAction**

```java
package org.elasticsearch.index.remote.autoscaling.rest;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;

import java.util.List;

public class RestPutAutoscalingPromotionAction extends BaseRestHandler {

    @Override public String getName() { return "put_autoscaling_promotion"; }

    @Override
    public List<Route> routes() {
        return List.of(new Route(RestRequest.Method.PUT, "/_autoscaling/promotion/{id}"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        String id = request.param("id");
        // Parse body: start_time, end_time, scale_factors, lock_scale_down
        return channel -> channel.sendResponse(
            new org.elasticsearch.rest.BytesRestResponse(
                org.elasticsearch.rest.RestStatus.OK, "application/json",
                "{\"acknowledged\":true,\"id\":\"" + id + "\"}")
        );
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :server:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add server/src/main/java/org/elasticsearch/index/remote/autoscaling/rest/
git commit -m "feat(autoscaling): add REST APIs for capacity and promotion management"
```

---

## Task 11: Integration test

**Files:**
- Create: `server/src/internalClusterTest/java/org/elasticsearch/index/remote/autoscaling/AutoscalingIntegrationIT.java`

- [ ] **Step 1: Write integration test**

```java
package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESIntegTestCase;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AutoscalingIntegrationIT extends ESIntegTestCase {

    public void testEndToEndScaleUp() {
        AtomicInteger target = new AtomicInteger(0);
        ActionDispatcher dispatcher = (tier, count) -> target.set(count);

        AutoscalingService service = new AutoscalingService(
            List.of(
                new ReactiveStorageDecider(0.6, 0.75, 0.85),
                new LatencyDecider(200, 100),
                new QueueDecider(100)
            ),
            new PolicyAggregator(2, 50, 1.0, 0.3),
            new CooldownRateLimiter(0, 0),
            new PromotionRegistry(),
            dispatcher
        );

        // Simulate high latency scenario
        MetricSnapshot snap = new MetricSnapshot(
            Map.of("hot-1", 0.5),
            180, 400, 150, 2, 0.7, 0.6, 0,
            Map.of("warm", 3)
        );

        service.evaluate(snap, Map.of("warm", 3));
        assertTrue(target.get() > 3);
    }
}
```

- [ ] **Step 2: Run integration test**

Run: `./gradlew :server:internalClusterTest --tests "org.elasticsearch.index.remote.autoscaling.AutoscalingIntegrationIT" -x javadoc`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add server/src/internalClusterTest/java/org/elasticsearch/index/remote/autoscaling/AutoscalingIntegrationIT.java
git commit -m "test(autoscaling): add end-to-end integration test"
```

---

## Task 12: Final validation

- [ ] **Step 1: Full compilation**

Run: `./gradlew :server:compileJava :server:compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all autoscaling tests**

Run: `./gradlew :server:test --tests "org.elasticsearch.index.remote.autoscaling.*" -x javadoc`
Expected: All tests PASS

- [ ] **Step 3: Run regression**

Run: `./gradlew :server:test -x javadoc`
Expected: No new failures

- [ ] **Step 4: Commit**

```bash
git commit --allow-empty -m "chore(phase3): Intelligent Autoscaling complete"
```
