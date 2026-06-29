/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import static org.mockito.Mockito.mock;

public class TieringPolicyServiceTests extends ESTestCase {

    private ThreadPool threadPool;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool("test");
    }

    @Override
    public void tearDown() throws Exception {
        threadPool.shutdownNow();
        super.tearDown();
    }

    public void testSettingsParsed() {
        Settings settings = Settings.builder()
            .put(TieringPolicySettings.WARM_AFTER.getKey(), "7d")
            .put(TieringPolicySettings.COLD_AFTER.getKey(), "30d")
            .put(TieringPolicySettings.DELETE_AFTER.getKey(), "90d")
            .build();
        assertEquals(7L * 24 * 60 * 60 * 1000,
            TieringPolicySettings.WARM_AFTER.get(settings).millis());
        assertEquals(30L * 24 * 60 * 60 * 1000,
            TieringPolicySettings.COLD_AFTER.get(settings).millis());
    }

    public void testEvaluatesIndexAge() {
        long nowMillis = System.currentTimeMillis();
        long eightDaysAgo = nowMillis - (8L * 24 * 60 * 60 * 1000);

        Settings indexSettings = Settings.builder()
            .put(IndexMetadata.SETTING_INDEX_UUID, "test-uuid")
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetadata.SETTING_VERSION_CREATED, org.elasticsearch.Version.CURRENT)
            .put(IndexMetadata.SETTING_CREATION_DATE, eightDaysAgo)
            .put(TieringPolicySettings.WARM_AFTER.getKey(), "7d")
            .build();

        IndexMetadata indexMetadata = IndexMetadata.builder("logs-2024")
            .settings(indexSettings)
            .build();

        TieringService mockTieringService = mock(TieringService.class);
        TieringPolicyService policyService = new TieringPolicyService(
            threadPool, mock(ClusterService.class), mockTieringService);

        boolean shouldTransition = policyService.shouldTransition(indexMetadata, TieringState.HOT, "warm");
        assertTrue(shouldTransition);
    }

    public void testDoesNotTransitionYoungIndex() {
        long nowMillis = System.currentTimeMillis();
        long twoDaysAgo = nowMillis - (2L * 24 * 60 * 60 * 1000);

        Settings indexSettings = Settings.builder()
            .put(IndexMetadata.SETTING_INDEX_UUID, "test-uuid")
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetadata.SETTING_VERSION_CREATED, org.elasticsearch.Version.CURRENT)
            .put(IndexMetadata.SETTING_CREATION_DATE, twoDaysAgo)
            .put(TieringPolicySettings.WARM_AFTER.getKey(), "7d")
            .build();

        IndexMetadata indexMetadata = IndexMetadata.builder("logs-2024")
            .settings(indexSettings)
            .build();

        TieringService mockTieringService = mock(TieringService.class);
        TieringPolicyService policyService = new TieringPolicyService(
            threadPool, mock(ClusterService.class), mockTieringService);

        boolean shouldTransition = policyService.shouldTransition(indexMetadata, TieringState.HOT, "warm");
        assertFalse(shouldTransition);
    }
}
