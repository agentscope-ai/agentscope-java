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
package io.agentscope.core.llm.interfacesweb.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProtocolJsonUtils Tests")
class ProtocolJsonUtilsTest {

    @Test
    @DisplayName("Should parse optional and required JSON objects")
    void shouldParseOptionalAndRequiredObjects() {
        assertTrue(ProtocolJsonUtils.parseObject(null).isEmpty());
        assertTrue(ProtocolJsonUtils.parseObject(" ").isEmpty());
        assertTrue(ProtocolJsonUtils.parseObject("{bad").isEmpty());
        assertEquals("Paris", ProtocolJsonUtils.parseObject("{\"city\":\"Paris\"}").get("city"));

        assertTrue(ProtocolJsonUtils.parseRequiredObject("", "arguments").isEmpty());
        assertEquals(3, ProtocolJsonUtils.parseRequiredObject("{\"n\":3}", "arguments").get("n"));

        ProtocolException error =
                assertThrows(
                        ProtocolException.class,
                        () -> ProtocolJsonUtils.parseRequiredObject("{bad", "arguments"));
        assertEquals("invalid_request_error", error.getCode());
    }

    @Test
    @DisplayName("Should normalize dynamic JSON values")
    void shouldNormalizeDynamicJsonValues() throws Exception {
        Map<String, Object> map = ProtocolJsonUtils.toMap(Map.of(7, "seven"));
        assertEquals("seven", map.get("7"));
        assertTrue(ProtocolJsonUtils.toMap(null).isEmpty());

        JsonNode node = ProtocolJsonUtils.OBJECT_MAPPER.readTree("{\"enabled\":true}");
        assertSame(node, ProtocolJsonUtils.toJsonNode(node));
        assertNull(ProtocolJsonUtils.toJsonNode(null));
        assertTrue(ProtocolJsonUtils.truthy(node, "enabled"));
        assertFalse(ProtocolJsonUtils.truthy(node, "missing"));

        JsonNode objectNode = ProtocolJsonUtils.toJsonNode(Map.of("name", "lookup"));
        assertEquals("lookup", ProtocolJsonUtils.textValue(objectNode, "name"));
        assertNull(ProtocolJsonUtils.textValue(objectNode, "missing"));
    }

    @Test
    @DisplayName("Should serialize dynamic JSON values")
    void shouldSerializeDynamicJsonValues() {
        assertEquals("{}", ProtocolJsonUtils.toJson(null));
        assertEquals("{\"raw\":true}", ProtocolJsonUtils.toJson("{\"raw\":true}"));
        assertEquals("{\"a\":1}", ProtocolJsonUtils.toJson(Map.of("a", 1)));
    }
}
