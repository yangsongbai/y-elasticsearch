/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.core.TimeValue;

import java.util.Arrays;
import java.util.List;

/**
 * Settings for tiering policy that control when indices transition between tiers
 * based on age thresholds.
 */
public final class TieringPolicySettings {

    public static final Setting<TimeValue> WARM_AFTER = Setting.timeSetting(
        "index.tiering.warm_after",
        TimeValue.timeValueMillis(-1),
        TimeValue.timeValueMillis(-1),
        Setting.Property.IndexScope,
        Setting.Property.Dynamic
    );

    public static final Setting<TimeValue> COLD_AFTER = Setting.timeSetting(
        "index.tiering.cold_after",
        TimeValue.timeValueMillis(-1),
        TimeValue.timeValueMillis(-1),
        Setting.Property.IndexScope,
        Setting.Property.Dynamic
    );

    public static final Setting<TimeValue> DELETE_AFTER = Setting.timeSetting(
        "index.tiering.delete_after",
        TimeValue.timeValueMillis(-1),
        TimeValue.timeValueMillis(-1),
        Setting.Property.IndexScope,
        Setting.Property.Dynamic
    );

    public static final Setting<TimeValue> EVALUATION_INTERVAL = Setting.timeSetting(
        "cluster.tiering.evaluation_interval",
        TimeValue.timeValueMinutes(5),
        TimeValue.timeValueMinutes(1),
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    private TieringPolicySettings() {}

    public static List<Setting<?>> getIndexSettings() {
        return Arrays.asList(WARM_AFTER, COLD_AFTER, DELETE_AFTER);
    }

    public static List<Setting<?>> getClusterSettings() {
        return Arrays.asList(EVALUATION_INTERVAL);
    }
}
