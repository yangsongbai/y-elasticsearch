/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.qa.single_node;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;

import org.apache.http.util.EntityUtils;
import org.apache.lucene.search.DocIdSetIterator;
import org.elasticsearch.Build;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.MapMatcher;
import org.elasticsearch.test.TestClustersThreadFilter;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.cluster.LogType;
import org.elasticsearch.xpack.esql.qa.rest.RestEsqlTestCase;
import org.hamcrest.Matchers;
import org.junit.ClassRule;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.test.ListMatcher.matchesList;
import static org.elasticsearch.test.MapMatcher.assertMap;
import static org.elasticsearch.test.MapMatcher.matchesMap;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.Is.is;

@ThreadLeakFilters(filters = TestClustersThreadFilter.class)
public class RestEsqlIT extends RestEsqlTestCase {
    @ClassRule
    public static ElasticsearchCluster cluster = Clusters.testCluster(
        specBuilder -> specBuilder.plugin("mapper-size").plugin("mapper-murmur3")
    );

    @Override
    protected String getTestRestCluster() {
        return cluster.getHttpAddresses();
    }

    @ParametersFactory
    public static List<Object[]> modes() {
        return Arrays.stream(Mode.values()).map(m -> new Object[] { m }).toList();
    }

    public RestEsqlIT(Mode mode) {
        super(mode);
    }

    public void testBasicEsql() throws IOException {
        indexTimestampData(1);

        RequestObjectBuilder builder = requestObjectBuilder().query(fromIndex() + " | stats avg(value)");
        if (Build.current().isSnapshot()) {
            builder.pragmas(Settings.builder().put("data_partitioning", "shard").build());
        }
        Map<String, Object> result = runEsql(builder);
        assertEquals(2, result.size());
        Map<String, String> colA = Map.of("name", "avg(value)", "type", "double");
        assertEquals(List.of(colA), result.get("columns"));
        assertEquals(List.of(List.of(499.5d)), result.get("values"));
    }

    public void testInvalidPragma() throws IOException {
        assumeTrue("pragma only enabled on snapshot builds", Build.current().isSnapshot());
        createIndex("test-index");
        for (int i = 0; i < 10; i++) {
            Request request = new Request("POST", "/test-index/_doc/");
            request.addParameter("refresh", "true");
            request.setJsonEntity("{\"f\":" + i + "}");
            assertOK(client().performRequest(request));
        }
        RequestObjectBuilder builder = requestObjectBuilder().query("from test-index | limit 1 | keep f");
        builder.pragmas(Settings.builder().put("data_partitioning", "invalid-option").build());
        ResponseException re = expectThrows(ResponseException.class, () -> runEsqlSync(builder));
        assertThat(EntityUtils.toString(re.getResponse().getEntity()), containsString("No enum constant"));

        assertThat(deleteIndex("test-index").isAcknowledged(), is(true)); // clean up
    }

    public void testPragmaNotAllowed() throws IOException {
        assumeFalse("pragma only disabled on release builds", Build.current().isSnapshot());
        RequestObjectBuilder builder = requestObjectBuilder().query("row a = 1, b = 2");
        builder.pragmas(Settings.builder().put("data_partitioning", "shard").build());
        ResponseException re = expectThrows(ResponseException.class, () -> runEsqlSync(builder));
        assertThat(EntityUtils.toString(re.getResponse().getEntity()), containsString("[pragma] only allowed in snapshot builds"));
    }

    public void testDoNotLogWithInfo() throws IOException {
        try {
            setLoggingLevel("INFO");
            RequestObjectBuilder builder = requestObjectBuilder().query("ROW DO_NOT_LOG_ME = 1");
            Map<String, Object> result = runEsql(builder);
            assertEquals(2, result.size());
            Map<String, String> colA = Map.of("name", "DO_NOT_LOG_ME", "type", "integer");
            assertEquals(List.of(colA), result.get("columns"));
            assertEquals(List.of(List.of(1)), result.get("values"));
            for (int i = 0; i < cluster.getNumNodes(); i++) {
                try (InputStream log = cluster.getNodeLog(i, LogType.SERVER)) {
                    Streams.readAllLines(log, line -> assertThat(line, not(containsString("DO_NOT_LOG_ME"))));
                }
            }
        } finally {
            setLoggingLevel(null);
        }
    }

    public void testDoLogWithDebug() throws IOException {
        try {
            setLoggingLevel("DEBUG");
            RequestObjectBuilder builder = requestObjectBuilder().query("ROW DO_LOG_ME = 1");
            Map<String, Object> result = runEsql(builder);
            assertEquals(2, result.size());
            Map<String, String> colA = Map.of("name", "DO_LOG_ME", "type", "integer");
            assertEquals(List.of(colA), result.get("columns"));
            assertEquals(List.of(List.of(1)), result.get("values"));
            boolean[] found = new boolean[] { false };
            for (int i = 0; i < cluster.getNumNodes(); i++) {
                try (InputStream log = cluster.getNodeLog(i, LogType.SERVER)) {
                    Streams.readAllLines(log, line -> {
                        if (line.contains("DO_LOG_ME")) {
                            found[0] = true;
                        }
                    });
                }
            }
            assertThat(found[0], equalTo(true));
        } finally {
            setLoggingLevel(null);
        }
    }

    private void setLoggingLevel(String level) throws IOException {
        Request request = new Request("PUT", "/_cluster/settings");
        request.setJsonEntity("""
            {
                "persistent": {
                    "logger.org.elasticsearch.xpack.esql.action": $LEVEL$
                }
            }
            """.replace("$LEVEL$", level == null ? "null" : '"' + level + '"'));
        client().performRequest(request);
    }

    public void testIncompatibleMappingsErrors() throws IOException {
        // create first index
        Request request = new Request("PUT", "/index1");
        request.setJsonEntity("""
            {
               "mappings": {
                 "_size": {
                   "enabled": true
                 },
                 "properties": {
                   "message": {
                     "type": "keyword",
                     "fields": {
                       "hash": {
                         "type": "murmur3"
                       }
                     }
                   }
                 }
               }
            }
            """);
        assertEquals(200, client().performRequest(request).getStatusLine().getStatusCode());

        // create second index
        request = new Request("PUT", "/index2");
        request.setJsonEntity("""
            {
              "mappings": {
                "properties": {
                  "message": {
                    "type": "long",
                    "fields": {
                      "hash": {
                        "type": "integer"
                      }
                    }
                  }
                }
              }
            }
            """);
        assertEquals(200, client().performRequest(request).getStatusLine().getStatusCode());

        // create alias
        request = new Request("POST", "/_aliases");
        request.setJsonEntity("""
            {
              "actions": [
                {
                  "add": {
                    "index": "index1",
                    "alias": "test_alias"
                  }
                },
                {
                  "add": {
                    "index": "index2",
                    "alias": "test_alias"
                  }
                }
              ]
            }
            """);
        assertEquals(200, client().performRequest(request).getStatusLine().getStatusCode());
        assertException(
            "from index1,index2 | stats count(message)",
            "VerificationException",
            "Cannot use field [message] due to ambiguities",
            "incompatible types: [keyword] in [index1], [long] in [index2]"
        );
        assertException(
            "from test_alias | where message is not null",
            "VerificationException",
            "Cannot use field [message] due to ambiguities",
            "incompatible types: [keyword] in [index1], [long] in [index2]"
        );
        assertException("from test_alias | where _size is not null | limit 1", "Unknown column [_size]");
        assertException(
            "from test_alias | where message.hash is not null | limit 1",
            "Cannot use field [message.hash] with unsupported type [murmur3]"
        );
        assertException(
            "from index1 | where message.hash is not null | limit 1",
            "Cannot use field [message.hash] with unsupported type [murmur3]"
        );
        // clean up
        assertThat(deleteIndex("index1").isAcknowledged(), Matchers.is(true));
        assertThat(deleteIndex("index2").isAcknowledged(), Matchers.is(true));
    }

    public void testTableDuplicateNames() throws IOException {
        Request request = new Request("POST", "/_query");
        request.setJsonEntity("""
            {
              "query": "FROM a=1",
              "tables": {
                "t": {
                  "a": {"integer": [1]},
                  "a": {"integer": [1]}
                }
              }
            }""");
        ResponseException re = expectThrows(ResponseException.class, () -> client().performRequest(request));
        assertThat(re.getResponse().getStatusLine().getStatusCode(), equalTo(400));
        assertThat(re.getMessage(), containsString("[6:10] Duplicate field 'a'"));
    }

    public void testProfile() throws IOException {
        indexTimestampData(1);

        RequestObjectBuilder builder = requestObjectBuilder().query(fromIndex() + " | STATS AVG(value)");
        builder.profile(true);
        if (Build.current().isSnapshot()) {
            // Lock to shard level partitioning, so we get consistent profile output
            builder.pragmas(Settings.builder().put("data_partitioning", "shard").build());
        }
        Map<String, Object> result = runEsql(builder);
        assertMap(
            result,
            matchesMap().entry("columns", matchesList().item(matchesMap().entry("name", "AVG(value)").entry("type", "double")))
                .entry("values", List.of(List.of(499.5d)))
                .entry("profile", matchesMap().entry("drivers", instanceOf(List.class)))
        );

        MapMatcher commonProfile = matchesMap().entry("iterations", greaterThan(0))
            .entry("cpu_nanos", greaterThan(0))
            .entry("took_nanos", greaterThan(0))
            .entry("operators", instanceOf(List.class));
        List<List<String>> signatures = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) ((Map<String, Object>) result.get("profile")).get("drivers");
        for (Map<String, Object> p : profiles) {
            assertThat(p, commonProfile);
            List<String> sig = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> operators = (List<Map<String, Object>>) p.get("operators");
            for (Map<String, Object> o : operators) {
                sig.add(checkOperatorProfile(o));
            }
            signatures.add(sig);
        }
        assertThat(
            signatures,
            containsInAnyOrder(
                matchesList().item("LuceneSourceOperator")
                    .item("ValuesSourceReaderOperator")
                    .item("AggregationOperator")
                    .item("ExchangeSinkOperator"),
                matchesList().item("ExchangeSourceOperator").item("ExchangeSinkOperator"),
                matchesList().item("ExchangeSourceOperator")
                    .item("AggregationOperator")
                    .item("ProjectOperator")
                    .item("LimitOperator")
                    .item("EvalOperator")
                    .item("ProjectOperator")
                    .item("OutputOperator")
            )
        );
    }

    private String checkOperatorProfile(Map<String, Object> o) {
        String name = (String) o.get("operator");
        name = name.replaceAll("\\[.+", "");
        MapMatcher status = switch (name) {
            case "LuceneSourceOperator" -> matchesMap().entry("processed_slices", greaterThan(0))
                .entry("processed_shards", List.of(testIndexName() + ":0"))
                .entry("total_slices", greaterThan(0))
                .entry("slice_index", 0)
                .entry("slice_max", 0)
                .entry("slice_min", 0)
                .entry("current", DocIdSetIterator.NO_MORE_DOCS)
                .entry("pages_emitted", greaterThan(0))
                .entry("processing_nanos", greaterThan(0))
                .entry("processed_queries", List.of("*:*"));
            case "ValuesSourceReaderOperator" -> basicProfile().entry("readers_built", matchesMap().extraOk());
            case "AggregationOperator" -> matchesMap().entry("pages_processed", greaterThan(0)).entry("aggregation_nanos", greaterThan(0));
            case "ExchangeSinkOperator" -> matchesMap().entry("pages_accepted", greaterThan(0));
            case "ExchangeSourceOperator" -> matchesMap().entry("pages_emitted", greaterThan(0)).entry("pages_waiting", 0);
            case "ProjectOperator", "EvalOperator" -> basicProfile();
            case "LimitOperator" -> matchesMap().entry("pages_processed", greaterThan(0))
                .entry("limit", 1000)
                .entry("limit_remaining", 999);
            case "OutputOperator" -> null;
            case "TopNOperator" -> matchesMap().entry("occupied_rows", 0)
                .entry("ram_used", instanceOf(String.class))
                .entry("ram_bytes_used", greaterThan(0));
            default -> throw new AssertionError("unexpected status: " + o);
        };
        MapMatcher expectedOp = matchesMap().entry("operator", startsWith(name));
        if (status != null) {
            expectedOp = expectedOp.entry("status", status);
        }
        assertMap(o, expectedOp);
        return name;
    }

    private MapMatcher basicProfile() {
        return matchesMap().entry("pages_processed", greaterThan(0)).entry("process_nanos", greaterThan(0));
    }

    private void assertException(String query, String... errorMessages) throws IOException {
        ResponseException re = expectThrows(ResponseException.class, () -> runEsqlSync(requestObjectBuilder().query(query)));
        assertThat(re.getResponse().getStatusLine().getStatusCode(), equalTo(400));
        for (var error : errorMessages) {
            assertThat(re.getMessage(), containsString(error));
        }
    }
}
