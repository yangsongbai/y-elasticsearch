package org.elasticsearch.index.remote.prefetch;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;

import java.util.Arrays;
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
        return Arrays.asList(ENABLED, RATE_LIMIT, CONCURRENCY, CACHE_THRESHOLD);
    }
}
