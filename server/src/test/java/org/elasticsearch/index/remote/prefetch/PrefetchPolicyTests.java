package org.elasticsearch.index.remote.prefetch;

import org.elasticsearch.test.ESTestCase;

import java.util.Arrays;
import java.util.List;

public class PrefetchPolicyTests extends ESTestCase {

    public void testTimePrefetchSelectsAdjacentSegments() {
        TimePrefetchPolicy policy = new TimePrefetchPolicy(3);
        List<String> segments = Arrays.asList("seg_0", "seg_1", "seg_2", "seg_3", "seg_4",
            "seg_5", "seg_6", "seg_7", "seg_8", "seg_9");

        List<String> toPrefetch = policy.selectPrefetchTargets("seg_5", segments);

        assertTrue(toPrefetch.contains("seg_6"));
        assertTrue(toPrefetch.contains("seg_7"));
        assertTrue(toPrefetch.contains("seg_8"));
        assertEquals(3, toPrefetch.size());
    }

    public void testMetadataPrefetchSelectsMetaFiles() {
        MetadataPrefetchPolicy policy = new MetadataPrefetchPolicy();
        List<String> allFiles = Arrays.asList("_0.cfs", "_0.si", "_0.cfe", "_1.cfs", "_1.si", "_1.cfe");

        List<String> toPrefetch = policy.selectPrefetchTargets(null, allFiles);

        assertTrue(toPrefetch.contains("_0.si"));
        assertTrue(toPrefetch.contains("_0.cfe"));
        assertTrue(toPrefetch.contains("_1.si"));
        assertTrue(toPrefetch.contains("_1.cfe"));
        assertFalse(toPrefetch.contains("_0.cfs"));
    }
}
