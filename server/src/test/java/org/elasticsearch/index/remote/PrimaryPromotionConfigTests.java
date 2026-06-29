package org.elasticsearch.index.remote;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;

public class PrimaryPromotionConfigTests extends ESTestCase {

    public void testDefaultConcurrency() {
        Settings settings = Settings.EMPTY;
        assertEquals(20, RemoteStoreSettings.PRIMARY_PROMOTION_CONCURRENT.get(settings).intValue());
    }

    public void testCustomConcurrency() {
        Settings settings = Settings.builder()
            .put("cluster.routing.allocation.primary_promotion.concurrent", 10)
            .build();
        assertEquals(10, RemoteStoreSettings.PRIMARY_PROMOTION_CONCURRENT.get(settings).intValue());
    }

    public void testPreselectEnabled() {
        Settings settings = Settings.builder()
            .put("cluster.routing.allocation.primary_promotion.preselect", true)
            .build();
        assertTrue(RemoteStoreSettings.PRIMARY_PROMOTION_PRESELECT.get(settings));
    }

    public void testPrimaryShardsPerNodeLimit() {
        Settings settings = Settings.builder()
            .put("cluster.routing.allocation.primary_shards_per_node", 30)
            .build();
        assertEquals(30, RemoteStoreSettings.PRIMARY_SHARDS_PER_NODE.get(settings).intValue());
    }

    public void testPrimaryPromotionConfigObject() {
        PrimaryPromotionConfig config = new PrimaryPromotionConfig(20, 1000L, true);
        assertEquals(20, config.getConcurrent());
        assertEquals(1000L, config.getBatchIntervalMs());
        assertTrue(config.isPreselectEnabled());
    }
}
