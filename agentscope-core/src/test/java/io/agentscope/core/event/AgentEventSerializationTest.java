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
package io.agentscope.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultState;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class AgentEventSerializationTest {

    private final ObjectMapper jackson2 = new ObjectMapper();

    @ParameterizedTest
    @MethodSource("events")
    void jackson2UsesOneTypePropertyAndPreservesSubtype(AgentEvent event, String expectedType)
            throws Exception {
        String json = jackson2.writeValueAsString(event);
        assertSingleTypeProperty(json, expectedType);
        assertRoundTrip(event, json, jackson2.readValue(json, AgentEvent.class));

        String baseTypedJson = jackson2.writerFor(AgentEvent.class).writeValueAsString(event);
        assertSingleTypeProperty(baseTypedJson, expectedType);
        assertRoundTrip(event, baseTypedJson, jackson2.readValue(baseTypedJson, AgentEvent.class));
    }

    @ParameterizedTest
    @MethodSource("events")
    void jackson3UsesOneTypePropertyAndPreservesSubtype(AgentEvent event, String expectedType)
            throws Exception {
        JsonMapper jackson3 = JsonMapper.builder().build();
        String json = jackson3.writeValueAsString(event);
        assertSingleTypeProperty(json, expectedType);
        assertRoundTrip(event, json, jackson3.readValue(json, AgentEvent.class));

        String baseTypedJson = jackson3.writerFor(AgentEvent.class).writeValueAsString(event);
        assertSingleTypeProperty(baseTypedJson, expectedType);
        assertRoundTrip(event, baseTypedJson, jackson3.readValue(baseTypedJson, AgentEvent.class));
    }

    private void assertSingleTypeProperty(String json, String expectedType) throws Exception {
        // Inspect raw root fields before reading a tree, which would hide duplicate keys.
        try (JsonParser parser = jackson2.createParser(json)) {
            assertEquals(JsonToken.START_OBJECT, parser.nextToken());
            int typeCount = 0;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                assertEquals(JsonToken.FIELD_NAME, parser.currentToken());
                String fieldName = parser.currentName();
                parser.nextToken();
                if ("type".equals(fieldName)) {
                    typeCount++;
                    assertEquals(JsonToken.VALUE_STRING, parser.currentToken());
                    assertEquals(expectedType, parser.getText());
                }
                parser.skipChildren();
            }
            assertEquals(1, typeCount, json);
            assertNull(parser.nextToken());
        }
    }

    private void assertRoundTrip(AgentEvent original, String json, AgentEvent restored)
            throws Exception {
        assertEquals(original.getClass(), restored.getClass());
        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getCreatedAt(), restored.getCreatedAt());
        assertEquals(jackson2.readTree(json), jackson2.valueToTree(restored));
    }

    private static Stream<Arguments> events() {
        String id = "event-1";
        String createdAt = "2026-06-27T14:00:00Z";
        return Stream.of(
                Arguments.of(
                        new AgentStartEvent(
                                id, createdAt, "session-1", "reply-1", "assistant", "assistant"),
                        "AGENT_START"),
                Arguments.of(new AgentEndEvent(id, createdAt, "reply-1"), "AGENT_END"),
                Arguments.of(
                        new ToolResultEndEvent(
                                id,
                                createdAt,
                                "reply-1",
                                "tool-call-1",
                                "search",
                                ToolResultState.SUCCESS,
                                Map.of("cost", 1)),
                        "TOOL_RESULT_END"),
                Arguments.of(
                        new CustomEvent(
                                id, createdAt, "progress", Map.of("type", "status", "count", 1)),
                        "CUSTOM"));
    }
}
