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

    public void testSingleWriterLeaseToleranceSettings() {
        Settings settings = Settings.builder()
            .put("cluster.remote_store.single_writer.lease_tolerance", "45s")
            .put("cluster.remote_store.single_writer.degrade_after_failures", 3)
            .put("cluster.remote_store.single_writer.lock_attempt_timeout", "8s")
            .put("cluster.remote_store.single_writer.fast_degrade_on_first_failure", false)
            .build();
        assertEquals(45000, RemoteStoreSettings.SINGLE_WRITER_LEASE_TOLERANCE.get(settings).millis());
        assertEquals(3, RemoteStoreSettings.SINGLE_WRITER_DEGRADE_AFTER_FAILURES.get(settings).intValue());
        assertEquals(8000, RemoteStoreSettings.SINGLE_WRITER_LOCK_ATTEMPT_TIMEOUT.get(settings).millis());
        assertFalse(RemoteStoreSettings.SINGLE_WRITER_FAST_DEGRADE.get(settings));
    }

    public void testFastFailoverSettings() {
        Settings settings = Settings.builder()
            .put("cluster.search.fast_failover.enabled", true)
            .put("cluster.search.fast_failover.known_dead_timeout", "3s")
            .build();
        assertTrue(RemoteStoreSettings.FAST_FAILOVER_ENABLED.get(settings));
        assertEquals(3000, RemoteStoreSettings.FAST_FAILOVER_KNOWN_DEAD_TIMEOUT.get(settings).millis());
    }

    public void testPrimaryPromotionSettings() {
        Settings settings = Settings.builder()
            .put("cluster.routing.allocation.primary_promotion.concurrent", 10)
            .put("cluster.routing.allocation.primary_promotion.preselect", true)
            .put("cluster.routing.allocation.primary_shards_per_node", 30)
            .build();
        assertEquals(10, RemoteStoreSettings.PRIMARY_PROMOTION_CONCURRENT.get(settings).intValue());
        assertTrue(RemoteStoreSettings.PRIMARY_PROMOTION_PRESELECT.get(settings));
        assertEquals(30, RemoteStoreSettings.PRIMARY_SHARDS_PER_NODE.get(settings).intValue());
    }

    public void testRelocationSettings() {
        Settings settings = Settings.builder()
            .put("cluster.routing.allocation.relocation.force_upload_before_handoff", true)
            .put("index.remote_store.relocation.handoff_timeout", "90s")
            .put("index.remote_store.relocation.max_tail_at_handoff", "512mb")
            .build();
        assertTrue(RemoteStoreSettings.RELOCATION_FORCE_UPLOAD.get(settings));
        assertEquals(90000, RemoteStoreSettings.RELOCATION_HANDOFF_TIMEOUT.get(settings).millis());
        assertEquals("512mb", RemoteStoreSettings.RELOCATION_MAX_TAIL_AT_HANDOFF.get(settings).toString());
    }
}
