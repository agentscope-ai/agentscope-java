/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.agui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.StateDeltaEvent.JsonPatchOperation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for all AG-UI event types.
 */
class AguiEventTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Nested
    class RunStartedEventTest {

        @Test
        void testCreation() {
            RunStartedEvent event = new RunStartedEvent("thread-1", "run-1");

            assertEquals(AguiEventType.RUN_STARTED, event.getType());
            assertEquals("thread-1", event.getThreadId());
            assertEquals("run-1", event.getRunId());
        }

        @Test
        void testToString() {
            RunStartedEvent event = new RunStartedEvent("thread-1", "run-1");

            String str = event.toString();
            assertTrue(str.contains("thread-1"));
            assertTrue(str.contains("run-1"));
        }

        @Test
        void testNullThreadIdThrows() {
            assertThrows(NullPointerException.class, () -> new RunStartedEvent(null, "run-1"));
        }

        @Test
        void testNullRunIdThrows() {
            assertThrows(NullPointerException.class, () -> new RunStartedEvent("thread-1", null));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            RunStartedEvent event = new RunStartedEvent("thread-1", "run-1");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"RUN_STARTED\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof RunStartedEvent);
            assertEquals("thread-1", deserialized.getThreadId());
        }
    }

    @Nested
    class RunFinishedEventTest {

        @Test
        void testCreation() {
            RunFinishedEvent event = new RunFinishedEvent("thread-2", "run-2");

            assertEquals(AguiEventType.RUN_FINISHED, event.getType());
            assertEquals("thread-2", event.getThreadId());
            assertEquals("run-2", event.getRunId());
        }

        @Test
        void testToString() {
            RunFinishedEvent event = new RunFinishedEvent("thread-2", "run-2");

            String str = event.toString();
            assertTrue(str.contains("thread-2"));
            assertTrue(str.contains("run-2"));
        }

        @Test
        void testNullThreadIdThrows() {
            assertThrows(NullPointerException.class, () -> new RunFinishedEvent(null, "run-1"));
        }

        @Test
        void testNullRunIdThrows() {
            assertThrows(NullPointerException.class, () -> new RunFinishedEvent("thread-1", null));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            RunFinishedEvent event = new RunFinishedEvent("thread-2", "run-2");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"RUN_FINISHED\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof RunFinishedEvent);
        }
    }

    @Nested
    class TextMessageStartEventTest {

        @Test
        void testCreation() {
            TextMessageStartEvent event =
                    new TextMessageStartEvent("thread-1", "run-1", "msg-1", "assistant");

            assertEquals(AguiEventType.TEXT_MESSAGE_START, event.getType());
            assertEquals("thread-1", event.getThreadId());
            assertEquals("run-1", event.getRunId());
            assertEquals("msg-1", event.getMessageId());
            assertEquals("assistant", event.getRole());
        }

        @Test
        void testToString() {
            TextMessageStartEvent event =
                    new TextMessageStartEvent("thread-1", "run-1", "msg-1", "user");

            String str = event.toString();
            assertTrue(str.contains("msg-1"));
            assertTrue(str.contains("user"));
        }

        @Test
        void testNullMessageIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new TextMessageStartEvent("thread-1", "run-1", null, "assistant"));
        }

        @Test
        void testNullRoleThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new TextMessageStartEvent("thread-1", "run-1", "msg-1", null));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            TextMessageStartEvent event =
                    new TextMessageStartEvent("thread-1", "run-1", "msg-1", "assistant");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"TEXT_MESSAGE_START\""));
            assertTrue(json.contains("\"messageId\":\"msg-1\""));
            assertTrue(json.contains("\"role\":\"assistant\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof TextMessageStartEvent);
            TextMessageStartEvent cast = (TextMessageStartEvent) deserialized;
            assertEquals("msg-1", cast.getMessageId());
            assertEquals("assistant", cast.getRole());
        }
    }

    @Nested
    class TextMessageContentEventTest {

        @Test
        void testCreation() {
            TextMessageContentEvent event =
                    new TextMessageContentEvent("thread-1", "run-1", "msg-1", "Hello");

            assertEquals(AguiEventType.TEXT_MESSAGE_CONTENT, event.getType());
            assertEquals("msg-1", event.getMessageId());
            assertEquals("Hello", event.getDelta());
        }

        @Test
        void testToString() {
            TextMessageContentEvent event =
                    new TextMessageContentEvent("thread-1", "run-1", "msg-1", "Test");

            String str = event.toString();
            assertTrue(str.contains("msg-1"));
            assertTrue(str.contains("Test"));
        }

        @Test
        void testNullDeltaThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new TextMessageContentEvent("thread-1", "run-1", "msg-1", null));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            TextMessageContentEvent event =
                    new TextMessageContentEvent("thread-1", "run-1", "msg-1", "Hello World");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"TEXT_MESSAGE_CONTENT\""));
            assertTrue(json.contains("\"delta\":\"Hello World\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof TextMessageContentEvent);
            assertEquals("Hello World", ((TextMessageContentEvent) deserialized).getDelta());
        }
    }

    @Nested
    class TextMessageEndEventTest {

        @Test
        void testCreation() {
            TextMessageEndEvent event = new TextMessageEndEvent("thread-1", "run-1", "msg-1");

            assertEquals(AguiEventType.TEXT_MESSAGE_END, event.getType());
            assertEquals("msg-1", event.getMessageId());
        }

        @Test
        void testToString() {
            TextMessageEndEvent event = new TextMessageEndEvent("thread-1", "run-1", "msg-1");

            String str = event.toString();
            assertTrue(str.contains("msg-1"));
        }

        @Test
        void testNullMessageIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new TextMessageEndEvent("thread-1", "run-1", null));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            TextMessageEndEvent event = new TextMessageEndEvent("thread-1", "run-1", "msg-1");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"TEXT_MESSAGE_END\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof TextMessageEndEvent);
        }
    }

    @Nested
    class ToolCallStartEventTest {

        @Test
        void testCreation() {
            ToolCallStartEvent event =
                    new ToolCallStartEvent("thread-1", "run-1", "tc-1", "get_weather");

            assertEquals(AguiEventType.TOOL_CALL_START, event.getType());
            assertEquals("tc-1", event.getToolCallId());
            assertEquals("get_weather", event.getToolCallName());
        }

        @Test
        void testToString() {
            ToolCallStartEvent event =
                    new ToolCallStartEvent("thread-1", "run-1", "tc-1", "calculator");

            String str = event.toString();
            assertTrue(str.contains("tc-1"));
            assertTrue(str.contains("calculator"));
        }

        @Test
        void testNullToolCallIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ToolCallStartEvent("thread-1", "run-1", null, "tool"));
        }

        @Test
        void testNullToolCallNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ToolCallStartEvent("thread-1", "run-1", "tc-1", null));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            ToolCallStartEvent event =
                    new ToolCallStartEvent("thread-1", "run-1", "tc-1", "get_weather");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"TOOL_CALL_START\""));
            assertTrue(json.contains("\"toolCallId\":\"tc-1\""));
            assertTrue(json.contains("\"toolCallName\":\"get_weather\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof ToolCallStartEvent);
        }
    }

    @Nested
    class ToolCallArgsEventTest {

        @Test
        void testCreation() {
            ToolCallArgsEvent event =
                    new ToolCallArgsEvent("thread-1", "run-1", "tc-1", "{\"city\":\"Beijing\"}");

            assertEquals(AguiEventType.TOOL_CALL_ARGS, event.getType());
            assertEquals("tc-1", event.getToolCallId());
            assertEquals("{\"city\":\"Beijing\"}", event.getDelta());
        }

        @Test
        void testToString() {
            ToolCallArgsEvent event =
                    new ToolCallArgsEvent("thread-1", "run-1", "tc-1", "{\"key\":\"value\"}");

            String str = event.toString();
            assertTrue(str.contains("tc-1"));
            assertTrue(str.contains("value"));
        }

        @Test
        void testNullToolCallIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ToolCallArgsEvent("thread-1", "run-1", null, "{}"));
        }

        @Test
        void testNullDeltaThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ToolCallArgsEvent("thread-1", "run-1", "tc-1", null));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            ToolCallArgsEvent event =
                    new ToolCallArgsEvent("thread-1", "run-1", "tc-1", "{\"key\":\"value\"}");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"TOOL_CALL_ARGS\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof ToolCallArgsEvent);
        }
    }

    @Nested
    class ToolCallEndEventTest {

        @Test
        void testCreation() {
            ToolCallEndEvent event = new ToolCallEndEvent("thread-1", "run-1", "tc-1", "Success");

            assertEquals(AguiEventType.TOOL_CALL_END, event.getType());
            assertEquals("tc-1", event.getToolCallId());
            assertEquals("Success", event.getResult());
        }

        @Test
        void testWithNullResult() {
            ToolCallEndEvent event = new ToolCallEndEvent("thread-1", "run-1", "tc-1", null);

            assertNull(event.getResult());
        }

        @Test
        void testToString() {
            ToolCallEndEvent event = new ToolCallEndEvent("thread-1", "run-1", "tc-1", "Result");

            String str = event.toString();
            assertTrue(str.contains("tc-1"));
            assertTrue(str.contains("Result"));
        }

        @Test
        void testNullToolCallIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ToolCallEndEvent("thread-1", "run-1", null, "result"));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            ToolCallEndEvent event =
                    new ToolCallEndEvent("thread-1", "run-1", "tc-1", "Operation completed");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"TOOL_CALL_END\""));
            assertTrue(json.contains("\"result\":\"Operation completed\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof ToolCallEndEvent);
        }
    }

    @Nested
    class StateSnapshotEventTest {

        @Test
        void testCreation() {
            Map<String, Object> state = Map.of("key1", "value1", "key2", 42);
            StateSnapshotEvent event = new StateSnapshotEvent("thread-1", "run-1", state);

            assertEquals(AguiEventType.STATE_SNAPSHOT, event.getType());
            assertEquals("value1", event.getSnapshot().get("key1"));
            assertEquals(42, event.getSnapshot().get("key2"));
        }

        @Test
        void testNullSnapshotCreatesEmptyMap() {
            StateSnapshotEvent event = new StateSnapshotEvent("thread-1", "run-1", null);

            assertNotNull(event.getSnapshot());
            assertTrue(event.getSnapshot().isEmpty());
        }

        @Test
        void testSnapshotIsImmutable() {
            Map<String, Object> state = Map.of("key", "value");
            StateSnapshotEvent event = new StateSnapshotEvent("thread-1", "run-1", state);

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> event.getSnapshot().put("new", "value"));
        }

        @Test
        void testToString() {
            StateSnapshotEvent event =
                    new StateSnapshotEvent("thread-1", "run-1", Map.of("key", "value"));

            String str = event.toString();
            assertTrue(str.contains("thread-1"));
            assertTrue(str.contains("snapshot"));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            StateSnapshotEvent event =
                    new StateSnapshotEvent(
                            "thread-1", "run-1", Map.of("count", 10, "name", "test"));

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"STATE_SNAPSHOT\""));
            assertTrue(json.contains("\"snapshot\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof StateSnapshotEvent);
        }
    }

    @Nested
    class StateDeltaEventTest {

        @Test
        void testCreation() {
            List<JsonPatchOperation> ops =
                    List.of(
                            JsonPatchOperation.add("/path1", "value1"),
                            JsonPatchOperation.remove("/path2"));
            StateDeltaEvent event = new StateDeltaEvent("thread-1", "run-1", ops);

            assertEquals(AguiEventType.STATE_DELTA, event.getType());
            assertEquals(2, event.getDelta().size());
        }

        @Test
        void testNullDeltaCreatesEmptyList() {
            StateDeltaEvent event = new StateDeltaEvent("thread-1", "run-1", null);

            assertNotNull(event.getDelta());
            assertTrue(event.getDelta().isEmpty());
        }

        @Test
        void testDeltaIsImmutable() {
            List<JsonPatchOperation> ops = List.of(JsonPatchOperation.add("/path", "value"));
            StateDeltaEvent event = new StateDeltaEvent("thread-1", "run-1", ops);

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> event.getDelta().add(JsonPatchOperation.remove("/test")));
        }

        @Test
        void testToString() {
            StateDeltaEvent event =
                    new StateDeltaEvent(
                            "thread-1",
                            "run-1",
                            List.of(JsonPatchOperation.replace("/key", "newValue")));

            String str = event.toString();
            assertTrue(str.contains("delta"));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            StateDeltaEvent event =
                    new StateDeltaEvent(
                            "thread-1",
                            "run-1",
                            List.of(
                                    JsonPatchOperation.add("/new", "value"),
                                    JsonPatchOperation.remove("/old")));

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"STATE_DELTA\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof StateDeltaEvent);
        }
    }

    @Nested
    class JsonPatchOperationTest {

        @Test
        void testAddOperation() {
            JsonPatchOperation op = JsonPatchOperation.add("/path", "value");

            assertEquals("add", op.getOp());
            assertEquals("/path", op.getPath());
            assertEquals("value", op.getValue());
            assertNull(op.getFrom());
        }

        @Test
        void testRemoveOperation() {
            JsonPatchOperation op = JsonPatchOperation.remove("/path");

            assertEquals("remove", op.getOp());
            assertEquals("/path", op.getPath());
            assertNull(op.getValue());
            assertNull(op.getFrom());
        }

        @Test
        void testReplaceOperation() {
            JsonPatchOperation op = JsonPatchOperation.replace("/path", "newValue");

            assertEquals("replace", op.getOp());
            assertEquals("/path", op.getPath());
            assertEquals("newValue", op.getValue());
            assertNull(op.getFrom());
        }

        @Test
        void testFullConstructor() {
            JsonPatchOperation op = new JsonPatchOperation("move", "/to", null, "/from");

            assertEquals("move", op.getOp());
            assertEquals("/to", op.getPath());
            assertNull(op.getValue());
            assertEquals("/from", op.getFrom());
        }

        @Test
        void testNullOpThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new JsonPatchOperation(null, "/path", "value", null));
        }

        @Test
        void testNullPathThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new JsonPatchOperation("add", null, "value", null));
        }

        @Test
        void testToString() {
            JsonPatchOperation op = JsonPatchOperation.add("/test", "value");

            String str = op.toString();
            assertTrue(str.contains("add"));
            assertTrue(str.contains("/test"));
            assertTrue(str.contains("value"));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            JsonPatchOperation op = JsonPatchOperation.add("/path", "value");

            String json = objectMapper.writeValueAsString(op);
            assertTrue(json.contains("\"op\":\"add\""));
            assertTrue(json.contains("\"path\":\"/path\""));
            assertTrue(json.contains("\"value\":\"value\""));
        }
    }

    @Nested
    class RawEventTest {

        @Test
        void testCreation() {
            RawEvent event =
                    new RawEvent("thread-1", "run-1", Map.of("custom", "data", "count", 123));

            assertEquals(AguiEventType.RAW, event.getType());
            assertNotNull(event.getRawEvent());
        }

        @Test
        void testWithNullRawEvent() {
            RawEvent event = new RawEvent("thread-1", "run-1", null);

            assertNull(event.getRawEvent());
        }

        @Test
        void testWithComplexRawEvent() {
            Map<String, Object> complexData =
                    Map.of(
                            "error",
                            "Something failed",
                            "code",
                            500,
                            "details",
                            Map.of("reason", "Timeout"));
            RawEvent event = new RawEvent("thread-1", "run-1", complexData);

            assertTrue(event.getRawEvent() instanceof Map);
        }

        @Test
        void testToString() {
            RawEvent event =
                    new RawEvent("thread-1", "run-1", Map.of("error", "Test error message"));

            String str = event.toString();
            assertTrue(str.contains("thread-1"));
            assertTrue(str.contains("rawEvent"));
        }

        @Test
        void testNullThreadIdThrows() {
            assertThrows(NullPointerException.class, () -> new RawEvent(null, "run-1", Map.of()));
        }

        @Test
        void testNullRunIdThrows() {
            assertThrows(
                    NullPointerException.class, () -> new RawEvent("thread-1", null, Map.of()));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            RawEvent event =
                    new RawEvent("thread-1", "run-1", Map.of("key", "value", "number", 42));

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"RAW\""));
            assertTrue(json.contains("\"rawEvent\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof RawEvent);
        }
    }

    @Nested
    class AguiEventTypeTest {

        @Test
        void testAllEventTypesExist() {
            // Verify all expected event types exist
            assertNotNull(AguiEventType.RUN_STARTED);
            assertNotNull(AguiEventType.RUN_FINISHED);
            assertNotNull(AguiEventType.TEXT_MESSAGE_START);
            assertNotNull(AguiEventType.TEXT_MESSAGE_CONTENT);
            assertNotNull(AguiEventType.TEXT_MESSAGE_END);
            assertNotNull(AguiEventType.TOOL_CALL_START);
            assertNotNull(AguiEventType.TOOL_CALL_ARGS);
            assertNotNull(AguiEventType.TOOL_CALL_END);
            assertNotNull(AguiEventType.STATE_SNAPSHOT);
            assertNotNull(AguiEventType.STATE_DELTA);
            assertNotNull(AguiEventType.RAW);
        }

        @Test
        void testEventTypeCount() {
            assertEquals(11, AguiEventType.values().length);
        }

        @Test
        void testValueOf() {
            assertEquals(AguiEventType.RUN_STARTED, AguiEventType.valueOf("RUN_STARTED"));
            assertEquals(AguiEventType.RUN_FINISHED, AguiEventType.valueOf("RUN_FINISHED"));
            assertEquals(
                    AguiEventType.TEXT_MESSAGE_START, AguiEventType.valueOf("TEXT_MESSAGE_START"));
        }
    }
}
