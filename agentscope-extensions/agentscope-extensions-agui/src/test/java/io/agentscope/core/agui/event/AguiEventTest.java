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
package io.agentscope.core.agui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    class RunStartedTest {

        @Test
        void testCreation() {
            AguiEvent.RunStarted event = new AguiEvent.RunStarted("thread-1", "run-1");

            assertEquals(AguiEventType.RUN_STARTED, event.getType());
            assertEquals("thread-1", event.getThreadId());
            assertEquals("run-1", event.getRunId());
        }

        @Test
        void testToString() {
            AguiEvent.RunStarted event = new AguiEvent.RunStarted("thread-1", "run-1");

            String str = event.toString();
            assertTrue(str.contains("thread-1"));
            assertTrue(str.contains("run-1"));
        }

        @Test
        void testNullThreadIdThrows() {
            assertThrows(NullPointerException.class, () -> new AguiEvent.RunStarted(null, "run-1"));
        }

        @Test
        void testNullRunIdThrows() {
            assertThrows(
                    NullPointerException.class, () -> new AguiEvent.RunStarted("thread-1", null));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiEvent.RunStarted event = new AguiEvent.RunStarted("thread-1", "run-1");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"RUN_STARTED\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.RunStarted);
            assertEquals("thread-1", deserialized.getThreadId());
        }
    }

    @Nested
    class RunFinishedTest {

        @Test
        void testCreation() {
            AguiEvent.RunFinished event = new AguiEvent.RunFinished("thread-2", "run-2");

            assertEquals(AguiEventType.RUN_FINISHED, event.getType());
            assertEquals("thread-2", event.getThreadId());
            assertEquals("run-2", event.getRunId());
        }

        @Test
        void testToString() {
            AguiEvent.RunFinished event = new AguiEvent.RunFinished("thread-2", "run-2");

            String str = event.toString();
            assertTrue(str.contains("thread-2"));
            assertTrue(str.contains("run-2"));
        }

        @Test
        void testNullThreadIdThrows() {
            assertThrows(
                    NullPointerException.class, () -> new AguiEvent.RunFinished(null, "run-1"));
        }

        @Test
        void testNullRunIdThrows() {
            assertThrows(
                    NullPointerException.class, () -> new AguiEvent.RunFinished("thread-1", null));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiEvent.RunFinished event = new AguiEvent.RunFinished("thread-2", "run-2");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"RUN_FINISHED\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.RunFinished);
        }
    }

    @Nested
    class TextMessageStartTest {

        @Test
        void testCreation() {
            AguiEvent.TextMessageStart event =
                    new AguiEvent.TextMessageStart("thread-1", "run-1", "msg-1", "assistant");

            assertEquals(AguiEventType.TEXT_MESSAGE_START, event.getType());
            assertEquals("thread-1", event.getThreadId());
            assertEquals("run-1", event.getRunId());
            assertEquals("msg-1", event.messageId());
            assertEquals("assistant", event.role());
        }

        @Test
        void testToString() {
            AguiEvent.TextMessageStart event =
                    new AguiEvent.TextMessageStart("thread-1", "run-1", "msg-1", "user");

            String str = event.toString();
            assertTrue(str.contains("msg-1"));
            assertTrue(str.contains("user"));
        }

        @Test
        void testNullMessageIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.TextMessageStart("thread-1", "run-1", null, "assistant"));
        }

        @Test
        void testNullRoleThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.TextMessageStart("thread-1", "run-1", "msg-1", null));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiEvent.TextMessageStart event =
                    new AguiEvent.TextMessageStart("thread-1", "run-1", "msg-1", "assistant");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"TEXT_MESSAGE_START\""));
            assertTrue(json.contains("\"messageId\":\"msg-1\""));
            assertTrue(json.contains("\"role\":\"assistant\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.TextMessageStart);
            AguiEvent.TextMessageStart cast = (AguiEvent.TextMessageStart) deserialized;
            assertEquals("msg-1", cast.messageId());
            assertEquals("assistant", cast.role());
        }
    }

    @Nested
    class TextMessageContentTest {

        @Test
        void testCreation() {
            AguiEvent.TextMessageContent event =
                    new AguiEvent.TextMessageContent("thread-1", "run-1", "msg-1", "Hello");

            assertEquals(AguiEventType.TEXT_MESSAGE_CONTENT, event.getType());
            assertEquals("msg-1", event.messageId());
            assertEquals("Hello", event.delta());
        }

        @Test
        void testToString() {
            AguiEvent.TextMessageContent event =
                    new AguiEvent.TextMessageContent("thread-1", "run-1", "msg-1", "Test");

            String str = event.toString();
            assertTrue(str.contains("msg-1"));
            assertTrue(str.contains("Test"));
        }

        @Test
        void testNullDeltaThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.TextMessageContent("thread-1", "run-1", "msg-1", null));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiEvent.TextMessageContent event =
                    new AguiEvent.TextMessageContent("thread-1", "run-1", "msg-1", "Hello World");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"TEXT_MESSAGE_CONTENT\""));
            assertTrue(json.contains("\"delta\":\"Hello World\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.TextMessageContent);
            assertEquals("Hello World", ((AguiEvent.TextMessageContent) deserialized).delta());
        }
    }

    @Nested
    class TextMessageEndTest {

        @Test
        void testCreation() {
            AguiEvent.TextMessageEnd event =
                    new AguiEvent.TextMessageEnd("thread-1", "run-1", "msg-1");

            assertEquals(AguiEventType.TEXT_MESSAGE_END, event.getType());
            assertEquals("msg-1", event.messageId());
        }

        @Test
        void testToString() {
            AguiEvent.TextMessageEnd event =
                    new AguiEvent.TextMessageEnd("thread-1", "run-1", "msg-1");

            String str = event.toString();
            assertTrue(str.contains("msg-1"));
        }

        @Test
        void testNullMessageIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.TextMessageEnd("thread-1", "run-1", null));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiEvent.TextMessageEnd event =
                    new AguiEvent.TextMessageEnd("thread-1", "run-1", "msg-1");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"TEXT_MESSAGE_END\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.TextMessageEnd);
        }
    }

    @Nested
    class ToolCallStartTest {

        @Test
        void testCreation() {
            AguiEvent.ToolCallStart event =
                    new AguiEvent.ToolCallStart("thread-1", "run-1", "tc-1", "get_weather");

            assertEquals(AguiEventType.TOOL_CALL_START, event.getType());
            assertEquals("tc-1", event.toolCallId());
            assertEquals("get_weather", event.toolCallName());
        }

        @Test
        void testToString() {
            AguiEvent.ToolCallStart event =
                    new AguiEvent.ToolCallStart("thread-1", "run-1", "tc-1", "calculator");

            String str = event.toString();
            assertTrue(str.contains("tc-1"));
            assertTrue(str.contains("calculator"));
        }

        @Test
        void testNullToolCallIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.ToolCallStart("thread-1", "run-1", null, "tool"));
        }

        @Test
        void testNullToolCallNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.ToolCallStart("thread-1", "run-1", "tc-1", null));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiEvent.ToolCallStart event =
                    new AguiEvent.ToolCallStart("thread-1", "run-1", "tc-1", "get_weather");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"TOOL_CALL_START\""));
            assertTrue(json.contains("\"toolCallId\":\"tc-1\""));
            assertTrue(json.contains("\"toolCallName\":\"get_weather\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.ToolCallStart);
        }
    }

    @Nested
    class ToolCallArgsTest {

        @Test
        void testCreation() {
            AguiEvent.ToolCallArgs event =
                    new AguiEvent.ToolCallArgs(
                            "thread-1", "run-1", "tc-1", "{\"city\":\"Beijing\"}");

            assertEquals(AguiEventType.TOOL_CALL_ARGS, event.getType());
            assertEquals("tc-1", event.toolCallId());
            assertEquals("{\"city\":\"Beijing\"}", event.delta());
        }

        @Test
        void testToString() {
            AguiEvent.ToolCallArgs event =
                    new AguiEvent.ToolCallArgs("thread-1", "run-1", "tc-1", "{\"key\":\"value\"}");

            String str = event.toString();
            assertTrue(str.contains("tc-1"));
            assertTrue(str.contains("value"));
        }

        @Test
        void testNullToolCallIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.ToolCallArgs("thread-1", "run-1", null, "{}"));
        }

        @Test
        void testNullDeltaThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.ToolCallArgs("thread-1", "run-1", "tc-1", null));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiEvent.ToolCallArgs event =
                    new AguiEvent.ToolCallArgs("thread-1", "run-1", "tc-1", "{\"key\":\"value\"}");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"TOOL_CALL_ARGS\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.ToolCallArgs);
        }
    }

    @Nested
    class ToolCallEndTest {

        @Test
        void testCreation() {
            AguiEvent.ToolCallEnd event =
                    new AguiEvent.ToolCallEnd("thread-1", "run-1", "tc-1", "Success");

            assertEquals(AguiEventType.TOOL_CALL_END, event.getType());
            assertEquals("tc-1", event.toolCallId());
            assertEquals("Success", event.result());
        }

        @Test
        void testWithNullResult() {
            AguiEvent.ToolCallEnd event =
                    new AguiEvent.ToolCallEnd("thread-1", "run-1", "tc-1", null);

            assertNull(event.result());
        }

        @Test
        void testToString() {
            AguiEvent.ToolCallEnd event =
                    new AguiEvent.ToolCallEnd("thread-1", "run-1", "tc-1", "Result");

            String str = event.toString();
            assertTrue(str.contains("tc-1"));
            assertTrue(str.contains("Result"));
        }

        @Test
        void testNullToolCallIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.ToolCallEnd("thread-1", "run-1", null, "result"));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiEvent.ToolCallEnd event =
                    new AguiEvent.ToolCallEnd("thread-1", "run-1", "tc-1", "Operation completed");

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"TOOL_CALL_END\""));
            assertTrue(json.contains("\"result\":\"Operation completed\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.ToolCallEnd);
        }
    }

    @Nested
    class StateSnapshotTest {

        @Test
        void testCreation() {
            Map<String, Object> state = Map.of("key1", "value1", "key2", 42);
            AguiEvent.StateSnapshot event = new AguiEvent.StateSnapshot("thread-1", "run-1", state);

            assertEquals(AguiEventType.STATE_SNAPSHOT, event.getType());
            assertEquals("value1", event.snapshot().get("key1"));
            assertEquals(42, event.snapshot().get("key2"));
        }

        @Test
        void testNullSnapshotCreatesEmptyMap() {
            AguiEvent.StateSnapshot event = new AguiEvent.StateSnapshot("thread-1", "run-1", null);

            assertNotNull(event.snapshot());
            assertTrue(event.snapshot().isEmpty());
        }

        @Test
        void testSnapshotIsImmutable() {
            Map<String, Object> state = Map.of("key", "value");
            AguiEvent.StateSnapshot event = new AguiEvent.StateSnapshot("thread-1", "run-1", state);

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> event.snapshot().put("new", "value"));
        }

        @Test
        void testToString() {
            AguiEvent.StateSnapshot event =
                    new AguiEvent.StateSnapshot("thread-1", "run-1", Map.of("key", "value"));

            String str = event.toString();
            assertTrue(str.contains("thread-1"));
            assertTrue(str.contains("snapshot"));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiEvent.StateSnapshot event =
                    new AguiEvent.StateSnapshot(
                            "thread-1", "run-1", Map.of("count", 10, "name", "test"));

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"STATE_SNAPSHOT\""));
            assertTrue(json.contains("\"snapshot\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.StateSnapshot);
        }
    }

    @Nested
    class StateDeltaTest {

        @Test
        void testCreation() {
            List<AguiEvent.JsonPatchOperation> ops =
                    List.of(
                            AguiEvent.JsonPatchOperation.add("/path1", "value1"),
                            AguiEvent.JsonPatchOperation.remove("/path2"));
            AguiEvent.StateDelta event = new AguiEvent.StateDelta("thread-1", "run-1", ops);

            assertEquals(AguiEventType.STATE_DELTA, event.getType());
            assertEquals(2, event.delta().size());
        }

        @Test
        void testNullDeltaCreatesEmptyList() {
            AguiEvent.StateDelta event = new AguiEvent.StateDelta("thread-1", "run-1", null);

            assertNotNull(event.delta());
            assertTrue(event.delta().isEmpty());
        }

        @Test
        void testDeltaIsImmutable() {
            List<AguiEvent.JsonPatchOperation> ops =
                    List.of(AguiEvent.JsonPatchOperation.add("/path", "value"));
            AguiEvent.StateDelta event = new AguiEvent.StateDelta("thread-1", "run-1", ops);

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> event.delta().add(AguiEvent.JsonPatchOperation.remove("/test")));
        }

        @Test
        void testToString() {
            AguiEvent.StateDelta event =
                    new AguiEvent.StateDelta(
                            "thread-1",
                            "run-1",
                            List.of(AguiEvent.JsonPatchOperation.replace("/key", "newValue")));

            String str = event.toString();
            assertTrue(str.contains("delta"));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiEvent.StateDelta event =
                    new AguiEvent.StateDelta(
                            "thread-1",
                            "run-1",
                            List.of(
                                    AguiEvent.JsonPatchOperation.add("/new", "value"),
                                    AguiEvent.JsonPatchOperation.remove("/old")));

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"STATE_DELTA\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.StateDelta);
        }
    }

    @Nested
    class JsonPatchOperationTest {

        @Test
        void testAddOperation() {
            AguiEvent.JsonPatchOperation op = AguiEvent.JsonPatchOperation.add("/path", "value");

            assertEquals("add", op.op());
            assertEquals("/path", op.path());
            assertEquals("value", op.value());
            assertNull(op.from());
        }

        @Test
        void testRemoveOperation() {
            AguiEvent.JsonPatchOperation op = AguiEvent.JsonPatchOperation.remove("/path");

            assertEquals("remove", op.op());
            assertEquals("/path", op.path());
            assertNull(op.value());
            assertNull(op.from());
        }

        @Test
        void testReplaceOperation() {
            AguiEvent.JsonPatchOperation op =
                    AguiEvent.JsonPatchOperation.replace("/path", "newValue");

            assertEquals("replace", op.op());
            assertEquals("/path", op.path());
            assertEquals("newValue", op.value());
            assertNull(op.from());
        }

        @Test
        void testFullConstructor() {
            AguiEvent.JsonPatchOperation op =
                    new AguiEvent.JsonPatchOperation("move", "/to", null, "/from");

            assertEquals("move", op.op());
            assertEquals("/to", op.path());
            assertNull(op.value());
            assertEquals("/from", op.from());
        }

        @Test
        void testNullOpThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.JsonPatchOperation(null, "/path", "value", null));
        }

        @Test
        void testNullPathThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.JsonPatchOperation("add", null, "value", null));
        }

        @Test
        void testToString() {
            AguiEvent.JsonPatchOperation op = AguiEvent.JsonPatchOperation.add("/test", "value");

            String str = op.toString();
            assertTrue(str.contains("add"));
            assertTrue(str.contains("/test"));
            assertTrue(str.contains("value"));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiEvent.JsonPatchOperation op = AguiEvent.JsonPatchOperation.add("/path", "value");

            String json = objectMapper.writeValueAsString(op);
            assertTrue(json.contains("\"op\":\"add\""));
            assertTrue(json.contains("\"path\":\"/path\""));
            assertTrue(json.contains("\"value\":\"value\""));
        }
    }

    @Nested
    class RawTest {

        @Test
        void testCreation() {
            AguiEvent.Raw event =
                    new AguiEvent.Raw("thread-1", "run-1", Map.of("custom", "data", "count", 123));

            assertEquals(AguiEventType.RAW, event.getType());
            assertNotNull(event.rawEvent());
        }

        @Test
        void testWithNullRawEvent() {
            AguiEvent.Raw event = new AguiEvent.Raw("thread-1", "run-1", null);

            assertNull(event.rawEvent());
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
            AguiEvent.Raw event = new AguiEvent.Raw("thread-1", "run-1", complexData);

            assertTrue(event.rawEvent() instanceof Map);
        }

        @Test
        void testToString() {
            AguiEvent.Raw event =
                    new AguiEvent.Raw("thread-1", "run-1", Map.of("error", "Test error message"));

            String str = event.toString();
            assertTrue(str.contains("thread-1"));
            assertTrue(str.contains("rawEvent"));
        }

        @Test
        void testNullThreadIdThrows() {
            assertThrows(
                    NullPointerException.class, () -> new AguiEvent.Raw(null, "run-1", Map.of()));
        }

        @Test
        void testNullRunIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.Raw("thread-1", null, Map.of()));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiEvent.Raw event =
                    new AguiEvent.Raw("thread-1", "run-1", Map.of("key", "value", "number", 42));

            String json = objectMapper.writeValueAsString(event);
            assertTrue(json.contains("\"type\":\"RAW\""));
            assertTrue(json.contains("\"rawEvent\""));

            AguiEvent deserialized = objectMapper.readValue(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.Raw);
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
