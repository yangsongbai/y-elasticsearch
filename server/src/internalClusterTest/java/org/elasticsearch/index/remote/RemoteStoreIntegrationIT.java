/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.index.remote;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.HashMap;
import java.util.Map;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 2)
public class RemoteStoreIntegrationIT extends ESIntegTestCase {

    public void testIndexWithRemoteStoreEnabled() throws Exception {
        CreateIndexRequest createRequest = new CreateIndexRequest("test-index");
        createRequest.settings(Settings.builder()
            .put("index.remote_store.enabled", true)
            .put("index.remote_store.repository", "test-repo")
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", 0)
            .build());
        client().admin().indices().create(createRequest).actionGet();

        ensureGreen("test-index");

        for (int i = 0; i < 100; i++) {
            Map<String, Object> source = new HashMap<>();
            source.put("field", "value-" + i);
            IndexResponse response = client().index(new IndexRequest("test-index").source(source)).actionGet();
            assertTrue(response.status().getStatus() < 300);
        }

        client().admin().indices().prepareRefresh("test-index").get();

        long count = client().prepareSearch("test-index").setSize(0).get().getHits().getTotalHits().value;
        assertEquals(100L, count);

        IndicesService indicesService = internalCluster().getInstance(IndicesService.class,
            internalCluster().getMasterName());
        for (IndexService indexService : indicesService) {
            if ("test-index".equals(indexService.index().getName())) {
                for (IndexShard shard : indexService) {
                    assertTrue(shard.isRemoteStoreEnabled());
                }
            }
        }
    }

    public void testWritesContinueWhenRemoteStoreDisabled() throws Exception {
        CreateIndexRequest createRequest = new CreateIndexRequest("no-remote-index");
        createRequest.settings(Settings.builder()
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", 0)
            .build());
        client().admin().indices().create(createRequest).actionGet();
        ensureGreen("no-remote-index");

        Map<String, Object> source = new HashMap<>();
        source.put("field", "value");
        IndexResponse response = client().index(new IndexRequest("no-remote-index").source(source)).actionGet();
        assertTrue(response.status().getStatus() < 300);

        client().admin().indices().prepareRefresh("no-remote-index").get();

        long count = client().prepareSearch("no-remote-index").setSize(0).get().getHits().getTotalHits().value;
        assertEquals(1L, count);
    }

    public void testRemoteStoreShardDoesNotUploadWhenNotActivated() throws Exception {
        CreateIndexRequest createRequest = new CreateIndexRequest("inactive-remote");
        createRequest.settings(Settings.builder()
            .put("index.remote_store.enabled", true)
            .put("index.remote_store.repository", "test-repo")
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", 0)
            .build());
        client().admin().indices().create(createRequest).actionGet();
        ensureGreen("inactive-remote");

        for (int i = 0; i < 50; i++) {
            Map<String, Object> source = new HashMap<>();
            source.put("data", "document-" + i);
            client().index(new IndexRequest("inactive-remote").source(source)).actionGet();
        }

        client().admin().indices().prepareRefresh("inactive-remote").get();

        long count = client().prepareSearch("inactive-remote").setSize(0).get().getHits().getTotalHits().value;
        assertEquals(50L, count);
    }
}
