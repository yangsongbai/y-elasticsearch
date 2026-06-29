/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.index.remote.cache;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.core.TimeValue;

import java.util.Arrays;
import java.util.List;

public final class FileCacheSettings {

    public static final Setting<ByteSizeValue> CACHE_SIZE = Setting.byteSizeSetting(
        "node.filecache.size", new ByteSizeValue(200, ByteSizeUnit.GB),
        Setting.Property.NodeScope);

    public static final Setting<ByteSizeValue> REGION_SIZE = Setting.byteSizeSetting(
        "node.filecache.region_size", new ByteSizeValue(16, ByteSizeUnit.MB),
        Setting.Property.NodeScope);

    public static final Setting<String> EVICTION_POLICY = Setting.simpleString(
        "node.filecache.eviction_policy", "LFU_DECAY",
        Setting.Property.NodeScope);

    public static final Setting<TimeValue> DECAY_INTERVAL = Setting.timeSetting(
        "node.filecache.decay.interval", TimeValue.timeValueMinutes(1),
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Double> DECAY_FACTOR = Setting.doubleSetting(
        "node.filecache.decay.factor", 0.95, 0.0, 1.0,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    private FileCacheSettings() {}

    public static List<Setting<?>> getSettings() {
        return Arrays.asList(CACHE_SIZE, REGION_SIZE, EVICTION_POLICY, DECAY_INTERVAL, DECAY_FACTOR);
    }
}
