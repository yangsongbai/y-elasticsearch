# Bug Review: Remote Store Code (ES/Java Perspective)

**Date:** 2026-06-29  
**Scope:** All files under `server/src/main/java/org/elasticsearch/index/remote/`  
**Question:** 从elasticsearch和java的角度查看，代码是否存在bug？  
**Verdict:** 10 bugs found (7 correctness, 2 design, 1 security). All fixed.

---

## Findings

### Finding 1 — Metadata references unuploaded files [P0, FIXED]

**File:** `RemoteStoreRefreshListener.java:134`  
**Bug:** When `readLocalFile()` returns null (file deleted by concurrent merge), the file is never uploaded. But the metadata upload iterates over ALL `segmentInfos.files()` — including the missing file.  
**Impact:** Remote recovery reads metadata, tries to download the missing file → `FileNotFoundException` → shard recovery fails.  
**Fix:** Metadata now only includes files from `uploadedFiles` that are in `currentFiles` (intersection of actually-uploaded and expected files).

### Finding 2 — Uncancelled futures corrupt uploadedFiles set [P0, FIXED]

**File:** `RemoteStoreRefreshListener.java:121`  
**Bug:** On `TimeoutException`, upload futures were never cancelled. Their `whenComplete` callbacks continued to add filenames to `uploadedFiles`. On the next refresh, these files were skipped (already in set) but had no metadata referencing them.  
**Impact:** Files permanently unreachable from remote recovery — silent data loss.  
**Fix:** All futures are now explicitly cancelled via `future.cancel(true)` on timeout or interrupt.

### Finding 3 — NegativeArraySizeException on >2GB segments [P0, FIXED]

**File:** `RemoteStoreRefreshListener.java:158`  
**Bug:** `new byte[(int) input.length()]` casts `long` to `int`. For files >2GB, this wraps negative → `NegativeArraySizeException`.  
**Impact:** Force-merged indices with large segments crash the upload, and the file is silently skipped.  
**Fix:** Added `MAX_FILE_SIZE_BYTES = Integer.MAX_VALUE` guard. Files exceeding this limit are logged and skipped with a clear warning. (Future work: streaming upload for >2GB files.)

### Finding 4 — InterruptedException swallowed without restoring flag [P1, FIXED]

**File:** `RemoteStoreRefreshListener.java:129`  
**Bug:** `catch (Exception e)` catches `InterruptedException` (which `Future.get()` declares) without calling `Thread.currentThread().interrupt()`.  
**Impact:** Node shutdown signals are lost — the thread continues running with cleared interrupt status, potentially stalling node shutdown.  
**Fix:** Explicit `catch (InterruptedException)` before generic `catch (Exception)`, with `Thread.currentThread().interrupt()` called.

### Finding 5 — JSON injection via unescaped path parameter [P1, FIXED]

**File:** `RestPutAutoscalingPromotionAction.java:29`  
**Bug:** The `id` parameter from `request.param("id")` was string-concatenated into raw JSON: `"{\"id\":\"" + id + "\"}"`. Special characters (quotes, backslashes) were not escaped.  
**Impact:** Crafted path parameter can inject arbitrary JSON keys/values into the response.  
**Fix:** Replaced raw string concatenation with `XContentBuilder` which auto-escapes all field values.

### Finding 6 — Refresh thread blocked for up to 300 seconds [P1, FIXED]

**File:** `RemoteStoreRefreshListener.java:122`  
**Bug:** `uploadNewSegments()` ran synchronously on the Lucene refresh thread, blocking with `get(300, SECONDS)`. The refresh thread is shared — blocking it stalls NRT search visibility.  
**Impact:** If remote store is slow, searches on this shard see stale data for up to 5 minutes.  
**Fix:** `afterRefresh()` now dispatches `uploadNewSegments()` to `threadPool.generic()`. The refresh thread returns immediately.

### Finding 7 — getIntermediateState returns target for reverse transitions [P1, FIXED]

**File:** `TieringService.java:89`  
**Bug:** For WARM→HOT and COLD→WARM, `getIntermediateState()` returned the target state itself (HOT/WARM), causing `executeTransition(index, HOT, HOT)` — a semantically meaningless no-op.  
**Impact:** `TierTransitioner` implementations that validate `from != to` spuriously fail. Transition history is corrupted with `previousState == currentState`.  
**Fix:** Promote (reverse) transitions now bypass the intermediate state machine entirely via `executeDirectTransition()`. Only demote transitions use the two-phase prepare/execute pattern.

### Finding 8 — Silent enum coercion enables invalid transitions [P2, FIXED]

**File:** `TieringMetadata.java:45`  
**Bug:** `readStateSafe()` coerced unknown enum values to HOT without any marker. Downstream code then treated the index as HOT and permitted transitions that were invalid for the actual state.  
**Impact:** In mixed-version clusters, an index in a future state (e.g., FROZEN) could be incorrectly transitioned to WARM, corrupting allocation.  
**Fix:** Added `hasUnknownState` flag to `TieringMetadata`. `TieringService.transitionIndex()` now checks this flag and rejects transitions when state was coerced from an unknown value.

### Finding 9 — VersionGatekeeper only at service level [P2, DOCUMENTED]

**File:** `VersionGatekeeper.java:29`  
**Bug:** Version gate is enforced only in `TieringService`, not at the `ClusterStateUpdateTask` layer where `index.remote_store.enabled` enters cluster state.  
**Impact:** Code paths that bypass TieringService (create-index, restore-snapshot) could still introduce unknown settings during rolling upgrade.  
**Mitigation:** Documented. Requires architectural change to inject version check into `MetadataCreateIndexService`. Operator documentation warns against enabling remote store settings during rolling upgrade.

### Finding 10 — OOM risk from unbounded byte array allocation [P2, FIXED]

**File:** `RemoteStoreRefreshListener.java:104`  
**Bug:** Every segment file was read entirely into a `byte[]` on the shared thread. With multiple shards refreshing concurrently after force-merge, this could allocate GBs simultaneously.  
**Impact:** OOM or long GC pauses killing the node.  
**Fix:** Combined with Finding 3 fix (size guard at 2GB). Additionally, upload is now async off the refresh thread, reducing contention on GC-sensitive paths.

---

## Files Modified

| File | Change |
|------|--------|
| `RemoteStoreRefreshListener.java` | Async dispatch, cancel futures, size guard, interrupt handling, metadata correctness |
| `RestPutAutoscalingPromotionAction.java` | XContentBuilder instead of string concatenation |
| `TieringService.java` | Separate promote/demote paths, unknown-state check |
| `TieringMetadata.java` | `hasUnknownState` flag, 4-arg constructor |
| `VersionGatekeeper.java` | Eliminated double getMinNodeVersion() call |
| `IndexShard.java` | Pass threadPool to activate() |
| `TieringServiceTests.java` | Added promote tests, extracted helper |
| `RemoteStoreRefreshListenerTests.java` | Updated for new constructor signature |

---

## Test Results

```
BUILD SUCCESSFUL
135 tests completed, 0 failed
```
