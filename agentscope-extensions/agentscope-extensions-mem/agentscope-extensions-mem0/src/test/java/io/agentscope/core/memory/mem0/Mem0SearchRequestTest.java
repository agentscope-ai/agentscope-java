/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.memory.mem0;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.util.JsonUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Mem0SearchRequest}. */
class Mem0SearchRequestTest {

    @Test
    void testDefaultConstructor() {
        Mem0SearchRequest request = new Mem0SearchRequest();

        assertNotNull(request);
        assertEquals("v2", request.getVersion());
        assertEquals(10, request.getTopK());
        assertNotNull(request.getFilters());
        assertTrue(request.getFilters().isEmpty());
    }

    @Test
    void testBuilderWithQuery() {
        Mem0SearchRequest request = Mem0SearchRequest.builder().query("test query").build();

        assertEquals("test query", request.getQuery());
        assertEquals("v2", request.getVersion());
    }

    @Test
    void testBuilderWithFilters() {
        Map<String, Object> filters = new HashMap<>();
        filters.put("category", "personal");

        Mem0SearchRequest request = Mem0SearchRequest.builder().filters(filters).build();

        assertEquals(filters, request.getFilters());
    }

    @Test
    void testBuilderWithUserIdFilter() {
        Mem0SearchRequest request =
                Mem0SearchRequest.builder().query("test").userId("user123").build();

        assertNotNull(request.getFilters());
        assertEquals("user123", request.getFilters().get("user_id"));
    }

    @Test
    void testBuilderWithAgentIdFilter() {
        Mem0SearchRequest request =
                Mem0SearchRequest.builder().query("test").agentId("agent456").build();

        assertNotNull(request.getFilters());
        assertEquals("agent456", request.getFilters().get("agent_id"));
    }

    @Test
    void testBuilderWithRunIdFilter() {
        Mem0SearchRequest request =
                Mem0SearchRequest.builder().query("test").runId("run789").build();

        assertNotNull(request.getFilters());
        assertEquals("run789", request.getFilters().get("run_id"));
    }

    @Test
    void testBuilderWithAppIdFilter() {
        Mem0SearchRequest request =
                Mem0SearchRequest.builder().query("test").appId("app999").build();

        assertNotNull(request.getFilters());
        assertEquals("app999", request.getFilters().get("app_id"));
    }

    @Test
    void testBuilderWithMultipleFilters() {
        Mem0SearchRequest request =
                Mem0SearchRequest.builder()
                        .query("test")
                        .userId("user1")
                        .agentId("agent2")
                        .runId("run3")
                        .build();

        assertEquals("user1", request.getFilters().get("user_id"));
        assertEquals("agent2", request.getFilters().get("agent_id"));
        assertEquals("run3", request.getFilters().get("run_id"));
    }

    @Test
    void testBuilderWithTopK() {
        Mem0SearchRequest request = Mem0SearchRequest.builder().query("test").topK(20).build();

        assertEquals(20, request.getTopK());
    }

    @Test
    @SuppressWarnings("deprecation")
    void testBuilderWithDeprecatedLimit() {
        Mem0SearchRequest request = Mem0SearchRequest.builder().query("test").limit(15).build();

        assertEquals(15, request.getTopK());
    }

    @Test
    void testBuilderWithFields() {
        List<String> fields = List.of("memory", "id", "created_at");
        Mem0SearchRequest request = Mem0SearchRequest.builder().fields(fields).build();

        assertEquals(fields, request.getFields());
    }

    @Test
    void testBuilderWithRerank() {
        Mem0SearchRequest request = Mem0SearchRequest.builder().rerank(true).build();

        assertTrue(request.getRerank());
    }

    @Test
    void testBuilderWithKeywordSearch() {
        Mem0SearchRequest request = Mem0SearchRequest.builder().keywordSearch(true).build();

        assertTrue(request.getKeywordSearch());
    }

    @Test
    void testBuilderWithFilterMemories() {
        Mem0SearchRequest request = Mem0SearchRequest.builder().filterMemories(true).build();

        assertTrue(request.getFilterMemories());
    }

    @Test
    void testBuilderWithThreshold() {
        Mem0SearchRequest request = Mem0SearchRequest.builder().threshold(0.7).build();

        assertEquals(0.7, request.getThreshold());
    }

    @Test
    void testBuilderWithOrgAndProject() {
        Mem0SearchRequest request =
                Mem0SearchRequest.builder().orgId("org123").projectId("proj456").build();

        assertEquals("org123", request.getOrgId());
        assertEquals("proj456", request.getProjectId());
    }

    @Test
    void testBuilderWithVersion() {
        Mem0SearchRequest request = Mem0SearchRequest.builder().version("v3").build();

        assertEquals("v3", request.getVersion());
    }

    @Test
    void testSettersAndGetters() {
        Mem0SearchRequest request = new Mem0SearchRequest();
        request.setQuery("test query");
        request.setVersion("v2");
        request.setTopK(15);

        Map<String, Object> filters = new HashMap<>();
        filters.put("user_id", "user123");
        request.setFilters(filters);

        assertEquals("test query", request.getQuery());
        assertEquals("v2", request.getVersion());
        assertEquals(15, request.getTopK());
        assertEquals(filters, request.getFilters());
    }

    @Test
    void testJsonSerialization() throws Exception {
        Mem0SearchRequest request =
                Mem0SearchRequest.builder()
                        .query("search query")
                        .userId("user123")
                        .agentId("agent456")
                        .topK(5)
                        .threshold(0.8)
                        .build();

        String json = JsonUtils.getJsonCodec().toJson(request);
        assertNotNull(json);

        assertTrue(json.contains("\"query\""));
        assertTrue(json.contains("\"version\""));
        assertTrue(json.contains("\"filters\""));
        assertTrue(json.contains("\"top_k\""));
        assertTrue(json.contains("\"threshold\""));
    }

    @Test
    void testJsonDeserialization() throws Exception {
        String json =
                "{"
                        + "\"query\":\"test\","
                        + "\"version\":\"v2\","
                        + "\"filters\":{\"user_id\":\"user123\"},"
                        + "\"top_k\":10,"
                        + "\"threshold\":0.5"
                        + "}";

        Mem0SearchRequest request =
                JsonUtils.getJsonCodec().fromJson(json, Mem0SearchRequest.class);

        assertNotNull(request);
        assertEquals("test", request.getQuery());
        assertEquals("v2", request.getVersion());
        assertEquals(10, request.getTopK());
        assertEquals(0.5, request.getThreshold());
        assertEquals("user123", request.getFilters().get("user_id"));
    }

    @Test
    void testJsonRoundTrip() throws Exception {
        Mem0SearchRequest original =
                Mem0SearchRequest.builder()
                        .query("round trip test")
                        .userId("user789")
                        .topK(20)
                        .rerank(true)
                        .threshold(0.9)
                        .build();

        String json = JsonUtils.getJsonCodec().toJson(original);
        Mem0SearchRequest deserialized =
                JsonUtils.getJsonCodec().fromJson(json, Mem0SearchRequest.class);

        assertEquals(original.getQuery(), deserialized.getQuery());
        assertEquals(original.getVersion(), deserialized.getVersion());
        assertEquals(original.getTopK(), deserialized.getTopK());
        assertEquals(original.getThreshold(), deserialized.getThreshold());
        assertEquals(
                original.getFilters().get("user_id"), deserialized.getFilters().get("user_id"));
    }

    @Test
    void testFiltersAlwaysIncluded() throws Exception {
        // Filters should always be included in JSON, even if empty
        Mem0SearchRequest request = Mem0SearchRequest.builder().query("test").build();

        String json = JsonUtils.getJsonCodec().toJson(request);

        assertTrue(json.contains("\"filters\""));
    }

    @Test
    void testJsonExcludesNullFields() throws Exception {
        Mem0SearchRequest request = Mem0SearchRequest.builder().query("test").topK(10).build();

        String json = JsonUtils.getJsonCodec().toJson(request);

        // Null optional fields should be excluded
        assertFalse(json.contains("\"fields\""));
        assertFalse(json.contains("\"rerank\""));
        assertFalse(json.contains("\"keyword_search\""));
    }

    @Test
    void testNullFiltersHandling() {
        Mem0SearchRequest request = new Mem0SearchRequest();
        request.setFilters(null);

        // Should be replaced with empty map
        assertNotNull(request.getFilters());
        assertTrue(request.getFilters().isEmpty());
    }

    @Test
    void testNullFilterValuesIgnored() {
        Mem0SearchRequest request =
                Mem0SearchRequest.builder()
                        .query("test")
                        .userId(null)
                        .agentId("agent123")
                        .runId(null)
                        .build();

        // Only non-null filters should be added
        assertFalse(request.getFilters().containsKey("user_id"));
        assertTrue(request.getFilters().containsKey("agent_id"));
        assertFalse(request.getFilters().containsKey("run_id"));
    }
}
