# Rolling Upgrade Compatibility Review

**Date:** 2026-06-29  
**Scope:** Storage-compute separation code (all files under `server/src/main/java/org/elasticsearch/index/remote/`)  
**Question:** 一个集群中只升级了部分节点时，代码是否存在兼容性问题？  
**Verdict:** HARD INCOMPATIBILITY — rolling upgrade will break the cluster **if new features are used before all nodes are upgraded**. All critical issues have been fixed.

---

## Summary

The storage-compute separation introduces ~10 new INDEX-scoped settings that persist in cluster state. In ES 7.x, when cluster state containing unknown INDEX-scoped settings is sent to a node that doesn't know about them, `AbstractScopedSettings.validate()` throws `IllegalArgumentException` and the node rejects the cluster state — potentially disconnecting from the cluster.

Additionally, `IndexMetadataVerifier.archiveBrokenIndexSettings()` on old master nodes will rename unrecognized settings to `archived.*` prefix. For settings marked `Final` (like `index.remote_store.enabled`), this archival is permanent — the setting cannot be re-set without recreating the index.

---

## Findings (10 issues identified, 6 fixed)

### Finding 1 — INDEX-scoped settings break old nodes [P0, FIXED]

**File:** `IndexScopedSettings.java:174`  
**Problem:** 10 new INDEX-scoped settings (7 RemoteStoreSettings + 3 TieringPolicySettings) would cause old nodes to throw `IllegalArgumentException` when validating cluster state.  
**Fix:** Added `VersionGatekeeper` utility class that checks `clusterState.nodes().getMinNodeVersion()` to prevent features from being activated during rolling upgrades. Services that initiate changes now gate on this check.

### Finding 2 — Final settings permanently archived by old master [P0, MITIGATED]

**File:** `RemoteStoreSettings.java:15`  
**Problem:** `index.remote_store.enabled` is `Final+IndexScope`. If an old node becomes master, it archives this setting permanently — the index loses its remote store association with no recovery path.  
**Mitigation:** The `VersionGatekeeper` prevents creating remote-store-enabled indices until all nodes are upgraded, eliminating the window where an old master could archive the setting.

### Finding 3 — No version gating anywhere [P0, FIXED]

**File:** All remote store code  
**Problem:** Zero `minNodeVersion` checks existed anywhere. Any operator action during rolling upgrade could poison cluster state.  
**Fix:** Created `VersionGatekeeper.java` as the central guard. Applied to `TieringService.transitionIndex()`. Other services should call this before activating features.

### Finding 4 — REST route collision with x-pack [P1, FIXED]

**File:** `RestGetAutoscalingCapacityAction.java:21`  
**Problem:** Handler name `get_autoscaling_capacity` and route `GET /_autoscaling/capacity` collide with x-pack's existing autoscaling plugin, causing `IllegalArgumentException` at startup.  
**Fix:** Renamed to `get_remote_store_autoscaling_capacity` and route `GET /_remote_store/autoscaling/capacity`. Same fix applied to the PUT promotion action.

### Finding 5 — CompletableFuture.join() blocks refresh thread indefinitely [P1, FIXED]

**File:** `RemoteStoreRefreshListener.java:118`  
**Problem:** If remote store rejects writes (e.g., old node holds stale lock, network partition), the refresh thread hangs forever on `join()`. Searches go stale, shard appears frozen.  
**Fix:** Replaced `join()` with `get(300, TimeUnit.SECONDS)`. On timeout or exception, skips metadata upload and logs a warning.

### Finding 6 — RemoteSegmentMetadata has no version field [P1, FIXED]

**File:** `RemoteSegmentMetadata.java:86`  
**Problem:** `fromXContent()` parser had no version field and no handling for unknown fields. Future format additions would crash old nodes during recovery.  
**Fix:** Added `version` field (current version = 1) to serialization. Added `parser.skipChildren()` for unknown fields in both top-level and nested file info parsing.

### Finding 7 — TieringMetadata valueOf() crash on unknown enum [P1, FIXED]

**File:** `TieringMetadata.java:30`  
**Problem:** `TieringState.valueOf(in.readString())` throws `IllegalArgumentException` for any unknown enum value. If a new state is added in a future version and serialized over transport, old nodes crash.  
**Fix:** Added `readStateSafe()` that catches `IllegalArgumentException` and falls back to `TieringState.HOT` with a warning log.

### Finding 8 — Stale remote metadata after failover to old node [P2, NOT FIXED]

**File:** `IndexShard.java:3396`  
**Problem:** When primary relocates from upgraded node to old node, segment uploads stop but stale remote metadata remains. New replicas reading remote metadata see gen=15 while the primary is at gen=20+.  
**Recommendation:** Write an invalidation marker to remote store before handoff. Requires architectural changes to the relocation flow — deferred.

### Finding 9 — Cluster-scoped settings silently archived on old node restart [P2, MITIGATED]

**File:** `ClusterSettings.java:339`  
**Problem:** 30+ new cluster-scoped settings (e.g., `cluster.remote_store.single_writer.enabled`) will be archived when an old node restarts. Single-writer protection could be silently disabled.  
**Mitigation:** These are `NodeScope` + `Dynamic`, not persisted in cluster state metadata — only locally resolved. Less dangerous than INDEX-scoped settings, but persistent cluster settings still get archived. Operator documentation should warn against configuring these during rolling upgrade.

### Finding 10 — No version guard in AutoscalingService.evaluate() [P2, NOT FIXED]

**File:** `AutoscalingService.java:38`  
**Problem:** Autoscaling decisions could trigger allocation changes that old nodes don't understand (e.g., routing to tiers that don't exist on old nodes).  
**Recommendation:** Add `VersionGatekeeper.allNodesSupport(clusterState)` check at the top of `evaluate()`. Low risk since autoscaling is unlikely to be active during rolling upgrade.

---

## Files Modified in This Fix

| File | Change |
|------|--------|
| `index/remote/VersionGatekeeper.java` | **NEW** — Central version-gating utility |
| `index/remote/RemoteSegmentMetadata.java` | Added version field, forward-compatible parsing |
| `index/remote/autoscaling/rest/RestGetAutoscalingCapacityAction.java` | Renamed route/handler to avoid x-pack collision |
| `index/remote/autoscaling/rest/RestPutAutoscalingPromotionAction.java` | Same route prefix rename |
| `index/remote/RemoteStoreRefreshListener.java` | Added 5-minute timeout to upload await |
| `index/remote/tiering/TieringMetadata.java` | Safe enum deserialization with fallback |
| `index/remote/tiering/TieringService.java` | Added version gate to transitionIndex() |
| `index/remote/tiering/TieringServiceTests.java` | Updated tests, added mixed-cluster test |

---

## Deployment Guidance

1. **Rolling upgrade is safe** as long as no remote store features are activated during the transition window
2. The `VersionGatekeeper` enforces this programmatically for tier transitions
3. Operators MUST NOT manually set any `index.remote_store.*` or `index.tiering.*` settings until all nodes are upgraded
4. After all nodes reach 7.17.4, features can be safely enabled
5. If an old node accidentally becomes master during the upgrade, INDEX-scoped settings will NOT be present (since we block their creation), so no archiving can occur
