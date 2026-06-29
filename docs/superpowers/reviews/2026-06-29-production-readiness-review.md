# Production Readiness Review — Storage-Compute Separation

**Date:** 2026-06-29  
**Branch:** feature-7.17.4-20260628  
**Verdict:** NOT READY — requires fixes before production deployment

---

## Critical Findings

### 1. Services Not Wired Into ES Lifecycle (BLOCKER)

**Affected:** PrefetchService, PITRService, AutoscalingService, TieringPolicyService, CrossRegionReplicationService, SharedBlobCacheService

**Problem:** These services are standalone POJOs never instantiated in production code. They don't implement `LifecycleComponent`, aren't registered in `Node.java`, and have no scheduled triggers.

**Impact:** Features don't function on a running cluster. REST endpoints return fake responses.

**Fix:** Register in Node.java or plugin module; implement proper lifecycle interfaces. (Deferred — requires design decision on plugin vs. core integration.)

---

### 2. Metadata Uploaded Before Segment Files Complete (CRITICAL)

**File:** `RemoteStoreRefreshListener.java:125`

**Problem:** `scheduler.schedule()` is async but metadata upload proceeds without awaiting futures. Replicas see metadata referencing non-existent files.

**Fix:** Collect all futures, await with `CompletableFuture.allOf()` before uploading metadata.

---

### 3. Tiering State Not Persisted (CRITICAL)

**File:** `TieringService.java:26`

**Problem:** State in node-local `ConcurrentHashMap`, lost on restart, not replicated.

**Fix:** For now, add persistence note. Full fix requires ClusterState custom metadata integration.

---

### 4. PITRService Thread-Unsafe ArrayList (CRITICAL)

**File:** `PITRService.java:17`

**Problem:** `computeIfAbsent` returns shared `ArrayList`, concurrent `add()` corrupts it.

**Fix:** Use `CopyOnWriteArrayList`.

---

### 5. RemoteStoreRefreshListener Race on uploadedFiles (CRITICAL)

**File:** `RemoteStoreRefreshListener.java:89`

**Problem:** Unsynchronized `removeAll(uploadedFiles)` races with synchronized `add()` in callbacks.

**Fix:** Synchronize the read path or use `ConcurrentHashMap.newKeySet()`.

---

### 6. Settings Not Registered in ClusterSettings (MEDIUM)

**Files:** `PrefetchSettings.java`, `TracingSettings.java`

**Problem:** 7 settings never registered — rejected as "unknown setting" at runtime.

**Fix:** Add to `ClusterSettings.BUILT_IN_CLUSTER_SETTINGS`.

---

### 7. maxBytesInFlight Not Enforced (HIGH)

**File:** `SegmentUploadScheduler.java:76`

**Problem:** `maxBytesInFlight` stored but never checked — queue grows unbounded under load.

**Fix:** Block or reject in `schedule()` when `bytesPending` exceeds limit.

---

### 8. PrefetchService Blocking Semaphore on Generic Pool (HIGH)

**File:** `PrefetchService.java:63`

**Problem:** `Semaphore.acquire()` blocks generic pool threads, starving cluster operations.

**Fix:** Use `tryAcquire()` with skip-on-failure.

---

### 9. SharedBlobCacheService Race on Region Allocation (HIGH)

**File:** `SharedBlobCacheService.java:55`

**Problem:** Check-then-act race — two threads allocate duplicate regions for same key.

**Fix:** Use `computeIfAbsent` for atomic region creation.

---

### 10. CrossRegionReplicationService Lost-Update Race (MEDIUM)

**File:** `CrossRegionReplicationService.java:25`

**Problem:** Concurrent checkpoint updates overwrite each other (read-modify-write without atomicity).

**Fix:** Use `ConcurrentHashMap.compute()` for atomic updates.

---

## Summary

| Category | Count | Severity |
|----------|-------|----------|
| Dead code / no lifecycle wiring | 6+ services | Blocker (deferred) |
| Race conditions / thread safety | 5 | Critical |
| Resource leaks / OOM | 2 | High |
| Missing configuration registration | 7 settings | Medium |
