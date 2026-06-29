/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.index.remote.tiering;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.HashMap;
import java.util.Map;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 2)
public class TieringServiceIntegrationIT extends ESIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal, otherSettings))
            .build();
    }

    public void testTieringPolicySettingsRegistered() throws Exception {
        CreateIndexRequest request = new CreateIndexRequest("tier-test");
        request.settings(Settings.builder()
            .put("index.tiering.warm_after", "7d")
            .put("index.tiering.cold_after", "30d")
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", 0)
            .build());
        client().admin().indices().create(request).actionGet();
        ensureGreen("tier-test");

        Settings indexSettings = client().admin().indices().prepareGetSettings("tier-test")
            .get().getIndexToSettings().get("tier-test");
        assertEquals("7d", indexSettings.get("index.tiering.warm_after"));
        assertEquals("30d", indexSettings.get("index.tiering.cold_after"));
    }

    public void testIndexDataWithTieringSettingsQueryable() throws Exception {
        CreateIndexRequest request = new CreateIndexRequest("tier-data-test");
        request.settings(Settings.builder()
            .put("index.tiering.warm_after", "7d")
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", 0)
            .build());
        client().admin().indices().create(request).actionGet();
        ensureGreen("tier-data-test");

        for (int i = 0; i < 50; i++) {
            Map<String, Object> source = new HashMap<>();
            source.put("field", "value-" + i);
            client().index(new IndexRequest("tier-data-test").source(source)).actionGet();
        }
        client().admin().indices().prepareRefresh("tier-data-test").get();

        long count = client().prepareSearch("tier-data-test").setSize(0).get().getHits().getTotalHits().value;
        assertEquals(50L, count);
    }
}
