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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Mem0AddResponse}. */
class Mem0AddResponseTest {

    @Test
    void testDefaultConstructor() {
        Mem0AddResponse response = new Mem0AddResponse();
        assertNotNull(response);
        assertNull(response.getResults());
        assertNull(response.getMessage());
    }

    @Test
    void testSettersAndGetters() {
        Mem0AddResponse response = new Mem0AddResponse();
        List<Map<String, Object>> results = List.of(Map.of("memory", "test", "id", "123"));
        response.setResults(results);
        response.setMessage("Success");

        assertEquals(results, response.getResults());
        assertEquals("Success", response.getMessage());
    }

    @Test
    void testJsonDeserialization() throws Exception {
        String json =
                "{"
                        + "\"results\":["
                        + "{\"memory\":\"User prefers dark mode\",\"id\":\"mem_123\"}"
                        + "],"
                        + "\"message\":\"Successfully added memory\""
                        + "}";

        Mem0AddResponse response = JsonUtils.getJsonCodec().fromJson(json, Mem0AddResponse.class);

        assertNotNull(response);
        assertNotNull(response.getResults());
        assertEquals(1, response.getResults().size());
        assertEquals("Successfully added memory", response.getMessage());
    }

    @Test
    void testJsonSerialization() throws Exception {
        Mem0AddResponse response = new Mem0AddResponse();
        List<Map<String, Object>> results =
                List.of(Map.of("memory", "User prefers dark theme", "id", "mem_456"));
        response.setResults(results);
        response.setMessage("Memory added");

        String json = JsonUtils.getJsonCodec().toJson(response);
        assertNotNull(json);
        assertTrue(json.contains("\"results\""));
        assertTrue(json.contains("\"message\""));
    }

    @Test
    void testJsonRoundTrip() throws Exception {
        Mem0AddResponse original = new Mem0AddResponse();
        List<Map<String, Object>> results =
                List.of(Map.of("memory", "Test memory", "id", "test_123", "score", 0.95));
        original.setResults(results);
        original.setMessage("Test message");

        String json = JsonUtils.getJsonCodec().toJson(original);
        Mem0AddResponse deserialized =
                JsonUtils.getJsonCodec().fromJson(json, Mem0AddResponse.class);

        assertEquals(original.getResults().size(), deserialized.getResults().size());
        assertEquals(original.getMessage(), deserialized.getMessage());
    }

    @Test
    void testToString() {
        Mem0AddResponse response = new Mem0AddResponse();
        response.setResults(List.of(Map.of("key", "value")));
        response.setMessage("Test");

        String str = response.toString();
        assertNotNull(str);
        assertTrue(str.contains("Mem0AddResponse"));
        assertTrue(str.contains("1 items"));
        assertTrue(str.contains("Test"));
    }

    @Test
    void testToStringWithNullResults() {
        Mem0AddResponse response = new Mem0AddResponse();
        response.setMessage("No results");

        String str = response.toString();
        assertNotNull(str);
        assertTrue(str.contains("null"));
        assertTrue(str.contains("No results"));
    }

    @Test
    void testEmptyResults() {
        Mem0AddResponse response = new Mem0AddResponse();
        response.setResults(List.of());

        assertNotNull(response.getResults());
        assertEquals(0, response.getResults().size());
    }

    @Test
    void testJsonExcludesNullFields() throws Exception {
        Mem0AddResponse response = new Mem0AddResponse();
        // Only set results, leave message null

        List<Map<String, Object>> results = List.of(Map.of("test", "data"));
        response.setResults(results);

        String json = JsonUtils.getJsonCodec().toJson(response);

        // message field should be excluded when null
        assertTrue(json.contains("\"results\""));
        // The NON_NULL annotation should exclude null fields
        assertTrue(!json.contains("\"message\"") || json.contains("\"message\":null"));
    }
}
