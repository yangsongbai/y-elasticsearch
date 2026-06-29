/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.core.TimeValue;

import java.util.Arrays;
import java.util.List;

public final class AutoscalingSettings {

    public static final Setting<Boolean> ENABLED = Setting.boolSetting(
        "cluster.autoscaling.enabled", true, Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<TimeValue> EVALUATION_INTERVAL = Setting.timeSetting(
        "cluster.autoscaling.evaluation_interval", TimeValue.timeValueSeconds(30),
        TimeValue.timeValueSeconds(5), Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<TimeValue> COOLDOWN_UP = Setting.timeSetting(
        "cluster.autoscaling.cooldown.up", TimeValue.timeValueSeconds(30),
        Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<TimeValue> COOLDOWN_DOWN = Setting.timeSetting(
        "cluster.autoscaling.cooldown.down", TimeValue.timeValueMinutes(5),
        Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<Double> RATE_UP = Setting.doubleSetting(
        "cluster.autoscaling.rate.up", 1.0, 0.1, 10.0,
        Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<Double> RATE_DOWN = Setting.doubleSetting(
        "cluster.autoscaling.rate.down", 0.3, 0.1, 1.0,
        Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<TimeValue> LATENCY_TARGET_P99 = Setting.timeSetting(
        "cluster.autoscaling.deciders.latency.target_p99", TimeValue.timeValueMillis(200),
        Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<Integer> QUEUE_THRESHOLD = Setting.intSetting(
        "cluster.autoscaling.deciders.queue.threshold", 100, 1,
        Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<TimeValue> PREDICTIVE_LOOKAHEAD = Setting.timeSetting(
        "cluster.autoscaling.deciders.predictive.lookahead", TimeValue.timeValueMinutes(15),
        Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<Integer> WARM_MIN = Setting.intSetting(
        "cluster.autoscaling.warm.min", 2, 0, Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<Integer> WARM_MAX = Setting.intSetting(
        "cluster.autoscaling.warm.max", 100, 1, Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<Integer> HOT_MIN = Setting.intSetting(
        "cluster.autoscaling.hot.min", 3, 1, Setting.Property.Dynamic, Setting.Property.NodeScope);

    public static final Setting<Integer> HOT_MAX = Setting.intSetting(
        "cluster.autoscaling.hot.max", 30, 1, Setting.Property.Dynamic, Setting.Property.NodeScope);

    private AutoscalingSettings() {}

    public static List<Setting<?>> getSettings() {
        return Arrays.asList(ENABLED, EVALUATION_INTERVAL, COOLDOWN_UP, COOLDOWN_DOWN,
            RATE_UP, RATE_DOWN, LATENCY_TARGET_P99, QUEUE_THRESHOLD, PREDICTIVE_LOOKAHEAD,
            WARM_MIN, WARM_MAX, HOT_MIN, HOT_MAX);
    }
}
