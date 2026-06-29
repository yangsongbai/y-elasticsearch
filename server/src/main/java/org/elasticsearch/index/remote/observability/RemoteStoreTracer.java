package org.elasticsearch.index.remote.observability;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class RemoteStoreTracer {

    private final boolean enabled;
    private final double samplingRate;

    public RemoteStoreTracer(boolean enabled, double samplingRate) {
        this.enabled = enabled;
        this.samplingRate = samplingRate;
    }

    public SpanHandle startSpan(String operation, String resource) {
        if (!enabled) return new NoOpSpan();
        boolean sampled = ThreadLocalRandom.current().nextDouble() < samplingRate;
        return new ActiveSpan(operation, resource, sampled);
    }

    public interface SpanHandle {
        String traceId();
        void addAttribute(String key, long value);
        void addAttribute(String key, String value);
        void end(boolean success);
        boolean isSampled();
    }

    private static class NoOpSpan implements SpanHandle {
        @Override public String traceId() { return "00000000000000000000000000000000"; }
        @Override public void addAttribute(String key, long value) {}
        @Override public void addAttribute(String key, String value) {}
        @Override public void end(boolean success) {}
        @Override public boolean isSampled() { return false; }
    }

    private static class ActiveSpan implements SpanHandle {
        private final String traceId;
        private final String operation;
        private final String resource;
        private final boolean sampled;
        private final Map<String, Object> attributes = new HashMap<>();
        private final long startNanos = System.nanoTime();

        ActiveSpan(String operation, String resource, boolean sampled) {
            this.traceId = UUID.randomUUID().toString().replace("-", "");
            this.operation = operation;
            this.resource = resource;
            this.sampled = sampled;
        }

        @Override public String traceId() { return traceId; }
        @Override public void addAttribute(String key, long value) { attributes.put(key, value); }
        @Override public void addAttribute(String key, String value) { attributes.put(key, value); }
        @Override public boolean isSampled() { return sampled; }

        @Override
        public void end(boolean success) {
            // In production: export to OTel collector
        }
    }
}
