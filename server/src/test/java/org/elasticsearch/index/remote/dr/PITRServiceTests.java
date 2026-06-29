package org.elasticsearch.index.remote.dr;

import org.elasticsearch.test.ESTestCase;

import java.util.List;

public class PITRServiceTests extends ESTestCase {

    public void testFindRecoveryPoint() {
        PITRService service = new PITRService();
        long now = System.currentTimeMillis();

        service.registerRecoveryPoint(new PITRMetadata("idx", 1L, 100L, now - 3600_000));
        service.registerRecoveryPoint(new PITRMetadata("idx", 1L, 200L, now - 1800_000));
        service.registerRecoveryPoint(new PITRMetadata("idx", 1L, 300L, now - 600_000));

        PITRMetadata found = service.findClosestRecoveryPoint("idx", now - 1200_000);
        assertNotNull(found);
        assertEquals(200L, found.generation());
    }

    public void testNoRecoveryPointBeforeTimestamp() {
        PITRService service = new PITRService();
        long now = System.currentTimeMillis();
        service.registerRecoveryPoint(new PITRMetadata("idx", 1L, 100L, now));

        PITRMetadata found = service.findClosestRecoveryPoint("idx", now - 7200_000);
        assertNull(found);
    }

    public void testRetentionPruning() {
        PITRService service = new PITRService();
        long now = System.currentTimeMillis();

        for (int i = 0; i < 48; i++) {
            service.registerRecoveryPoint(new PITRMetadata("idx", 1L, i, now - i * 3600_000L));
        }

        service.pruneRetention("idx", 24 * 3600_000L);

        List<PITRMetadata> remaining = service.getRecoveryPoints("idx");
        assertTrue(remaining.size() >= 24);
        assertTrue(remaining.size() <= 48);
    }
}
