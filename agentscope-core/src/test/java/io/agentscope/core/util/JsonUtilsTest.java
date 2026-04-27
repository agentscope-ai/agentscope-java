/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.message.ToolUseBlock;
import java.lang.reflect.Type;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JsonUtilsTest {

    @BeforeEach
    void setUp() {
        JsonUtils.resetToDefault();
    }

    @AfterEach
    void tearDown() {
        JsonUtils.resetToDefault();
    }

    @Test
    void testGetJsonCodecReturnsNonNull() {
        JsonCodec codec = JsonUtils.getJsonCodec();
        assertNotNull(codec);
    }

    @Test
    void testGetJsonCodecReturnsJacksonJsonCodecByDefault() {
        JsonCodec codec = JsonUtils.getJsonCodec();
        assertInstanceOf(JacksonJsonCodec.class, codec);
    }

    @Test
    void testGetJsonCodecReturnsSameInstance() {
        JsonCodec codec1 = JsonUtils.getJsonCodec();
        JsonCodec codec2 = JsonUtils.getJsonCodec();
        assertSame(codec1, codec2);
    }

    @Test
    void testSetJsonCodec() {
        JsonCodec customCodec = new CustomJsonCodec();
        JsonUtils.setJsonCodec(customCodec);

        JsonCodec retrieved = JsonUtils.getJsonCodec();
        assertSame(customCodec, retrieved);
    }

    @Test
    void testSetJsonCodecNull() {
        assertThrows(IllegalArgumentException.class, () -> JsonUtils.setJsonCodec(null));
    }

    @Test
    void testResetToDefault() {
        JsonCodec originalCodec = JsonUtils.getJsonCodec();

        JsonCodec customCodec = new CustomJsonCodec();
        JsonUtils.setJsonCodec(customCodec);
        assertSame(customCodec, JsonUtils.getJsonCodec());

        JsonUtils.resetToDefault();

        JsonCodec resetCodec = JsonUtils.getJsonCodec();
        assertInstanceOf(JacksonJsonCodec.class, resetCodec);
        assertNotSame(originalCodec, resetCodec);
    }

    @Test
    void testJsonCodecFunctionality() {
        JsonCodec codec = JsonUtils.getJsonCodec();

        Map<String, Object> data = Map.of("name", "test", "value", 123);
        String json = codec.toJson(data);

        assertNotNull(json);

        Map<String, Object> result =
                codec.fromJson(json, new TypeReference<Map<String, Object>>() {});
        assertEquals("test", result.get("name"));
        assertEquals(123, result.get("value"));
    }

    @Test
    void testCustomCodecIsUsed() {
        CustomJsonCodec customCodec = new CustomJsonCodec();
        JsonUtils.setJsonCodec(customCodec);

        JsonCodec codec = JsonUtils.getJsonCodec();
        String result = codec.toJson(new Object());

        assertEquals("custom_json", result);
    }

    @Nested
    @DisplayName("isValidJsonObject Tests")
    class IsValidJsonObjectTests {

        @Test
        @DisplayName("Should accept valid JSON object")
        void testValidJsonObject() {
            assertTrue(JsonUtils.isValidJsonObject("{\"key\":\"value\"}"));
            assertTrue(JsonUtils.isValidJsonObject("{}"));
            assertTrue(JsonUtils.isValidJsonObject("{\"a\":1,\"b\":true}"));
        }

        @Test
        @DisplayName("Should reject incomplete JSON")
        void testIncompleteJson() {
            assertFalse(JsonUtils.isValidJsonObject("{\"query\":\"hel"));
            assertFalse(JsonUtils.isValidJsonObject("{\"key\":"));
            assertFalse(JsonUtils.isValidJsonObject("{"));
        }

        @Test
        @DisplayName("Should reject non-object JSON values")
        void testNonObjectJsonValues() {
            assertFalse(JsonUtils.isValidJsonObject("null"));
            assertFalse(JsonUtils.isValidJsonObject("[1,2,3]"));
            assertFalse(JsonUtils.isValidJsonObject("\"just a string\""));
            assertFalse(JsonUtils.isValidJsonObject("42"));
            assertFalse(JsonUtils.isValidJsonObject("true"));
        }

        @Test
        @DisplayName("Should reject null and empty")
        void testNullAndEmpty() {
            assertFalse(JsonUtils.isValidJsonObject(null));
            assertFalse(JsonUtils.isValidJsonObject(""));
        }
    }

    @Nested
    @DisplayName("resolveToolCallArgsJson Tests")
    class ResolveToolCallArgsJsonTests {

        @Test
        @DisplayName("Should return valid content when present")
        void testValidContent() {
            ToolUseBlock block =
                    ToolUseBlock.builder()
                            .id("call_1")
                            .name("tool")
                            .input(Map.of("fallback", "value"))
                            .content("{\"key\":\"value\"}")
                            .build();

            assertEquals("{\"key\":\"value\"}", JsonUtils.resolveToolCallArgsJson(block));
        }

        @Test
        @DisplayName("Should fall back to input when content is invalid JSON")
        void testInvalidContentFallsBackToInput() {
            ToolUseBlock block =
                    ToolUseBlock.builder()
                            .id("call_1")
                            .name("tool")
                            .input(Map.of("city", "Beijing"))
                            .content("{\"query\":\"hel")
                            .build();

            String result = JsonUtils.resolveToolCallArgsJson(block);
            assertTrue(result.contains("city"));
            assertTrue(result.contains("Beijing"));
        }

        @Test
        @DisplayName("Should fall back to input when content is null")
        void testNullContentFallsBackToInput() {
            ToolUseBlock block =
                    ToolUseBlock.builder()
                            .id("call_1")
                            .name("tool")
                            .input(Map.of("key", "value"))
                            .content(null)
                            .build();

            String result = JsonUtils.resolveToolCallArgsJson(block);
            assertTrue(result.contains("key"));
        }

        @Test
        @DisplayName("Should fall back to input when content is empty")
        void testEmptyContentFallsBackToInput() {
            ToolUseBlock block =
                    ToolUseBlock.builder()
                            .id("call_1")
                            .name("tool")
                            .input(Map.of("key", "value"))
                            .content("")
                            .build();

            String result = JsonUtils.resolveToolCallArgsJson(block);
            assertTrue(result.contains("key"));
        }

        @Test
        @DisplayName("Should reject non-object JSON content like arrays")
        void testNonObjectJsonContentFallsBackToInput() {
            ToolUseBlock block =
                    ToolUseBlock.builder()
                            .id("call_1")
                            .name("tool")
                            .input(Map.of("key", "value"))
                            .content("[1,2,3]")
                            .build();

            String result = JsonUtils.resolveToolCallArgsJson(block);
            assertTrue(result.contains("key"));
        }

        @Test
        @DisplayName("Should return {} when both content and input are empty")
        void testEmptyContentAndInput() {
            ToolUseBlock block =
                    ToolUseBlock.builder()
                            .id("call_1")
                            .name("tool")
                            .input(Map.of())
                            .content("")
                            .build();

            assertEquals("{}", JsonUtils.resolveToolCallArgsJson(block));
        }
    }

    private static class CustomJsonCodec implements JsonCodec {

        @Override
        public String toJson(Object obj) {
            return "custom_json";
        }

        @Override
        public String toPrettyJson(Object obj) {
            return "custom_pretty_json";
        }

        @Override
        public <T> T fromJson(String json, Class<T> type) {
            return null;
        }

        @Override
        public <T> T fromJson(String json, TypeReference<T> typeRef) {
            return null;
        }

        @Override
        public <T> T convertValue(Object from, Class<T> toType) {
            return null;
        }

        @Override
        public <T> T convertValue(Object from, TypeReference<T> toTypeRef) {
            return null;
        }

        @Override
        public Object convertValue(Object from, Type toType) {
            return null;
        }
    }
}
