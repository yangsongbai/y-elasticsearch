package org.elasticsearch.index.remote.prefetch;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;

public class PrefetchSettingsTests extends ESTestCase {

    public void testDefaults() {
        Settings s = Settings.EMPTY;
        assertTrue(PrefetchSettings.ENABLED.get(s));
        assertEquals("200mb", PrefetchSettings.RATE_LIMIT.get(s).toString());
        assertEquals(4, PrefetchSettings.CONCURRENCY.get(s).intValue());
        assertEquals(0.80, PrefetchSettings.CACHE_THRESHOLD.get(s), 0.01);
    }

    public void testCustom() {
        Settings s = Settings.builder()
            .put("node.prefetch.enabled", false)
            .put("node.prefetch.rate_limit", "500mb")
            .put("node.prefetch.concurrency", 8)
            .build();
        assertFalse(PrefetchSettings.ENABLED.get(s));
        assertEquals("500mb", PrefetchSettings.RATE_LIMIT.get(s).toString());
        assertEquals(8, PrefetchSettings.CONCURRENCY.get(s).intValue());
    }
}
