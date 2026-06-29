package org.elasticsearch.index.remote.cache;

import org.elasticsearch.test.ESTestCase;

import java.util.Arrays;
import java.util.List;

public class LFUDecayPolicyTests extends ESTestCase {

    public void testEvictLeastFrequent() {
        LFUDecayPolicy policy = new LFUDecayPolicy(0.95, 60_000L);
        CacheRegion r1 = new CacheRegion(0, 16 * 1024 * 1024);
        CacheRegion r2 = new CacheRegion(1, 16 * 1024 * 1024);
        CacheRegion r3 = new CacheRegion(2, 16 * 1024 * 1024);

        for (int i = 0; i < 10; i++) r1.recordAccess();
        r2.recordAccess();
        for (int i = 0; i < 5; i++) r3.recordAccess();

        List<CacheRegion> candidates = Arrays.asList(r1, r2, r3);
        CacheRegion victim = policy.selectVictim(candidates);
        assertEquals(1, victim.getRegionId());
    }

    public void testDecayReducesFrequency() {
        LFUDecayPolicy policy = new LFUDecayPolicy(0.5, 60_000L);
        CacheRegion region = new CacheRegion(0, 16 * 1024 * 1024);
        for (int i = 0; i < 100; i++) region.recordAccess();
        assertEquals(100, region.getAccessCount());

        policy.applyDecay(Arrays.asList(region));
        assertEquals(50, region.getAccessCount());
    }
}
