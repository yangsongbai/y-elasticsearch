package org.elasticsearch.index.remote.observability;

import org.elasticsearch.common.settings.Setting;

import java.util.Arrays;
import java.util.List;

public final class TracingSettings {

    public static final Setting<Boolean> TRACING_ENABLED = Setting.boolSetting(
        "tracing.enabled", true, Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<Double> SAMPLER_FRACTION = Setting.doubleSetting(
        "tracing.sampler.fraction", 0.1, 0.0, 1.0,
        Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<String> EXPORTER_TYPE = Setting.simpleString(
        "tracing.exporter.type", "otlp", Setting.Property.NodeScope);

    private TracingSettings() {}

    public static List<Setting<?>> getSettings() {
        return Arrays.asList(TRACING_ENABLED, SAMPLER_FRACTION, EXPORTER_TYPE);
    }
}
