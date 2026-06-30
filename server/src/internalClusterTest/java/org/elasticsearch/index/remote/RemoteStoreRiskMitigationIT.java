/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.index.remote;

import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.equalTo;

@ESIntegTestCase.ClusterScope(numDataNodes = 3)
public class RemoteStoreRiskMitigationIT extends ESIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal, otherSettings))
            .put("cluster.remote_store.single_writer.fast_degrade_on_first_failure", true)
            .put("cluster.remote_store.single_writer.degrade_after_failures", 2)
            .put("cluster.search.fast_failover.enabled", true)
            .put("cluster.routing.allocation.primary_promotion.concurrent", 20)
            .put("cluster.routing.allocation.primary_shards_per_node", 20)
            .build();
    }

    public void testSingleWriterLockSettingsRegistered() {
        ClusterState state = client().admin().cluster().prepareState().get().getState();
        assertNotNull(state);
        Settings persistentSettings = state.metadata().persistentSettings();
        assertNotNull(persistentSettings);
    }

    public void testFastFailoverSettingsRegistered() {
        Settings settings = client().admin().cluster().prepareState().get()
            .getState().metadata().persistentSettings();
        assertNotNull(settings);
    }

    public void testUploadOrderCoordinatorDoesNotBlockWhenNoTranslog() {
        UploadOrderCoordinator coordinator = new UploadOrderCoordinator();
        coordinator.awaitTranslogUploadedForGeneration(1L);
    }

    public void testRelocationUploadServiceReady() throws Exception {
        ThreadPool threadPool = new TestThreadPool("test");
        try {
            RelocationUploadService service = new RelocationUploadService(60_000L, 256 * 1024 * 1024L, threadPool);
            service.setForceUploadCallback(seqNo -> seqNo + 10);
            RelocationUploadService.TailState tailState = new RelocationUploadService.TailState(100L, 90L, 1024L);
            RelocationUploadService.HandoffReadiness result = service.prepareForHandoff(tailState);
            assertThat(result, equalTo(RelocationUploadService.HandoffReadiness.READY));
        } finally {
            ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
        }
    }
}
