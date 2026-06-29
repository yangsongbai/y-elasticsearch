package org.elasticsearch.index.remote.dr;

import org.elasticsearch.index.remote.prefetch.MetadataPrefetchPolicy;
import org.elasticsearch.index.remote.prefetch.TimePrefetchPolicy;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.Arrays;
import java.util.List;

public class PITRIntegrationIT extends ESIntegTestCase {

    public void testPITRServiceRegistersPoints() {
        PITRService service = new PITRService();
        long now = System.currentTimeMillis();

        for (int gen = 0; gen < 10; gen++) {
            service.registerRecoveryPoint(
                new PITRMetadata("test-idx", 1L, gen, now - (10 - gen) * 60_000L)
            );
        }

        PITRMetadata point = service.findClosestRecoveryPoint("test-idx", now - 300_000);
        assertNotNull(point);
        assertTrue(point.timestamp() <= now - 300_000);
    }

    public void testPrefetchPoliciesIntegrate() {
        TimePrefetchPolicy timePolicy = new TimePrefetchPolicy(2);
        MetadataPrefetchPolicy metaPolicy = new MetadataPrefetchPolicy();

        List<String> allFiles = Arrays.asList("_0.cfs", "_0.si", "_1.cfs", "_1.si", "_2.cfs", "_2.si");

        List<String> timePrefetch = timePolicy.selectPrefetchTargets("_0.cfs", allFiles);
        List<String> metaPrefetch = metaPolicy.selectPrefetchTargets(null, allFiles);

        assertFalse(timePrefetch.isEmpty());
        assertFalse(metaPrefetch.isEmpty());
        for (String f : metaPrefetch) {
            assertTrue(f.endsWith(".si") || f.endsWith(".cfe"));
        }
    }
}
