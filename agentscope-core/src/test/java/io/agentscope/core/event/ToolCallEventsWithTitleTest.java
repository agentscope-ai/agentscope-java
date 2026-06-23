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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Tool*Event toolCallTitle field tests")
class ToolCallEventsWithTitleTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── ToolCallStartEvent ───────────────────────────────────────────────────

    @Nested
    @DisplayName("ToolCallStartEvent")
    class ToolCallStartEventTests {

        @Test
        @DisplayName("Constructor with title stores title")
        void testConstructorWithTitle() {
            ToolCallStartEvent event =
                    new ToolCallStartEvent("reply1", "call1", "my_tool", "My Tool");
            assertEquals("reply1", event.getReplyId());
            assertEquals("call1", event.getToolCallId());
            assertEquals("my_tool", event.getToolCallName());
            assertEquals("My Tool", event.getToolCallTitle());
        }

        @Test
        @DisplayName("Constructor with null title stores null")
        void testConstructorNullTitle() {
            ToolCallStartEvent event = new ToolCallStartEvent("reply1", "call1", "my_tool", null);
            assertNull(event.getToolCallTitle());
        }

        @Test
        @DisplayName("Title is serialized in JSON")
        void testJsonSerializationTitle() throws JsonProcessingException {
            ToolCallStartEvent event =
                    new ToolCallStartEvent("reply1", "call1", "my_tool", "My Tool");
            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"toolCallTitle\":\"My Tool\""));
        }

        @Test
        @DisplayName("Title is deserialized from JSON")
        void testJsonDeserializationTitle() throws JsonProcessingException {
            String json =
                    """
                    {
                        "type": "TOOL_CALL_START",
                        "replyId": "r1",
                        "toolCallId": "c1",
                        "toolCallName": "tool",
                        "toolCallTitle": "Tool Title"
                    }
                    """;
            ToolCallStartEvent event = objectMapper.readValue(json, ToolCallStartEvent.class);
            assertEquals("Tool Title", event.getToolCallTitle());
        }
    }

    // ── ToolCallEndEvent ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("ToolCallEndEvent")
    class ToolCallEndEventTests {

        @Test
        @DisplayName("Constructor with title stores title")
        void testConstructorWithTitle() {
            ToolCallEndEvent event = new ToolCallEndEvent("reply1", "call1", "my_tool", "My Tool");
            assertEquals("My Tool", event.getToolCallTitle());
        }

        @Test
        @DisplayName("Constructor with null title stores null")
        void testConstructorNullTitle() {
            ToolCallEndEvent event = new ToolCallEndEvent("reply1", "call1", "my_tool", null);
            assertNull(event.getToolCallTitle());
        }

        @Test
        @DisplayName("Title is serialized in JSON")
        void testJsonSerializationTitle() throws JsonProcessingException {
            ToolCallEndEvent event = new ToolCallEndEvent("reply1", "call1", "my_tool", "My Tool");
            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"toolCallTitle\":\"My Tool\""));
        }

        @Test
        @DisplayName("Title is deserialized from JSON")
        void testJsonDeserializationTitle() throws JsonProcessingException {
            String json =
                    """
                    {
                        "type": "TOOL_CALL_END",
                        "replyId": "r1",
                        "toolCallId": "c1",
                        "toolCallName": "tool",
                        "toolCallTitle": "Tool End Title"
                    }
                    """;
            ToolCallEndEvent event = objectMapper.readValue(json, ToolCallEndEvent.class);
            assertEquals("Tool End Title", event.getToolCallTitle());
        }
    }

    // ── ToolCallDeltaEvent ───────────────────────────────────────────────────

    @Nested
    @DisplayName("ToolCallDeltaEvent")
    class ToolCallDeltaEventTests {

        @Test
        @DisplayName("Constructor with title stores title and delta")
        void testConstructorWithTitle() {
            ToolCallDeltaEvent event =
                    new ToolCallDeltaEvent("reply1", "call1", "my_tool", "My Tool", "{\"x\":1}");
            assertEquals("My Tool", event.getToolCallTitle());
            assertEquals("{\"x\":1}", event.getDelta());
        }

        @Test
        @DisplayName("Constructor with null title stores null")
        void testConstructorNullTitle() {
            ToolCallDeltaEvent event =
                    new ToolCallDeltaEvent("reply1", "call1", "my_tool", null, "delta");
            assertNull(event.getToolCallTitle());
        }

        @Test
        @DisplayName("Title is serialized in JSON")
        void testJsonSerializationTitle() throws JsonProcessingException {
            ToolCallDeltaEvent event =
                    new ToolCallDeltaEvent("reply1", "call1", "my_tool", "Delta Tool", "{}");
            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"toolCallTitle\":\"Delta Tool\""));
        }

        @Test
        @DisplayName("Title is deserialized from JSON")
        void testJsonDeserializationTitle() throws JsonProcessingException {
            String json =
                    """
                    {
                        "type": "TOOL_CALL_DELTA",
                        "replyId": "r1",
                        "toolCallId": "c1",
                        "toolCallName": "tool",
                        "toolCallTitle": "Delta Title",
                        "delta": "chunk"
                    }
                    """;
            ToolCallDeltaEvent event = objectMapper.readValue(json, ToolCallDeltaEvent.class);
            assertEquals("Delta Title", event.getToolCallTitle());
            assertEquals("chunk", event.getDelta());
        }
    }

    // ── ToolResultStartEvent ─────────────────────────────────────────────────

    @Nested
    @DisplayName("ToolResultStartEvent")
    class ToolResultStartEventTests {

        @Test
        @DisplayName("Constructor with title stores title")
        void testConstructorWithTitle() {
            ToolResultStartEvent event =
                    new ToolResultStartEvent("reply1", "call1", "my_tool", "My Tool");
            assertEquals("My Tool", event.getToolCallTitle());
        }

        @Test
        @DisplayName("Backward-compatible constructor (3 args) has null title")
        void testBackwardCompatibleConstructorNullTitle() {
            ToolResultStartEvent event = new ToolResultStartEvent("reply1", "call1", "my_tool");
            assertNull(event.getToolCallTitle());
        }

        @Test
        @DisplayName("Title is serialized in JSON")
        void testJsonSerializationTitle() throws JsonProcessingException {
            ToolResultStartEvent event =
                    new ToolResultStartEvent("reply1", "call1", "my_tool", "Result Start");
            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"toolCallTitle\":\"Result Start\""));
        }
    }

    // ── ToolResultEndEvent ───────────────────────────────────────────────────

    @Nested
    @DisplayName("ToolResultEndEvent")
    class ToolResultEndEventTests {

        @Test
        @DisplayName("Constructor with title stores title")
        void testConstructorWithTitle() {
            ToolResultEndEvent event =
                    new ToolResultEndEvent(
                            "reply1", "call1", "my_tool", "My Tool", ToolResultState.SUCCESS);
            assertEquals("My Tool", event.getToolCallTitle());
            assertEquals(ToolResultState.SUCCESS, event.getState());
        }

        @Test
        @DisplayName("Backward-compatible constructor (4 args, no title) has null title")
        void testBackwardCompatibleConstructorNullTitle() {
            ToolResultEndEvent event =
                    new ToolResultEndEvent("reply1", "call1", "my_tool", ToolResultState.SUCCESS);
            assertNull(event.getToolCallTitle());
        }

        @Test
        @DisplayName("Title is serialized in JSON")
        void testJsonSerializationTitle() throws JsonProcessingException {
            ToolResultEndEvent event =
                    new ToolResultEndEvent(
                            "reply1", "call1", "my_tool", "End Tool", ToolResultState.SUCCESS);
            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"toolCallTitle\":\"End Tool\""));
        }

        @Test
        @DisplayName("Title is deserialized from JSON")
        void testJsonDeserializationTitle() throws JsonProcessingException {
            String json =
                    """
                    {
                        "type": "TOOL_RESULT_END",
                        "replyId": "r1",
                        "toolCallId": "c1",
                        "toolCallName": "tool",
                        "toolCallTitle": "End Title",
                        "state": "success"
                    }
                    """;
            ToolResultEndEvent event = objectMapper.readValue(json, ToolResultEndEvent.class);
            assertEquals("End Title", event.getToolCallTitle());
            assertEquals(ToolResultState.SUCCESS, event.getState());
        }
    }

    // ── ToolResultTextDeltaEvent ─────────────────────────────────────────────

    @Nested
    @DisplayName("ToolResultTextDeltaEvent")
    class ToolResultTextDeltaEventTests {

        @Test
        @DisplayName("Constructor with title stores title and delta")
        void testConstructorWithTitle() {
            ToolResultTextDeltaEvent event =
                    new ToolResultTextDeltaEvent(
                            "reply1", "call1", "my_tool", "My Tool", "text chunk");
            assertEquals("My Tool", event.getToolCallTitle());
            assertEquals("text chunk", event.getDelta());
        }

        @Test
        @DisplayName("Backward-compatible constructor (4 args, no title) has null title")
        void testBackwardCompatibleConstructorNullTitle() {
            ToolResultTextDeltaEvent event =
                    new ToolResultTextDeltaEvent("reply1", "call1", "my_tool", "delta");
            assertNull(event.getToolCallTitle());
            assertEquals("delta", event.getDelta());
        }

        @Test
        @DisplayName("Title is serialized in JSON")
        void testJsonSerializationTitle() throws JsonProcessingException {
            ToolResultTextDeltaEvent event =
                    new ToolResultTextDeltaEvent(
                            "reply1", "call1", "my_tool", "Text Delta Tool", "chunk");
            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"toolCallTitle\":\"Text Delta Tool\""));
        }

        @Test
        @DisplayName("Title is deserialized from JSON")
        void testJsonDeserializationTitle() throws JsonProcessingException {
            String json =
                    """
                    {
                        "type": "TOOL_RESULT_TEXT_DELTA",
                        "replyId": "r1",
                        "toolCallId": "c1",
                        "toolCallName": "tool",
                        "toolCallTitle": "Text Title",
                        "delta": "text"
                    }
                    """;
            ToolResultTextDeltaEvent event =
                    objectMapper.readValue(json, ToolResultTextDeltaEvent.class);
            assertEquals("Text Title", event.getToolCallTitle());
        }
    }

    // ── ToolResultDataDeltaEvent ─────────────────────────────────────────────

    @Nested
    @DisplayName("ToolResultDataDeltaEvent")
    class ToolResultDataDeltaEventTests {

        @Test
        @DisplayName("Constructor with title stores title and data")
        void testConstructorWithTitle() {
            TextBlock data = TextBlock.builder().text("data chunk").build();
            ToolResultDataDeltaEvent event =
                    new ToolResultDataDeltaEvent("reply1", "call1", "my_tool", "My Tool", data);
            assertEquals("My Tool", event.getToolCallTitle());
            assertEquals(data, event.getData());
        }

        @Test
        @DisplayName("Constructor with null title stores null")
        void testConstructorNullTitle() {
            TextBlock data = TextBlock.builder().text("data").build();
            ToolResultDataDeltaEvent event =
                    new ToolResultDataDeltaEvent("reply1", "call1", "my_tool", null, data);
            assertNull(event.getToolCallTitle());
        }

        @Test
        @DisplayName("Title is serialized in JSON")
        void testJsonSerializationTitle() throws JsonProcessingException {
            TextBlock data = TextBlock.builder().text("block").build();
            ToolResultDataDeltaEvent event =
                    new ToolResultDataDeltaEvent("reply1", "call1", "my_tool", "Data Tool", data);
            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"toolCallTitle\":\"Data Tool\""));
        }
    }
}
