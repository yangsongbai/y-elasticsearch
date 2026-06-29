package org.elasticsearch.index.remote.autoscaling;

import org.elasticsearch.test.ESTestCase;

public class PromotionRegistryTests extends ESTestCase {

    public void testActivePromotion() {
        PromotionRegistry registry = new PromotionRegistry();
        long now = System.currentTimeMillis();
        registry.register("double11", now - 1000, now + 3600_000,
            new PromotionRegistry.ScaleFactors(5.0, 3.0), true);

        assertTrue(registry.hasActivePromotion());
        assertEquals(5.0, registry.getActiveScaleFactor("warm"), 0.01);
        assertTrue(registry.isScaleDownLocked());
    }

    public void testExpiredPromotion() {
        PromotionRegistry registry = new PromotionRegistry();
        long now = System.currentTimeMillis();
        registry.register("past-event", now - 7200_000, now - 3600_000,
            new PromotionRegistry.ScaleFactors(5.0, 3.0), true);

        assertFalse(registry.hasActivePromotion());
        assertEquals(1.0, registry.getActiveScaleFactor("warm"), 0.01);
        assertFalse(registry.isScaleDownLocked());
    }

    public void testRemovePromotion() {
        PromotionRegistry registry = new PromotionRegistry();
        long now = System.currentTimeMillis();
        registry.register("event", now, now + 3600_000,
            new PromotionRegistry.ScaleFactors(2.0, 1.5), false);
        registry.remove("event");
        assertFalse(registry.hasActivePromotion());
    }
}
