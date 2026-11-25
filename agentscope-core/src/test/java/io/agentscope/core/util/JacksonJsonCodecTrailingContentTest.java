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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JacksonJsonCodecTrailingContentTest {
    @ParameterizedTest
    @ValueSource(strings = {"{\"x\":1} garbage", "{\"x\":1} {}", "{\"x\":1} null", "{\"x\":1} 42"})
    void rejectsContentAfterTheRootValue(String json) {
        JacksonJsonCodec codec = new JacksonJsonCodec();
        assertThrows(JsonException.class, () -> codec.fromJson(json, Map.class));
        assertThrows(
                JsonException.class,
                () -> codec.fromJson(json, new TypeReference<Map<String, Object>>() {}));
        assertFalse(JsonUtils.isValidJsonObject(json));
        ToolUseBlock call =
                ToolUseBlock.builder()
                        .id("call")
                        .name("test")
                        .input(Map.of("safe", true))
                        .content(json)
                        .build();
        assertEquals("{\"safe\":true}", JsonUtils.resolveToolCallArgsJson(call));
    }

    @Test
    void acceptsTrailingJsonWhitespace() {
        JacksonJsonCodec codec = new JacksonJsonCodec();
        String json = "{\"x\":1} \r\n\t";
        assertEquals(Map.of("x", 1), codec.fromJson(json, Map.class));
        assertEquals(
                Map.of("x", 1), codec.fromJson(json, new TypeReference<Map<String, Object>>() {}));
    }

    @Test
    void validatesCustomMapperReadsWithoutChangingItsConfiguration() {
        ObjectMapper mapper = new ObjectMapper();
        JacksonJsonCodec codec = new JacksonJsonCodec(mapper);
        assertThrows(JsonException.class, () -> codec.fromJson("{} []", Map.class));
        assertFalse(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
    }
}
