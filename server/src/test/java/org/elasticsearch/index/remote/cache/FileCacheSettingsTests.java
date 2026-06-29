/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.index.remote.cache;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;

public class FileCacheSettingsTests extends ESTestCase {

    public void testDefaultValues() {
        Settings settings = Settings.EMPTY;
        assertEquals("200gb", FileCacheSettings.CACHE_SIZE.get(settings).toString());
        assertEquals("16mb", FileCacheSettings.REGION_SIZE.get(settings).toString());
        assertEquals("LFU_DECAY", FileCacheSettings.EVICTION_POLICY.get(settings));
    }

    public void testCustomValues() {
        Settings settings = Settings.builder()
            .put("node.filecache.size", "100gb")
            .put("node.filecache.region_size", "8mb")
            .put("node.filecache.eviction_policy", "LRU")
            .build();
        assertEquals("100gb", FileCacheSettings.CACHE_SIZE.get(settings).toString());
        assertEquals("8mb", FileCacheSettings.REGION_SIZE.get(settings).toString());
        assertEquals("LRU", FileCacheSettings.EVICTION_POLICY.get(settings));
    }

    public void testDecaySettings() {
        Settings settings = Settings.builder()
            .put("node.filecache.decay.interval", "5m")
            .put("node.filecache.decay.factor", 0.9)
            .build();
        assertEquals(300000L, FileCacheSettings.DECAY_INTERVAL.get(settings).millis());
        assertEquals(0.9, FileCacheSettings.DECAY_FACTOR.get(settings), 0.001);
    }
}
