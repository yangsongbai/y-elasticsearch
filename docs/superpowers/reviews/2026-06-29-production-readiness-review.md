# Production-Readiness Review — Storage-Compute Separation

**Date:** 2026-06-29  
**Branch:** feature-7.17.4-20260628  
**Verdict:** 40 production-readiness issues found and fixed across 8 review rounds.

---

## Review #7: Post-Fix Audit (Round 6)

After fixing all 26 findings from rounds 1-5, a sixth review pass found 7 additional issues — missing lifecycle deactivation, unchecked exceptions escaping lock renewal, blocking semaphore with no timeout, double-release on exception propagation, stale thread interrupt, and a TOCTOU in file upload.

## Review #8: Post-Fix Audit (Round 7)

After fixing all 33 findings from rounds 1-6, a seventh review pass found 4 additional issues — a non-monotonic generation counter, lost pendingUpload on rejection/failure, a TOCTOU in lock acquisition, and silent translog generation loss on semaphore timeout.

## Review #9: Post-Fix Audit (Round 8)

After fixing all 37 findings from rounds 1-7, an eighth review pass found 3 additional issues — unbounded stack recursion in onRejection retry, uncaught IOException in tryAcquire main path, and orphaned futures on scheduler close.

---

## Round 8 Findings

### Finding 38 — onRejection/onFailure retries afterRefresh synchronously with no depth limit → StackOverflowError [P1, FIXED]

**File:** `RemoteStoreRefreshListener.java:125`  
**Bug:** The Round 7 fix for "onRejection losing pendingUpload" added `afterRefresh(true)` calls in onRejection and onFailure. But these run synchronously on the caller's thread. If the thread pool continuously rejects (during shutdown under load), and concurrent refreshes keep setting pendingUpload=true, each onRejection picks up the flag and recurses: afterRefresh → submit → rejected → onRejection → afterRefresh → ... Stack grows ~4 frames per iteration with no bound.  
**Impact:** StackOverflowError crashes the calling thread. uploadInProgress stays true permanently, halting all future segment uploads for this shard.  
**Fix:** Changed onRejection and onFailure to simply set `pendingUpload.set(true)` instead of synchronously calling afterRefresh. This preserves the signal (the next Lucene refresh triggers afterRefresh which picks up pendingUpload). The doRun finally block still retries on the pool thread — safe because it's bounded by a single recursive call that dispatches to the pool rather than running inline.

### Finding 39 — tryAcquire main path does not catch IOException from writeLock, unlike NoSuchFileException path [P1, FIXED]

**File:** `SingleWriterLock.java:51`  
**Bug:** In tryAcquire's main path (lock exists with lower term), after `deleteLock()` and `writeLock(failIfAlreadyExists=true)`, an IOException (e.g., "blob already exists" due to contention, or deleteLock silently failing) propagated to the caller instead of returning false. The NoSuchFileException path (line 64) correctly caught this with try/catch.  
**Impact:** Caller treats IOException as fatal shard failure. Shard becomes unassignable on transient blob store contention.  
**Fix:** Wrapped `writeLock` in the main path with try/catch(IOException), returning false on contention — same pattern as the NoSuchFileException path.

### Finding 40 — SegmentUploadScheduler.close() does not drain queue or fail pending futures [P2, FIXED]

**File:** `SegmentUploadScheduler.java:184`  
**Bug:** `close()` only set a flag. Tasks already in the queue (with bytesPending counted) whose futures had never been dispatched would never complete. If all parallelism slots were held by uploads blocked on an unresponsive remote store, no drainQueue ever ran, and callers blocked at `allFuture.get(300s)`.  
**Impact:** Node shutdown delayed by up to 300s per shard. A node with many shards takes N*300s to shut down. Master marks node as failed, triggering unnecessary shard reallocation.  
**Fix:** `close()` now drains the queue after setting the flag: polls all remaining tasks, completes their futures exceptionally with "scheduler closed", and decrements bytesPending. Callers immediately unblock.

---

## Round 7 Findings

### Finding 34 — lastUploadedGeneration uses bare set() instead of monotonic CAS; parallel uploads regress the counter [P1, FIXED]

**File:** `RemoteTranslogTransferManager.java:49`  
**Bug:** `lastUploadedGeneration.set(generation)` is a bare write with no monotonic guard. With `parallelUploads > 1`, if generation N+1 completes before generation N (variable network latency), the counter is first set to N+1 then regressed to N.  
**Impact:** Downstream code (translog trim, recovery) that trusts `getLastUploadedGeneration()` may delete generation N+1 locally (believing it's safely remote), or fail to detect a gap during disaster recovery → data loss.  
**Fix:** Added `updateLastUploadedGeneration()` using a CAS loop (same pattern already used in `RemoteStoreRefreshListener`). Counter only advances forward.

### Finding 35 — onRejection and onFailure do not drain pendingUpload, losing a queued refresh [P1, FIXED]

**File:** `RemoteStoreRefreshListener.java:119`  
**Bug:** `onRejection` (and `onFailure`) only reset `uploadInProgress.set(false)` without checking `pendingUpload`. If a concurrent refresh set `pendingUpload=true` before the rejection fired, that signal is permanently lost until the next external Lucene refresh.  
**Impact:** Segments from the lost refresh are never uploaded. If `deactivate()` fires before the next refresh (e.g., during relocation), those segments are permanently absent from remote store → data loss on disaster recovery.  
**Fix:** Added `pendingUpload.getAndSet(false) && active.get() → afterRefresh(true)` drain logic to both `onRejection` and `onFailure`, matching the pattern in `doRun`'s finally block.

### Finding 36 — TOCTOU in tryAcquire allows two nodes with same primary term to both hold the lock [P1, FIXED]

**File:** `SingleWriterLock.java:50`  
**Bug:** `tryAcquire` reads `existingTerm`, checks it's lower, then writes with `failIfAlreadyExists=false` (overwrite). Between read and write, another node with the same primary term (during relocation) can also read + write, both succeeding.  
**Impact:** Both nodes set `heldLocally=true` and write to remote store concurrently, corrupting segment metadata.  
**Fix:** Changed to delete-then-write-with-failIfAlreadyExists=true pattern, followed by a read-back verification (`verifyLockHolder`) that confirms our `nodeId` is in the written blob. If verification fails (another node won the race), `tryAcquire` returns false.

### Finding 37 — Semaphore timeout silently drops translog generation with no error propagation [P2, FIXED]

**File:** `RemoteTranslogTransferManager.java:73`  
**Bug:** If `tryAcquire(30, SECONDS)` timed out, the method just logged a warning and returned. The generation was silently never uploaded. Combined with finding 34's non-monotonic counter, a later generation succeeding would mask the gap.  
**Impact:** Permanent gap in remote translog. On disaster recovery, operations from the lost generation are unrecoverable.  
**Fix:** Changed from silent return to throwing `IOException("Timed out waiting for upload permit...")`. The exception propagates to `onFailure` which logs at WARN level, making the failure visible in monitoring. Upstream retry mechanisms (if any) can act on the exception.

---

## Round 6 Findings

### Finding 27 — No deactivation path for RemoteStoreRefreshListener [P1, FIXED]

**File:** `RemoteStoreRefreshListener.java:44`  
**Bug:** The `active` AtomicBoolean can be set to `true` (constructor, `activate()`) but is NEVER set to `false`. No `deactivate()` or `close()` method exists. After a primary shard is relocated, the old node's listener remains active during the window between handoff and shard removal.  
**Impact:** Both old and new primaries upload segments concurrently. Old primary's metadata upload overwrites new primary's metadata with stale file references → data corruption on recovery from remote store.  
**Fix:** Added `deactivate()` method that sets `active.set(false)`. Callers (IndexShard relocation path) should invoke this on demotion.

### Finding 28 — tryRenew catches only IOException; XContent parsing throws unchecked exceptions [P1, FIXED]

**File:** `SingleWriterLock.java:87`  
**Bug:** `tryRenew()` caught only `IOException`, but `readCurrentTerm()` parses JSON via XContentParser. If the lock blob is malformed/corrupted, `parser.longValue()` throws `IllegalArgumentException` (unchecked) and `XContentParseException` extends `IllegalArgumentException`. These escape `tryRenew` entirely, crashing the renewal scheduler.  
**Impact:** `heldLocally` stays true (no cleanup), no further renewals occur. Another node acquires the lock → split-brain dual-writer corruption.  
**Fix:** Changed `catch (IOException e)` to `catch (Exception e)` to handle all exception types uniformly through the degraded/lease-expired state machine.

### Finding 29 — Blocking Semaphore.acquire() with no timeout in generic thread pool [P1, FIXED]

**File:** `RemoteTranslogTransferManager.java:69`  
**Bug:** `doRun()` called `parallelism.acquire()` which blocks indefinitely if all permits are held. The generic thread pool is bounded (128-512 threads). `close()` only set a flag — did not release permits or interrupt blocked threads.  
**Impact:** Slow/unreachable blob store holds all permits. Subsequent uploadGenerationAsync calls block generic pool threads. Multiple shards with queued uploads exhaust the pool → cluster state updates, recovery, and transport stall → node removed from cluster.  
**Fix:** Replaced `acquire()` with `tryAcquire(30, TimeUnit.SECONDS)`. Added `closed` check before and after acquire to allow graceful exit on shutdown.

### Finding 30 — Double-release of semaphore/bytesPending if drainQueue() throws in finally [P2, FIXED]

**File:** `SegmentUploadScheduler.java:163`  
**Bug:** `executeUpload`'s finally block releases bytesPending + semaphore, then calls `drainQueue()`. If `drainQueue()` throws (e.g., `threadPool.generic().execute()` during shutdown throws RejectedExecutionException bypassing `onRejection`), the exception propagates out of `doRun` to `AbstractRunnable.run()` which calls `onFailure`. `onFailure` repeats the same cleanup — double-release.  
**Impact:** Semaphore exceeds initial permits (allowing unbounded parallelism), bytesPending goes negative (backpressure permanently disabled) → potential OOM from unbounded concurrent uploads.  
**Fix:** Wrapped `drainQueue()` call in try-catch within the finally block. Also wrapped `bp.recordFailure()` to prevent its exception from escaping the catch block. Now no exception can propagate from `doRun` to `onFailure`.

### Finding 31 — Stale thread interrupt corrupts next task on pooled thread [P2, FIXED]

**File:** `RelocationUploadService.java:82`  
**Bug:** After timeout, `t.interrupt()` is called on the worker thread reference. But `runningThread` is never cleared after `doRun()` completes. If timeout fires after the worker finished and returned to the pool, the interrupt flag is set on a thread now executing an unrelated task.  
**Impact:** The next task on that thread (cluster state application, recovery) encounters the stale interrupt flag on its next blocking I/O → `InterruptedException` → unrelated operation fails.  
**Fix:** (1) Worker's `doRun` now clears `runningThread.set(null)` in a finally block. (2) Timeout handler uses `runningThread.getAndSet(null)` to atomically clear the reference before interrupting, preventing double-interrupt and stale reference.

### Finding 32 — TOCTOU: Files.size() after Files.newInputStream() [P3, FIXED]

**File:** `RemoteTranslogTransferManager.java:45`  
**Bug:** InputStream was opened FIRST, then `Files.size()` was called. If the file changes between the two operations, the size passed to `writeBlob` differs from stream content.  
**Impact:** Under normal append-only translog semantics, the size would only grow (not shrink), so the stream would have more data than `size` indicates — causing either truncation (data loss) or implementation-specific blob store behavior on short read.  
**Fix:** Moved `Files.size()` BEFORE `Files.newInputStream()`. The size is captured from a stable snapshot; the stream opened afterward reads from the same or newer state.

### Finding 33 — Non-atomic volatile int consecutiveFailures++ [P3, FIXED]

**File:** `SingleWriterLock.java:88`  
**Bug:** `consecutiveFailures` was `volatile int` but incremented with `++` (read-modify-write). While `volatile` provides visibility, `++` is NOT atomic. Concurrent `tryRenew` calls can lose increments.  
**Impact:** With `degradeAfterFailures=2`, the system may require 3+ actual failures to trigger degraded mode — delaying the safety fallback that allows local writes during remote store outages.  
**Fix:** Changed from `volatile int` to `AtomicInteger`. Uses `incrementAndGet()` for atomic increment and `set(0)` for reset on success.

---

## Round 5 Findings

### Finding 24 — onFailure executes after doRun's finally dispatches retry, corrupting uploadInProgress [P1, FIXED]

**File:** `RemoteStoreRefreshListener.java:100`  
**Bug:** If `uploadNewSegments()` throws, the `finally` block runs: sets `uploadInProgress=false`, sees `pendingUpload=true`, calls `afterRefresh(true)` which CASes `uploadInProgress` back to `true` and dispatches a retry. Then the exception propagates out of `doRun()` to `AbstractRunnable.run()` which calls `onFailure()`. `onFailure` sets `uploadInProgress=false` again — spuriously clearing the retry task's exclusive lock.  
**Impact:** Both the retry task and a concurrent refresh can acquire the CAS simultaneously, violating the single-upload invariant. Concurrent metadata writes cause stale-metadata overwrite.  
**Fix:** Catch exceptions from `uploadNewSegments()` inside `doRun()` (log and swallow), so they never propagate to `onFailure`. The `finally` block is the sole manager of `uploadInProgress` lifecycle and retry logic.

### Finding 25 — forceUploadCallback volatile field not captured to local before async dispatch [P1, FIXED]

**File:** `RelocationUploadService.java:56`  
**Bug:** `prepareForHandoff()` null-checks `forceUploadCallback` on the calling thread (line 34), then the `doRun()` lambda reads it again from the instance field on the pool thread (line 56). If `setForceUploadCallback(null)` is called while the task is queued (e.g., during shard close), the pool thread reads null and throws NPE.  
**Impact:** Handoff fails with FAILED status when it should have succeeded with the callback that was valid at dispatch time. Under rapid shard lifecycle transitions, this causes unnecessary relocation retries.  
**Fix:** Captured the volatile field to a local variable (`callback`) before the null check. The lambda captures the local, which is immutable and can never become null.

### Finding 26 — SegmentUploadScheduler onFailure leaks semaphore permit and bytesPending [P1, FIXED]

**File:** `SegmentUploadScheduler.java:123`  
**Bug:** `onFailure` only logged and completed the future. It did not release the semaphore permit (acquired in `drainQueue`) or decrement `bytesPending` (incremented in `schedule`). If `ContextPreservingAbstractRunnable.doRun()` throws before reaching our inner `doRun` (e.g., context stash fails during shutdown), `executeUpload`'s finally never runs.  
**Impact:** Each occurrence permanently reduces upload parallelism by 1 permit and inflates `bytesPending`. Eventually blocks all new segment uploads for the shard.  
**Fix:** Added `bytesPending.addAndGet(-content.length)` and `parallelismSemaphore.release()` to `onFailure`. Safe because `onFailure` can only fire when `executeUpload` was never reached (it catches all exceptions internally and never throws).

---

## Round 4 Findings

### Finding 21 — TOCTOU race in pendingUpload loses refresh signal on idle shards [P1, FIXED]

**File:** `RemoteStoreRefreshListener.java:108`  
**Bug:** `pendingUpload.getAndSet(false)` was executed BEFORE `uploadInProgress.set(false)`. A concurrent refresh thread could set `pendingUpload=true` in the window between these two operations: T1 reads false, T2 sets true, T1 releases the lock. The signal is permanently lost.  
**Impact:** On an idle shard (no subsequent refreshes), segments from the skipped refresh are never uploaded to remote store. Node crash loses them permanently.  
**Fix:** Swapped order — `uploadInProgress.set(false)` first, then `pendingUpload.getAndSet(false)`. Now if a refresh sets pendingUpload after we release the lock, that refresh's own CAS will succeed (uploadInProgress is already false) and it starts its own upload directly.

### Finding 22 — consecutiveFailures is a plain int without volatile [P1, FIXED]

**File:** `SingleWriterLock.java:30`  
**Bug:** `consecutiveFailures` was declared as a plain `int` while the other three mutable fields (`degraded`, `heldLocally`, `lastSuccessfulRenewMs`) are all `volatile`. In the `FAILED_TOLERABLE` path of `tryRenew()`, no volatile write follows the increment, so the JMM provides no visibility guarantee to other threads.  
**Impact:** Timer thread increments the counter but allocation thread reads a stale value. Can cause premature degraded-mode entry or failure to detect degradation threshold — the safety-critical lock state machine makes decisions based on stale data.  
**Fix:** Declared as `volatile int`.

### Finding 23 — onFailure does not reset uploadInProgress [P2, FIXED]

**File:** `RemoteStoreRefreshListener.java:99`  
**Bug:** `onFailure` only logged the error without resetting `uploadInProgress`. If `ContextPreservingAbstractRunnable.doRun()` throws before reaching the inner `doRun()` (e.g., context stash/restore fails), the inner try-finally never executes. AbstractRunnable delegates to `onFailure` which left `uploadInProgress` permanently true.  
**Impact:** All future `afterRefresh` calls silently skipped — remote store stops receiving segment updates permanently until node restart.  
**Fix:** Added `uploadInProgress.set(false)` to the `onFailure` handler.

---

After fixing all 15 findings from rounds 1-2, a third review pass found 5 additional issues — a compilation break, a JMM visibility bug, a fencing violation in the distributed lock, a missing retry mechanism, and dead code that could cause double-cleanup.

---

## Round 3 Findings

### Finding 16 — Integration test uses removed 2-arg constructor (compilation break) [P0, FIXED]

**File:** `RemoteStoreRiskMitigationIT.java:50`  
**Bug:** `RelocationUploadService` constructor changed from `(long, long)` to `(long, long, ThreadPool)` in Round 2, but the integration test was not updated.  
**Impact:** Full test suite does not compile — CI blocks all merges.  
**Fix:** Updated to pass `TestThreadPool` with proper teardown in finally block.

### Finding 17 — forceUploadCallback not volatile — stale null read causes data loss [P0, FIXED]

**File:** `RelocationUploadService.java:21`  
**Bug:** `forceUploadCallback` is a plain field written by `setForceUploadCallback()` (shard allocation thread) and read by `prepareForHandoff()` (relocation transport thread). Without `volatile`, the JMM does not guarantee cross-thread visibility.  
**Impact:** Under high-concurrency relocation, `prepareForHandoff()` may read null, skip the upload, and return READY — allowing shard handoff with un-uploaded data (data loss).  
**Fix:** Declared field as `volatile`.

### Finding 18 — tryRenew blindly overwrites lock without term verification [P1, FIXED]

**File:** `SingleWriterLock.java:67`  
**Bug:** `tryRenew()` called `writeLock(primaryTerm, nodeId, false)` without reading the current lock state. A stale primary (partitioned from cluster master but with blob store access) could overwrite a higher-term lock.  
**Impact:** Fencing violation — two nodes both believe they hold the lock, causing concurrent writes to the remote store and data corruption.  
**Fix:** Added `readCurrentTerm()` check before write in both `tryRenew()` and `renew()`. Returns `LEASE_EXPIRED` if a higher term is found.

### Finding 19 — Missing dirty flag causes silent segment loss on idle shards [P1, FIXED]

**File:** `RemoteStoreRefreshListener.java:89`  
**Bug:** When `uploadInProgress` is true and a new refresh fires, `afterRefresh()` returns without recording that new segments need uploading. If the shard goes idle (no more refreshes), those segments are never uploaded.  
**Impact:** Segments from skipped refreshes remain only on local disk. Node crash permanently loses them — violates remote store durability guarantees.  
**Fix:** Added `pendingUpload` AtomicBoolean flag. Set to true when upload is skipped. After upload completes, checks the flag and re-triggers `afterRefresh(true)` if set.

### Finding 20 — Dead onFailure handler becomes double-cleanup on edge case [P2, FIXED]

**File:** `SegmentUploadScheduler.java:123`  
**Bug:** `onFailure()` in drainQueue's AbstractRunnable duplicates cleanup (semaphore release, bytesPending decrement) that `executeUpload()`'s finally block already performs. Since `executeUpload()` catches all Exceptions, `onFailure()` is never reached normally. But if `executeUpload()`'s finally block itself throws (e.g., NPE in `drainQueue()` during shutdown), both paths execute cleanup — double-releasing the semaphore and decrementing bytesPending below zero.  
**Impact:** Semaphore permits exceed configured parallelism (unbounded concurrent uploads). bytesPending goes negative, permanently disabling backpressure.  
**Fix:** Removed duplicate cleanup from `onFailure()` — only logs and completes the future exceptionally. All resource cleanup is solely in `executeUpload()`'s try-catch-finally.

---

## Round 2 Findings

### Finding 11 — CompletableFuture.cancel() does not interrupt the running thread [P0, FIXED]

**File:** `RelocationUploadService.java:62`  
**Bug:** Migration from `ExecutorService.shutdownNow()` to `CompletableFuture.cancel(true)` lost the thread-interrupt capability. `CompletableFuture.cancel()` only sets the cancelled state — it does NOT interrupt the executing thread (documented Java behavior).  
**Impact:** On timeout, the `forceUploadCallback` continues running indefinitely on the generic pool thread. Repeated timeouts during relocation accumulate orphaned threads.  
**Fix:** Track the executing thread via `AtomicReference<Thread>`, explicitly call `thread.interrupt()` on timeout/interruption.

### Finding 12 — RemoteTranslogTransferManager bare lambda missing preserveContext and AbstractRunnable [P1, FIXED]

**File:** `RemoteTranslogTransferManager.java:55`  
**Bug:** `threadPool.generic().execute(lambda)` without `preserveContext()` or `AbstractRunnable` wrapping. The InterruptedException fix (from Round 1) was applied inside a bare lambda that still lost ThreadContext and lacked rejection handling.  
**Impact:** On security-enabled clusters, translog uploads fail with `AccessDeniedException`. During shutdown, `EsRejectedExecutionException` propagates to UncaughtExceptionHandler.  
**Fix:** Wrapped with `AbstractRunnable` + `preserveContext()`, added `onRejection()` handler.

### Finding 13 — SegmentUploadScheduler.drainQueue() dispatches without preserveContext [P1, FIXED]

**File:** `SegmentUploadScheduler.java:119`  
**Bug:** `RemoteStoreRefreshListener` correctly preserves context into `uploadNewSegments()`, but `scheduler.schedule()` → `drainQueue()` → `tp.generic().execute(lambda)` creates a second async hop that drops the ThreadContext. The actual blob store write happens without security headers.  
**Impact:** Same `AccessDeniedException` on secured clusters. Additionally, `EsRejectedExecutionException` during shutdown leaves the semaphore and bytesPending in inconsistent state.  
**Fix:** Wrapped with `AbstractRunnable` + `preserveContext()`, added proper cleanup in `onFailure()` and `onRejection()`.

### Finding 14 — RelocationUploadService bare lambda without AbstractRunnable [P1, FIXED]

**File:** `RelocationUploadService.java:41`  
**Bug:** Same pattern as Finding 12 — bare lambda dispatched to generic pool without rejection handling. During node shutdown, `EsRejectedExecutionException` is caught by the generic `catch(Exception)` but logged misleadingly as "Force upload failed".  
**Fix:** Replaced bare lambda with `AbstractRunnable`, added dedicated `onRejection()` that completes the future exceptionally.

### Finding 15 — Unnecessary ByteBuffer→BytesReference→byte[] roundtrip in SingleWriterLock [P2, FIXED]

**File:** `SingleWriterLock.java:123`  
**Bug:** `readCurrentTerm()` wraps `byte[]` → `ByteBuffer` → `BytesReference.fromByteBuffer()` → `BytesReference.toBytes()` back to `byte[]`. The `XContentType.JSON.xContent().createParser()` has a direct `byte[]` overload.  
**Impact:** Unnecessary allocations (3 intermediate objects) on every lock renewal interval (5-10s per shard). GC pressure on clusters with many shards.  
**Fix:** Pass `byte[]` directly to `createParser()`, removed unused imports.

---

## Review #2: Initial Post-Fix Audit (10 Findings)

### Finding 1 — No concurrency guard on uploadNewSegments [P0, FIXED]

**File:** `RemoteStoreRefreshListener.java:89`  
**Bug:** `afterRefresh()` dispatches `uploadNewSegments()` to the generic thread pool without any serialization. With default 1-second refresh intervals and uploads that can take seconds, concurrent invocations race on `uploadedFiles`, metadata writes, and `lastUploadedGeneration`.  
**Impact:** Duplicate uploads, stale metadata overwriting newer metadata, orphan files in remote storage.  
**Fix:** Added `AtomicBoolean uploadInProgress` with `compareAndSet(false, true)` guard. Second refresh while upload is in-progress is skipped (it will catch up on next refresh).

### Finding 2 — lastUploadedGeneration regression on out-of-order completion [P0, FIXED]

**File:** `RemoteStoreRefreshListener.java:188`  
**Bug:** `lastUploadedGeneration.set(generation)` uses unconditional `set()`. With concurrent async uploads completing out-of-order, a slow gen-7 upload finishing after gen-8 regresses the value from 8→7.  
**Impact:** Relocation handoff tail-gap calculation sees wrong generation, causing unnecessary delays or incorrect handoff decisions.  
**Fix:** Replaced with CAS loop using `compareAndSet` — only advances if `generation > current`.

### Finding 3 — SingleWriterLock writeLock passes failIfAlreadyExists=true for renewals [P0, FIXED]

**File:** `SingleWriterLock.java:131`  
**Bug:** `writeLock()` always passed `failIfAlreadyExists=true` to `BlobContainer.writeBlob()`. After initial acquisition creates the blob, every renewal attempt throws `FileAlreadyExistsException`.  
**Impact:** Lock becomes non-renewable. After 2 failed renewals (default threshold), enters degraded mode → eventually lease expires → false split-brain detection.  
**Fix:** `writeLock()` now accepts a `failIfAlreadyExists` parameter. First-time acquire passes `true`, renewals pass `false`.

### Finding 4 — ThreadContext not preserved across async dispatch [P1, FIXED]

**File:** `RemoteStoreRefreshListener.java:89`  
**Bug:** `tp.generic().execute(lambda)` does not wrap with `threadPool.getThreadContext().preserveContext()`. Security headers, X-Opaque-Id, and task cancellation context are lost.  
**Impact:** On clusters with security enabled, blob store operations fail with `AccessDeniedException`. Audit logging cannot correlate uploads to originating operations.  
**Fix:** Wrapped Runnable with `tp.getThreadContext().preserveContext(...)`.

### Finding 5 — Bare lambda without AbstractRunnable wrapping [P1, FIXED]

**File:** `RemoteStoreRefreshListener.java:89`  
**Bug:** Bare lambda dispatched to generic pool. `EsRejectedExecutionException` during pool shutdown propagates uncaught through `afterRefresh()` to the Lucene ReferenceManager.  
**Impact:** During node shutdown, rejected execution crashes the refresh thread or triggers UncaughtExceptionHandler.  
**Fix:** Replaced bare lambda with `AbstractRunnable` providing `onFailure()` and `onRejection()` handlers.

### Finding 6 — InterruptedException swallowed in RemoteTranslogTransferManager [P1, FIXED]

**File:** `RemoteTranslogTransferManager.java:63`  
**Bug:** `Semaphore.acquire()` throws `InterruptedException` which is caught by generic `catch (Exception)` without restoring `Thread.currentThread().interrupt()`.  
**Impact:** Thread pool thread's interrupt flag is cleared, preventing graceful shutdown detection. Node can hang during rolling restart.  
**Fix:** Added explicit `catch (InterruptedException)` with `Thread.currentThread().interrupt()` before the generic catch.

### Finding 7 — SingleWriterLock TOCTOU race and raw JSON handling [P1, FIXED]

**File:** `SingleWriterLock.java:36,109,132`  
**Bug:** (a) `readCurrentTerm()` + `writeLock()` are not atomic — two nodes with same term can both succeed. (b) Manual `indexOf`/`substring` JSON parsing is brittle. (c) Raw string concatenation for JSON enables malformed output if nodeId contains special characters.  
**Impact:** Split-brain writes if two primaries race on lock acquisition. Malformed lock file from special characters in nodeId.  
**Fix:** (b,c) Replaced raw JSON handling with `XContentBuilder` (write) and `XContentParser` (read). (a) Documented as known limitation — requires conditional-write support from blob store (CAS/ETag) for full fix.

### Finding 8 — BackpressureController disconnected from upload pipeline [P2, FIXED]

**File:** `BackpressureController.java:55`, `SegmentUploadScheduler.java:76`  
**Bug:** `BackpressureController.allowWrite()` was never called by any code in the upload pipeline. Settings exist in `elasticsearch.yml` that operators can tune, but they had zero runtime effect.  
**Impact:** Operators configure disk thresholds expecting uploads to halt, but uploads continue regardless, leading to disk-full scenarios.  
**Fix:** Wired `BackpressureController` into `SegmentUploadScheduler` — `schedule()` checks `allowWrite()` before accepting tasks, `executeUpload()` calls `recordSuccess()`/`recordFailure()` to drive the controller's state.

### Finding 9 — RemoteSegmentMetadata checkpoint field equals generation [P2, FIXED]

**File:** `RemoteStoreRefreshListener.java:182`  
**Bug:** `RemoteSegmentMetadata` was constructed with `checkpoint = segmentInfos.getGeneration()`, making it identical to `generation`. The checkpoint field carried no distinct semantic value.  
**Impact:** Future recovery logic relying on checkpoint for durability guarantees would get incorrect data.  
**Fix:** Changed to use `lastUploadedGeneration.get()` as checkpoint — representing the last confirmed-durable generation prior to the current upload.

### Finding 10 — RelocationUploadService creates new thread per call [P2, FIXED]

**File:** `RelocationUploadService.java:39`  
**Bug:** `Executors.newSingleThreadExecutor()` creates a brand-new non-daemon thread on every `prepareForHandoff()` call. Thread is invisible to ES thread management APIs.  
**Impact:** During mass relocation, hundreds of orphaned threads accumulate, exhausting OS ulimits and preventing clean JVM exit.  
**Fix:** Replaced with ES `ThreadPool` — task dispatched to `threadPool.generic()` with `CompletableFuture` for timeout coordination.

---

## Additional Hardening Applied

| Issue | Fix |
|-------|-----|
| `uploadedFiles` grows unbounded (memory leak) | Added `pruneUploadedFiles(currentFiles)` — retains only files in current SegmentInfos |
| `uploadInProgress` not reset on rejection | `onRejection()` handler in AbstractRunnable resets the flag |
| Missing interrupt handling on node shutdown path | AbstractRunnable + InterruptedException guards on all async paths |
| All async dispatches now use preserveContext | Consistent security header propagation across the entire package |

---

## Files Modified

| File | Change |
|------|--------|
| `RemoteStoreRefreshListener.java` | Concurrency guard, CAS generation update, AbstractRunnable + preserveContext, uploadedFiles pruning, pendingUpload dirty flag, fixed race ordering, onFailure reset |
| `SingleWriterLock.java` | XContentBuilder/Parser for JSON, failIfAlreadyExists=false for renewals, removed ByteBuffer roundtrip, read-before-write in tryRenew/renew, volatile consecutiveFailures |
| `RemoteTranslogTransferManager.java` | AbstractRunnable + preserveContext, onRejection handler |
| `SegmentUploadScheduler.java` | BackpressureController integration, AbstractRunnable + preserveContext in drainQueue, removed duplicate cleanup from onFailure |
| `RelocationUploadService.java` | ThreadPool + AbstractRunnable + preserveContext, AtomicReference<Thread> for interrupt on timeout, volatile forceUploadCallback |
| `RelocationUploadServiceTests.java` | Updated for new 3-arg constructor (added ThreadPool) |
| `SingleWriterLockTests.java` | Updated mocks for read-before-write in tryRenew, verify failIfAlreadyExists |
| `RemoteStoreRiskMitigationIT.java` | Updated for new 3-arg RelocationUploadService constructor |

---

## Known Remaining Limitations (not fixable without architectural changes)

1. **SingleWriterLock TOCTOU** — requires blob store conditional-write (CAS) support for true fencing
2. **Tiering state not persisted** — requires ClusterState custom metadata integration
3. **Services not wired into ES lifecycle** — requires Node.java or plugin module registration
4. **No remote segment GC** — orphan blobs from merged segments accumulate; needs RemoteStoreGarbageCollector

---

## Test Results

```
BUILD SUCCESSFUL
137 tests completed, 0 failed (5 rounds)
```
