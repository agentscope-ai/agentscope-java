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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Mem0SearchResult}. */
class Mem0SearchResultTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void testDefaultConstructor() {
        Mem0SearchResult result = new Mem0SearchResult();
        assertNotNull(result);
        assertNull(result.getId());
        assertNull(result.getMemory());
        assertNull(result.getUserId());
    }

    @Test
    void testSettersAndGetters() {
        Mem0SearchResult result = new Mem0SearchResult();
        result.setId("mem_123");
        result.setMemory("Test memory");
        result.setUserId("user_456");
        result.setScore(0.95);
        result.setImmutable(true);

        assertEquals("mem_123", result.getId());
        assertEquals("Test memory", result.getMemory());
        assertEquals("user_456", result.getUserId());
        assertEquals(0.95, result.getScore());
        assertTrue(result.getImmutable());
    }

    @Test
    void testWithMetadata() {
        Mem0SearchResult result = new Mem0SearchResult();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "user_input");
        metadata.put("importance", 5);

        result.setMetadata(metadata);

        assertEquals(metadata, result.getMetadata());
    }

    @Test
    void testWithCategories() {
        Mem0SearchResult result = new Mem0SearchResult();
        List<String> categories = List.of("personal", "preferences");

        result.setCategories(categories);

        assertEquals(categories, result.getCategories());
    }

    @Test
    void testWithTimestamps() {
        Mem0SearchResult result = new Mem0SearchResult();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime later = now.plusDays(30);

        result.setCreatedAt(now);
        result.setUpdatedAt(now);
        result.setExpirationDate(later);

        assertEquals(now, result.getCreatedAt());
        assertEquals(now, result.getUpdatedAt());
        assertEquals(later, result.getExpirationDate());
    }

    @Test
    void testWithStructuredAttributes() {
        Mem0SearchResult result = new Mem0SearchResult();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("day", 15);
        attributes.put("month", 3);
        attributes.put("year", 2025);

        result.setStructuredAttributes(attributes);

        assertEquals(attributes, result.getStructuredAttributes());
    }

    @Test
    void testJsonDeserialization() throws Exception {
        String json =
                "{"
                        + "\"id\":\"mem_789\","
                        + "\"memory\":\"User prefers dark mode\","
                        + "\"user_id\":\"user_123\","
                        + "\"score\":0.92,"
                        + "\"categories\":[\"preferences\",\"ui\"],"
                        + "\"immutable\":false"
                        + "}";

        Mem0SearchResult result = objectMapper.readValue(json, Mem0SearchResult.class);

        assertNotNull(result);
        assertEquals("mem_789", result.getId());
        assertEquals("User prefers dark mode", result.getMemory());
        assertEquals("user_123", result.getUserId());
        assertEquals(0.92, result.getScore());
        assertNotNull(result.getCategories());
        assertEquals(2, result.getCategories().size());
        assertFalse(result.getImmutable());
    }

    @Test
    void testJsonDeserializationWithTimestamps() throws Exception {
        String json =
                "{"
                        + "\"id\":\"mem_456\","
                        + "\"memory\":\"Test\","
                        + "\"user_id\":\"user_789\","
                        + "\"created_at\":\"2025-01-15T10:30:00Z\","
                        + "\"updated_at\":\"2025-01-16T14:20:00Z\","
                        + "\"expiration_date\":\"2025-12-31T23:59:59Z\""
                        + "}";

        Mem0SearchResult result = objectMapper.readValue(json, Mem0SearchResult.class);

        assertNotNull(result);
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());
        assertNotNull(result.getExpirationDate());
    }

    @Test
    void testJsonSerialization() throws Exception {
        Mem0SearchResult result = new Mem0SearchResult();
        result.setId("mem_999");
        result.setMemory("User likes coffee");
        result.setUserId("user_888");
        result.setScore(0.98);
        result.setCategories(List.of("food", "preferences"));
        result.setImmutable(false);

        String json = objectMapper.writeValueAsString(result);
        assertNotNull(json);

        assertTrue(json.contains("\"id\""));
        assertTrue(json.contains("\"memory\""));
        assertTrue(json.contains("\"user_id\""));
        assertTrue(json.contains("\"score\""));
        assertTrue(json.contains("\"categories\""));
        assertTrue(json.contains("\"immutable\""));
    }

    @Test
    void testJsonRoundTrip() throws Exception {
        Mem0SearchResult original = new Mem0SearchResult();
        original.setId("test_id");
        original.setMemory("Test memory content");
        original.setUserId("test_user");
        original.setScore(0.85);
        original.setCategories(List.of("test", "demo"));
        original.setImmutable(true);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("test", "value");
        original.setMetadata(metadata);

        String json = objectMapper.writeValueAsString(original);
        Mem0SearchResult deserialized = objectMapper.readValue(json, Mem0SearchResult.class);

        assertEquals(original.getId(), deserialized.getId());
        assertEquals(original.getMemory(), deserialized.getMemory());
        assertEquals(original.getUserId(), deserialized.getUserId());
        assertEquals(original.getScore(), deserialized.getScore());
        assertEquals(original.getImmutable(), deserialized.getImmutable());
    }

    @Test
    void testToString() {
        Mem0SearchResult result = new Mem0SearchResult();
        result.setId("mem_111");
        result.setMemory("Test memory");
        result.setUserId("user_222");
        result.setCategories(List.of("test"));
        result.setImmutable(false);

        String str = result.toString();
        assertNotNull(str);
        assertTrue(str.contains("Mem0SearchResult"));
        assertTrue(str.contains("mem_111"));
        assertTrue(str.contains("Test memory"));
        assertTrue(str.contains("user_222"));
    }

    @Test
    void testJsonExcludesNullFields() throws Exception {
        Mem0SearchResult result = new Mem0SearchResult();
        result.setId("mem_123");
        result.setMemory("Test");
        // Leave other fields null

        String json = objectMapper.writeValueAsString(result);

        assertTrue(json.contains("\"id\""));
        assertTrue(json.contains("\"memory\""));
        // Null fields should be excluded
        assertFalse(json.contains("\"user_id\""));
        assertFalse(json.contains("\"metadata\""));
        assertFalse(json.contains("\"categories\""));
    }

    @Test
    void testFieldNameMapping() throws Exception {
        // Verify snake_case JSON field mapping
        Mem0SearchResult result = new Mem0SearchResult();
        result.setUserId("test_user");
        result.setCreatedAt(OffsetDateTime.now());
        result.setUpdatedAt(OffsetDateTime.now());
        result.setExpirationDate(OffsetDateTime.now().plusDays(30));

        Map<String, Object> structuredAttrs = Map.of("day", 1);
        result.setStructuredAttributes(structuredAttrs);

        String json = objectMapper.writeValueAsString(result);

        assertTrue(json.contains("\"user_id\""));
        assertTrue(json.contains("\"created_at\""));
        assertTrue(json.contains("\"updated_at\""));
        assertTrue(json.contains("\"expiration_date\""));
        assertTrue(json.contains("\"structured_attributes\""));
    }

    @Test
    void testCompleteResult() {
        Mem0SearchResult result = new Mem0SearchResult();
        result.setId("complete_mem");
        result.setMemory("Complete memory");
        result.setUserId("complete_user");
        result.setScore(1.0);
        result.setCategories(List.of("complete"));
        result.setImmutable(true);
        result.setMetadata(Map.of("complete", true));
        result.setCreatedAt(OffsetDateTime.now());
        result.setUpdatedAt(OffsetDateTime.now());
        result.setStructuredAttributes(Map.of("complete", "yes"));

        assertNotNull(result.getId());
        assertNotNull(result.getMemory());
        assertNotNull(result.getUserId());
        assertNotNull(result.getScore());
        assertNotNull(result.getCategories());
        assertNotNull(result.getImmutable());
        assertNotNull(result.getMetadata());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());
        assertNotNull(result.getStructuredAttributes());
    }
}
