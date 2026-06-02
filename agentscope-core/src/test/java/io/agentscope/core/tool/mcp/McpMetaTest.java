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
package io.agentscope.core.tool.mcp;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpMetaTest {

    // ==================== Constructor & getEntries ====================

    @Test
    void testConstructor_WithEntries() {
        McpMeta meta = new McpMeta(Map.of("traceId", "abc-123", "userId", "u456"));
        assertEquals(2, meta.entries().size());
        assertEquals("abc-123", meta.entries().get("traceId"));
        assertEquals("u456", meta.entries().get("userId"));
    }

    @Test
    void testConstructor_WithNullMap() {
        McpMeta meta = new McpMeta(null);
        assertNotNull(meta.entries().values());
        assertTrue(meta.entries().isEmpty());
    }

    @Test
    void testConstructor_WithEmptyMap() {
        McpMeta meta = new McpMeta(Collections.emptyMap());
        assertNotNull(meta.entries());
        assertTrue(meta.entries().isEmpty());
    }

    @Test
    void testConstructor_DefensiveCopy() {
        Map<String, Object> original = new java.util.HashMap<>();
        original.put("key1", "value1");

        McpMeta meta = new McpMeta(original);

        // Mutating the original map should NOT affect McpMeta
        original.put("key2", "value2");
        assertEquals(1, meta.entries().size());
        assertFalse(meta.entries().containsKey("key2"));
    }

    @Test
    void testGetEntries_IsUnmodifiable() {
        McpMeta meta = new McpMeta(Map.of("key1", "value1"));
        assertTrue(
                Collections.unmodifiableMap(Map.of())
                        .getClass()
                        .isAssignableFrom(meta.entries().getClass()));
    }

    // ==================== isEmpty ====================

    @Test
    void testIsEmpty_WithEntries() {
        McpMeta meta = new McpMeta(Map.of("key1", "value1"));
        assertFalse(meta.isEmpty());
    }

    @Test
    void testIsEmpty_WithNullMap() {
        McpMeta meta = new McpMeta(null);
        assertTrue(meta.isEmpty());
    }

    @Test
    void testIsEmpty_WithEmptyMap() {
        McpMeta meta = new McpMeta(Collections.emptyMap());
        assertTrue(meta.isEmpty());
    }

    // ==================== merge ====================

    @Test
    void testMerge_SingleMeta() {
        McpMeta meta = new McpMeta(Map.of("traceId", "abc-123"));
        McpMeta merged = McpMeta.merge(meta);
        assertEquals(1, merged.entries().size());
        assertEquals("abc-123", merged.entries().get("traceId"));
    }

    @Test
    void testMerge_MultipleMetas() {
        McpMeta meta1 = new McpMeta(Map.of("traceId", "abc-123"));
        McpMeta meta2 = new McpMeta(Map.of("callbackUrl", "https://hook.example.com"));
        McpMeta merged = McpMeta.merge(meta1, meta2);
        assertEquals(2, merged.entries().size());
        assertEquals("abc-123", merged.entries().get("traceId"));
        assertEquals("https://hook.example.com", merged.entries().get("callbackUrl"));
    }

    @Test
    void testMerge_LastWinsOnDuplicateKey() {
        McpMeta meta1 = new McpMeta(Map.of("traceId", "old-value"));
        McpMeta meta2 = new McpMeta(Map.of("traceId", "new-value"));
        McpMeta merged = McpMeta.merge(meta1, meta2);
        assertEquals(1, merged.entries().size());
        assertEquals("new-value", merged.entries().get("traceId"));
    }

    @Test
    void testMerge_SkipsNulls() {
        McpMeta meta1 = new McpMeta(Map.of("traceId", "abc-123"));
        McpMeta merged = McpMeta.merge(null, meta1, null);
        assertEquals(1, merged.entries().size());
        assertEquals("abc-123", merged.entries().get("traceId"));
    }

    @Test
    void testMerge_SkipsEmptyMetas() {
        McpMeta meta1 = new McpMeta(Map.of("traceId", "abc-123"));
        McpMeta empty = new McpMeta(null);
        McpMeta merged = McpMeta.merge(meta1, empty);
        assertEquals(1, merged.entries().size());
        assertEquals("abc-123", merged.entries().get("traceId"));
    }

    @Test
    void testMerge_AllNulls() {
        McpMeta merged = McpMeta.merge(null, null);
        assertNotNull(merged);
        assertTrue(merged.isEmpty());
    }

    @Test
    void testMerge_NullArray() {
        McpMeta merged = McpMeta.merge((McpMeta[]) null);
        assertNotNull(merged);
        assertTrue(merged.isEmpty());
    }

    @Test
    void testMerge_EmptyArray() {
        McpMeta merged = McpMeta.merge();
        assertNotNull(merged);
        assertTrue(merged.isEmpty());
    }

    // ==================== equals & hashCode ====================

    @Test
    void testEquals_SameEntries() {
        McpMeta meta1 = new McpMeta(Map.of("key1", "value1"));
        McpMeta meta2 = new McpMeta(Map.of("key1", "value1"));
        assertEquals(meta1, meta2);
        assertEquals(meta1.hashCode(), meta2.hashCode());
    }

    @Test
    void testEquals_DifferentEntries() {
        McpMeta meta1 = new McpMeta(Map.of("key1", "value1"));
        McpMeta meta2 = new McpMeta(Map.of("key1", "value2"));
        assertNotEquals(meta1, meta2);
    }

    @Test
    void testEquals_BothEmpty() {
        McpMeta meta1 = new McpMeta(null);
        McpMeta meta2 = new McpMeta(Collections.emptyMap());
        assertEquals(meta1, meta2);
    }

    @Test
    void testEquals_Self() {
        McpMeta meta = new McpMeta(Map.of("key1", "value1"));
        assertEquals(meta, meta);
    }

    @Test
    void testEquals_Null() {
        McpMeta meta = new McpMeta(Map.of("key1", "value1"));
        assertNotEquals(null, meta);
    }

    @Test
    void testEquals_DifferentType() {
        McpMeta meta = new McpMeta(Map.of("key1", "value1"));
        assertNotEquals("not-a-meta", meta);
    }

    // ==================== toString ====================

    @Test
    void testToString_ContainsEntries() {
        McpMeta meta = new McpMeta(Map.of("traceId", "abc-123"));
        String str = meta.toString();
        assertTrue(str.startsWith("McpMeta"));
        assertTrue(str.contains("traceId"));
        assertTrue(str.contains("abc-123"));
    }

    @Test
    void testToString_Empty() {
        McpMeta meta = new McpMeta(null);
        String str = meta.toString();
        assertTrue(str.startsWith("McpMeta"));
    }

    // ==================== Value types ====================

    @Test
    void testEntries_NestedValues() {
        McpMeta meta =
                new McpMeta(
                        Map.of(
                                "nested",
                                Map.of("inner", "value"),
                                "list",
                                java.util.List.of("a", "b")));
        assertEquals(Map.of("inner", "value"), meta.entries().get("nested"));
        assertEquals(java.util.List.of("a", "b"), meta.entries().get("list"));
    }

    @Test
    void testEntries_NumericValues() {
        McpMeta meta = new McpMeta(Map.of("count", 42, "ratio", 3.14));
        assertEquals(42, meta.entries().get("count"));
        assertEquals(3.14, meta.entries().get("ratio"));
    }
}
