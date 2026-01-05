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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Mem0SearchResponse}. */
class Mem0SearchResponseTest {

    @Test
    void testDefaultConstructor() {
        Mem0SearchResponse response = new Mem0SearchResponse();
        assertNotNull(response);
        assertNull(response.getResults());
    }

    @Test
    void testSettersAndGetters() {
        Mem0SearchResponse response = new Mem0SearchResponse();
        List<Mem0SearchResult> results = new ArrayList<>();

        Mem0SearchResult result = new Mem0SearchResult();
        result.setMemory("Test memory");
        result.setId("mem_123");
        results.add(result);

        response.setResults(results);

        assertEquals(results, response.getResults());
        assertEquals(1, response.getResults().size());
    }

    @Test
    void testJsonDeserialization() throws Exception {
        String json =
                "{"
                        + "\"results\":["
                        + "{"
                        + "\"id\":\"mem_123\","
                        + "\"memory\":\"User prefers dark mode\","
                        + "\"user_id\":\"user_456\""
                        + "}"
                        + "]"
                        + "}";

        Mem0SearchResponse response =
                JsonUtils.getJsonCodec().fromJson(json, Mem0SearchResponse.class);

        assertNotNull(response);
        assertNotNull(response.getResults());
        assertEquals(1, response.getResults().size());
        assertEquals("mem_123", response.getResults().get(0).getId());
        assertEquals("User prefers dark mode", response.getResults().get(0).getMemory());
    }

    @Test
    void testJsonSerialization() throws Exception {
        Mem0SearchResponse response = new Mem0SearchResponse();
        List<Mem0SearchResult> results = new ArrayList<>();

        Mem0SearchResult result = new Mem0SearchResult();
        result.setId("mem_789");
        result.setMemory("User likes coffee");
        result.setUserId("user_123");
        results.add(result);

        response.setResults(results);

        String json = JsonUtils.getJsonCodec().toJson(response);
        assertNotNull(json);
        assertTrue(json.contains("\"results\""));
        assertTrue(json.contains("mem_789"));
        assertTrue(json.contains("User likes coffee"));
    }

    @Test
    void testJsonRoundTrip() throws Exception {
        Mem0SearchResponse original = new Mem0SearchResponse();
        List<Mem0SearchResult> results = new ArrayList<>();

        Mem0SearchResult result = new Mem0SearchResult();
        result.setId("test_id");
        result.setMemory("Test memory content");
        result.setUserId("test_user");
        result.setScore(0.95);
        results.add(result);

        original.setResults(results);

        String json = JsonUtils.getJsonCodec().toJson(original);
        Mem0SearchResponse deserialized =
                JsonUtils.getJsonCodec().fromJson(json, Mem0SearchResponse.class);

        assertEquals(original.getResults().size(), deserialized.getResults().size());
        assertEquals(
                original.getResults().get(0).getMemory(),
                deserialized.getResults().get(0).getMemory());
        assertEquals(
                original.getResults().get(0).getId(), deserialized.getResults().get(0).getId());
    }

    @Test
    void testToString() {
        Mem0SearchResponse response = new Mem0SearchResponse();
        List<Mem0SearchResult> results = new ArrayList<>();
        results.add(new Mem0SearchResult());
        results.add(new Mem0SearchResult());
        response.setResults(results);

        String str = response.toString();
        assertNotNull(str);
        assertTrue(str.contains("Mem0SearchResponse"));
        assertTrue(str.contains("2 items"));
    }

    @Test
    void testToStringWithNullResults() {
        Mem0SearchResponse response = new Mem0SearchResponse();
        String str = response.toString();
        assertNotNull(str);
        assertTrue(str.contains("null"));
    }

    @Test
    void testEmptyResults() {
        Mem0SearchResponse response = new Mem0SearchResponse();
        response.setResults(new ArrayList<>());

        assertNotNull(response.getResults());
        assertEquals(0, response.getResults().size());

        String str = response.toString();
        assertTrue(str.contains("0 items"));
    }

    @Test
    void testMultipleResults() {
        Mem0SearchResponse response = new Mem0SearchResponse();
        List<Mem0SearchResult> results = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            Mem0SearchResult result = new Mem0SearchResult();
            result.setId("mem_" + i);
            result.setMemory("Memory " + i);
            results.add(result);
        }

        response.setResults(results);

        assertEquals(5, response.getResults().size());
    }
}
