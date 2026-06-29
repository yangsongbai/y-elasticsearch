package org.elasticsearch.index.remote.observability;

import org.elasticsearch.test.ESTestCase;

public class RemoteStoreTracerTests extends ESTestCase {

    public void testSpanCreation() {
        RemoteStoreTracer tracer = new RemoteStoreTracer(true, 1.0);

        RemoteStoreTracer.SpanHandle span = tracer.startSpan("segment_upload", "test-index/0/_0.cfs");
        assertNotNull(span);
        assertNotNull(span.traceId());
        assertTrue(span.traceId().length() > 0);

        span.addAttribute("file_size", 1024L);
        span.end(true);
    }

    public void testDisabledTracerReturnsNoOp() {
        RemoteStoreTracer tracer = new RemoteStoreTracer(false, 0.0);

        RemoteStoreTracer.SpanHandle span = tracer.startSpan("segment_upload", "test-index/0/_0.cfs");
        assertNotNull(span);
        assertFalse(span.isSampled());
        span.end(true);
    }

    public void testSamplingRateZero() {
        RemoteStoreTracer tracer = new RemoteStoreTracer(true, 0.0);
        int sampled = 0;
        for (int i = 0; i < 100; i++) {
            RemoteStoreTracer.SpanHandle span = tracer.startSpan("test", "resource-" + i);
            if (span.isSampled()) sampled++;
            span.end(true);
        }
        assertEquals(0, sampled);
    }
}
