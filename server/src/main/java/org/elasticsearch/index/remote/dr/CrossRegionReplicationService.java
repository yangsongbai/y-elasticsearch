package org.elasticsearch.index.remote.dr;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CrossRegionReplicationService {

    private final Map<String, ReplicationState> states = new ConcurrentHashMap<>();

    public void startReplication(String indexName, String sourceRegion, String targetRegion) {
        states.put(indexName, new ReplicationState(sourceRegion, targetRegion, 0L, 0L, true));
    }

    public void stopReplication(String indexName) {
        states.computeIfPresent(indexName, (k, current) -> new ReplicationState(
            current.sourceRegion(), current.targetRegion(),
            current.sourceCheckpoint(), current.targetCheckpoint(), false));
    }

    public void updateSourceCheckpoint(String indexName, long checkpoint) {
        states.computeIfPresent(indexName, (k, current) -> new ReplicationState(
            current.sourceRegion(), current.targetRegion(),
            checkpoint, current.targetCheckpoint(), current.isActive()));
    }

    public void updateTargetCheckpoint(String indexName, long checkpoint) {
        states.computeIfPresent(indexName, (k, current) -> new ReplicationState(
            current.sourceRegion(), current.targetRegion(),
            current.sourceCheckpoint(), checkpoint, current.isActive()));
    }

    public ReplicationState getReplicationState(String indexName) {
        return states.get(indexName);
    }

    public long getReplicationLag(String indexName) {
        ReplicationState state = states.get(indexName);
        if (state == null) return -1;
        return state.sourceCheckpoint() - state.targetCheckpoint();
    }

    public static class ReplicationState {
        private final String sourceRegion;
        private final String targetRegion;
        private final long sourceCheckpoint;
        private final long targetCheckpoint;
        private final boolean active;

        public ReplicationState(String sourceRegion, String targetRegion,
                                long sourceCheckpoint, long targetCheckpoint, boolean active) {
            this.sourceRegion = sourceRegion;
            this.targetRegion = targetRegion;
            this.sourceCheckpoint = sourceCheckpoint;
            this.targetCheckpoint = targetCheckpoint;
            this.active = active;
        }

        public String sourceRegion() { return sourceRegion; }
        public String targetRegion() { return targetRegion; }
        public long sourceCheckpoint() { return sourceCheckpoint; }
        public long targetCheckpoint() { return targetCheckpoint; }
        public boolean isActive() { return active; }
    }
}
