package org.elasticsearch.index.remote.prefetch;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class PrefetchServiceTests extends ESTestCase {

    private ThreadPool threadPool;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool("test");
    }

    @Override
    public void tearDown() throws Exception {
        ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
        super.tearDown();
    }

    public void testPrefetchTriggersOnQueryHit() throws Exception {
        AtomicInteger fetchCount = new AtomicInteger(0);

        PrefetchService service = new PrefetchService(
            Collections.singletonList(new TimePrefetchPolicy(2)),
            threadPool, 4, true
        );

        List<String> segments = Arrays.asList("seg_0", "seg_1", "seg_2", "seg_3");
        service.onQueryHit("seg_1", segments, (fileName) -> {
            fetchCount.incrementAndGet();
        });

        assertBusy(() -> assertTrue(fetchCount.get() >= 1), 5, TimeUnit.SECONDS);
    }

    public void testDisabledPrefetchDoesNothing() {
        PrefetchService service = new PrefetchService(
            Collections.singletonList(new TimePrefetchPolicy(2)),
            threadPool, 4, false
        );

        AtomicInteger fetchCount = new AtomicInteger(0);
        service.onQueryHit("seg_1", Arrays.asList("seg_0", "seg_1", "seg_2"), (f) -> fetchCount.incrementAndGet());

        assertEquals(0, fetchCount.get());
    }
}
