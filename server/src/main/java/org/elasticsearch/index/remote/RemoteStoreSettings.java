package org.elasticsearch.index.remote;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.core.TimeValue;

import java.util.Arrays;
import java.util.List;

public final class RemoteStoreSettings {

    // --- Index-level settings ---

    public static final Setting<Boolean> REMOTE_STORE_ENABLED = Setting.boolSetting(
        "index.remote_store.enabled", false, Setting.Property.IndexScope, Setting.Property.Final);

    public static final Setting<String> REMOTE_STORE_REPOSITORY = Setting.simpleString(
        "index.remote_store.repository", "", Setting.Property.IndexScope, Setting.Property.Final);

    public static final Setting<TimeValue> TRANSLOG_UPLOAD_INTERVAL = Setting.timeSetting(
        "index.translog.remote.upload.interval",
        TimeValue.timeValueSeconds(1),
        TimeValue.timeValueMillis(100),
        Setting.Property.IndexScope, Setting.Property.Dynamic);

    public static final Setting<ByteSizeValue> TRANSLOG_UPLOAD_BATCH_SIZE = Setting.byteSizeSetting(
        "index.translog.remote.upload.batch_size",
        new ByteSizeValue(4, ByteSizeUnit.MB),
        Setting.Property.IndexScope, Setting.Property.Dynamic);

    public static final Setting<Integer> TRANSLOG_PARALLEL_UPLOAD = Setting.intSetting(
        "index.translog.remote.parallel_upload", 8, 1, 32,
        Setting.Property.IndexScope, Setting.Property.Dynamic);

    public static final Setting<TimeValue> RELOCATION_HANDOFF_TIMEOUT = Setting.timeSetting(
        "index.remote_store.relocation.handoff_timeout",
        TimeValue.timeValueSeconds(60),
        TimeValue.timeValueSeconds(10),
        Setting.Property.IndexScope, Setting.Property.Dynamic);

    public static final Setting<ByteSizeValue> RELOCATION_MAX_TAIL_AT_HANDOFF = Setting.byteSizeSetting(
        "index.remote_store.relocation.max_tail_at_handoff",
        new ByteSizeValue(256, ByteSizeUnit.MB),
        Setting.Property.IndexScope, Setting.Property.Dynamic);

    // --- Node-level settings ---

    public static final Setting<Integer> SEGMENT_UPLOAD_PARALLELISM = Setting.intSetting(
        "node.remote_store.segment.upload.parallelism", 8, 1, 32,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<ByteSizeValue> SEGMENT_UPLOAD_MAX_BYTES_IN_FLIGHT = Setting.byteSizeSetting(
        "node.remote_store.segment.upload.max_bytes_in_flight",
        new ByteSizeValue(256, ByteSizeUnit.MB),
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Double> BACKPRESSURE_WARN_THRESHOLD = Setting.doubleSetting(
        "node.remote_store.backpressure.local_disk_threshold_warn", 0.70, 0.0, 1.0,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Double> BACKPRESSURE_BLOCK_THRESHOLD = Setting.doubleSetting(
        "node.remote_store.backpressure.local_disk_threshold_block", 0.90, 0.0, 1.0,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Integer> FAILURE_CONSECUTIVE_THRESHOLD = Setting.intSetting(
        "node.remote_store.failure.consecutive_threshold", 5, 1,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    // --- Cluster-level settings ---

    public static final Setting<Boolean> SINGLE_WRITER_ENABLED = Setting.boolSetting(
        "cluster.remote_store.single_writer.enabled", true,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<TimeValue> SINGLE_WRITER_HEARTBEAT_INTERVAL = Setting.timeSetting(
        "cluster.remote_store.single_writer.heartbeat_interval",
        TimeValue.timeValueSeconds(10),
        TimeValue.timeValueSeconds(1),
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<TimeValue> SINGLE_WRITER_LEASE_TOLERANCE = Setting.timeSetting(
        "cluster.remote_store.single_writer.lease_tolerance",
        TimeValue.timeValueSeconds(30),
        TimeValue.timeValueSeconds(5),
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Integer> SINGLE_WRITER_DEGRADE_AFTER_FAILURES = Setting.intSetting(
        "cluster.remote_store.single_writer.degrade_after_failures", 2, 1, 10,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<TimeValue> SINGLE_WRITER_LOCK_ATTEMPT_TIMEOUT = Setting.timeSetting(
        "cluster.remote_store.single_writer.lock_attempt_timeout",
        TimeValue.timeValueSeconds(5),
        TimeValue.timeValueSeconds(1),
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Boolean> SINGLE_WRITER_FAST_DEGRADE = Setting.boolSetting(
        "cluster.remote_store.single_writer.fast_degrade_on_first_failure", true,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Boolean> RELOCATION_FORCE_UPLOAD = Setting.boolSetting(
        "cluster.routing.allocation.relocation.force_upload_before_handoff", true,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Boolean> FAST_FAILOVER_ENABLED = Setting.boolSetting(
        "cluster.search.fast_failover.enabled", true,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<TimeValue> FAST_FAILOVER_KNOWN_DEAD_TIMEOUT = Setting.timeSetting(
        "cluster.search.fast_failover.known_dead_timeout",
        TimeValue.timeValueSeconds(2),
        TimeValue.timeValueMillis(500),
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Integer> PRIMARY_PROMOTION_CONCURRENT = Setting.intSetting(
        "cluster.routing.allocation.primary_promotion.concurrent", 20, 1, 100,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<TimeValue> PRIMARY_PROMOTION_BATCH_INTERVAL = Setting.timeSetting(
        "cluster.routing.allocation.primary_promotion.batch_interval",
        TimeValue.timeValueSeconds(1),
        TimeValue.timeValueMillis(100),
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Boolean> PRIMARY_PROMOTION_PRESELECT = Setting.boolSetting(
        "cluster.routing.allocation.primary_promotion.preselect", true,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Integer> PRIMARY_SHARDS_PER_NODE = Setting.intSetting(
        "cluster.routing.allocation.primary_shards_per_node", 20, -1, 1000,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    private RemoteStoreSettings() {}

    public static List<Setting<?>> getIndexSettings() {
        return Arrays.asList(
            REMOTE_STORE_ENABLED,
            REMOTE_STORE_REPOSITORY,
            TRANSLOG_UPLOAD_INTERVAL,
            TRANSLOG_UPLOAD_BATCH_SIZE,
            TRANSLOG_PARALLEL_UPLOAD,
            RELOCATION_HANDOFF_TIMEOUT,
            RELOCATION_MAX_TAIL_AT_HANDOFF
        );
    }

    public static List<Setting<?>> getNodeSettings() {
        return Arrays.asList(
            SEGMENT_UPLOAD_PARALLELISM,
            SEGMENT_UPLOAD_MAX_BYTES_IN_FLIGHT,
            BACKPRESSURE_WARN_THRESHOLD,
            BACKPRESSURE_BLOCK_THRESHOLD,
            FAILURE_CONSECUTIVE_THRESHOLD
        );
    }

    public static List<Setting<?>> getClusterSettings() {
        return Arrays.asList(
            SINGLE_WRITER_ENABLED,
            SINGLE_WRITER_HEARTBEAT_INTERVAL,
            SINGLE_WRITER_LEASE_TOLERANCE,
            SINGLE_WRITER_DEGRADE_AFTER_FAILURES,
            SINGLE_WRITER_LOCK_ATTEMPT_TIMEOUT,
            SINGLE_WRITER_FAST_DEGRADE,
            RELOCATION_FORCE_UPLOAD,
            FAST_FAILOVER_ENABLED,
            FAST_FAILOVER_KNOWN_DEAD_TIMEOUT,
            PRIMARY_PROMOTION_CONCURRENT,
            PRIMARY_PROMOTION_BATCH_INTERVAL,
            PRIMARY_PROMOTION_PRESELECT,
            PRIMARY_SHARDS_PER_NODE
        );
    }
}
