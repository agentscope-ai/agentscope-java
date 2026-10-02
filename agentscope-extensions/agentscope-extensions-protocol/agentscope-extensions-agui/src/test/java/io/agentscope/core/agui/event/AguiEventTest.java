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
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for all AG-UI event types.
 */
class AguiEventTest {

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

            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"RUN_STARTED\"");

            AguiEvent deserialized = JsonUtils.getJsonCodec().fromJson(json, AguiEvent.class);
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

            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"RUN_FINISHED\"");

            AguiEvent deserialized = JsonUtils.getJsonCodec().fromJson(json, AguiEvent.class);
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

            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"TEXT_MESSAGE_START\"");
            assertTrue(json.contains("\"messageId\":\"msg-1\""));
            assertTrue(json.contains("\"role\":\"assistant\""));

            AguiEvent deserialized = JsonUtils.getJsonCodec().fromJson(json, AguiEvent.class);
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

            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"TEXT_MESSAGE_CONTENT\"");
            assertTrue(json.contains("\"delta\":\"Hello World\""));

            AguiEvent deserialized = JsonUtils.getJsonCodec().fromJson(json, AguiEvent.class);
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

            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"TEXT_MESSAGE_END\"");

            AguiEvent deserialized = JsonUtils.getJsonCodec().fromJson(json, AguiEvent.class);
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

            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"TOOL_CALL_START\"");
            assertTrue(json.contains("\"toolCallId\":\"tc-1\""));
            assertTrue(json.contains("\"toolCallName\":\"get_weather\""));

            AguiEvent deserialized = JsonUtils.getJsonCodec().fromJson(json, AguiEvent.class);
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

            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"TOOL_CALL_ARGS\"");

            AguiEvent deserialized = JsonUtils.getJsonCodec().fromJson(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.ToolCallArgs);
        }
    }

    @Nested
    class ToolCallEndTest {

        @Test
        void testCreation() {
            AguiEvent.ToolCallEnd event = new AguiEvent.ToolCallEnd("thread-1", "run-1", "tc-1");

            assertEquals(AguiEventType.TOOL_CALL_END, event.getType());
            assertEquals("tc-1", event.toolCallId());
        }

        @Test
        void testToString() {
            AguiEvent.ToolCallEnd event = new AguiEvent.ToolCallEnd("thread-1", "run-1", "tc-1");

            String str = event.toString();
            assertTrue(str.contains("tc-1"));
        }

        @Test
        void testNullToolCallIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.ToolCallEnd("thread-1", "run-1", null));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiEvent.ToolCallEnd event = new AguiEvent.ToolCallEnd("thread-1", "run-1", "tc-1");

            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"TOOL_CALL_END\"");

            AguiEvent deserialized = JsonUtils.getJsonCodec().fromJson(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.ToolCallEnd);
        }
    }

    @Nested
    class ToolCallResultTest {

        @Test
        void testCreation() {
            AguiEvent.ToolCallResult event =
                    new AguiEvent.ToolCallResult(
                            "thread-1", "run-1", "tc-1", "Success", "tool", "msg-1");

            assertEquals(AguiEventType.TOOL_CALL_RESULT, event.getType());
            assertEquals("tc-1", event.toolCallId());
            assertEquals("Success", event.content());
        }

        @Test
        void testWithNullContent() {
            AguiEvent.ToolCallResult event =
                    new AguiEvent.ToolCallResult(
                            "thread-1", "run-1", "tc-1", null, "tool", "msg-1");

            assertNull(event.content());
        }

        @Test
        void testToString() {
            AguiEvent.ToolCallResult event =
                    new AguiEvent.ToolCallResult(
                            "thread-1", "run-1", "tc-1", "Result", "tool", "msg-1");

            String str = event.toString();
            assertTrue(str.contains("tc-1"));
            assertTrue(str.contains("Result"));
        }

        @Test
        void testNullToolCallIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            new AguiEvent.ToolCallResult(
                                    "thread-1", "run-1", null, "result", "tool", "msg-1"));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiEvent.ToolCallResult event =
                    new AguiEvent.ToolCallResult(
                            "thread-1", "run-1", "tc-1", "Operation completed", "tool", "msg-1");

            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"TOOL_CALL_RESULT\"");
            assertTrue(json.contains("\"content\":\"Operation completed\""));

            AguiEvent deserialized = JsonUtils.getJsonCodec().fromJson(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.ToolCallResult);
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

            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"STATE_SNAPSHOT\"");
            assertTrue(json.contains("\"snapshot\""));

            AguiEvent deserialized = JsonUtils.getJsonCodec().fromJson(json, AguiEvent.class);
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

            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"STATE_DELTA\"");

            AguiEvent deserialized = JsonUtils.getJsonCodec().fromJson(json, AguiEvent.class);
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

            String json = JsonUtils.getJsonCodec().toJson(op);
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

            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"RAW\"");
            assertTrue(json.contains("\"rawEvent\""));

            AguiEvent deserialized = JsonUtils.getJsonCodec().fromJson(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.Raw);
        }
    }

    @Nested
    class CustomTest {

        @Test
        void testCreation() {
            AguiEvent.Custom event =
                    new AguiEvent.Custom(
                            "thread-1", "run-1", "custom-event", Map.of("key", "value"));

            assertEquals(AguiEventType.CUSTOM, event.getType());
            assertEquals("thread-1", event.getThreadId());
            assertEquals("run-1", event.getRunId());
            assertEquals("custom-event", event.name());
            assertNotNull(event.value());
        }

        @Test
        void testWithNullValue() {
            AguiEvent.Custom event =
                    new AguiEvent.Custom("thread-1", "run-1", "custom-event", null);

            assertNull(event.value());
        }

        @Test
        void testWithComplexValue() {
            Map<String, Object> complexData =
                    Map.of(
                            "error",
                            "Something failed",
                            "code",
                            500,
                            "details",
                            Map.of("reason", "Timeout"));
            AguiEvent.Custom event =
                    new AguiEvent.Custom("thread-1", "run-1", "error-event", complexData);

            assertTrue(event.value() instanceof Map);
        }

        @Test
        void testToString() {
            AguiEvent.Custom event =
                    new AguiEvent.Custom("thread-1", "run-1", "test-event", Map.of("key", "value"));

            String str = event.toString();
            assertTrue(str.contains("thread-1"));
            assertTrue(str.contains("name"));
        }

        @Test
        void testNullThreadIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.Custom(null, "run-1", "test-event", Map.of()));
        }

        @Test
        void testNullRunIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.Custom("thread-1", null, "test-event", Map.of()));
        }

        @Test
        void testNullNameThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.Custom("thread-1", "run-1", null, Map.of()));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiEvent.Custom event =
                    new AguiEvent.Custom(
                            "thread-1",
                            "run-1",
                            "test-event",
                            Map.of("key", "value", "number", 42));

            String json = JsonUtils.getJsonCodec().toJson(event);
            assertTrue(json.contains("\"type\":\"CUSTOM\""));
            assertTrue(json.contains("\"name\":\"test-event\""));
            assertTrue(json.contains("\"value\""));

            AguiEvent deserialized = JsonUtils.getJsonCodec().fromJson(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.Custom);
            AguiEvent.Custom customEvent = (AguiEvent.Custom) deserialized;
            assertEquals("test-event", customEvent.name());
        }
    }

    @Nested
    class RunErrorTest {

        @Test
        void testCreation() {
            AguiEvent.RunError event =
                    new AguiEvent.RunError("thread-1", "run-1", "Something broke", "ERR_001");

            assertEquals(AguiEventType.RUN_ERROR, event.getType());
            assertEquals("thread-1", event.getThreadId());
            assertEquals("run-1", event.getRunId());
            assertEquals("Something broke", event.message());
            assertEquals("ERR_001", event.code());
        }

        @Test
        void testNullCode() {
            AguiEvent.RunError event = new AguiEvent.RunError("thread-1", "run-1", "error", null);
            assertNull(event.code());
        }

        @Test
        void testNullMessageThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.RunError("thread-1", "run-1", null, null));
        }

        @Test
        void testJsonSerialization() throws Exception {
            AguiEvent.RunError event = new AguiEvent.RunError("t1", "r1", "fail", null);
            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"RUN_ERROR\"");
            assertTrue(json.contains("\"message\":\"fail\""));

            AguiEvent deserialized = JsonUtils.getJsonCodec().fromJson(json, AguiEvent.class);
            assertTrue(deserialized instanceof AguiEvent.RunError);
        }
    }

    @Nested
    class StepStartedTest {

        @Test
        void testCreation() {
            AguiEvent.StepStarted event = new AguiEvent.StepStarted("thread-1", "run-1", "step1");
            assertEquals(AguiEventType.STEP_STARTED, event.getType());
            assertEquals("step1", event.stepName());
        }

        @Test
        void testNullStepNameThrows() {
            assertThrows(
                    NullPointerException.class, () -> new AguiEvent.StepStarted("t", "r", null));
        }

        @Test
        void testJsonSerialization() throws Exception {
            AguiEvent.StepStarted event = new AguiEvent.StepStarted("t1", "r1", "analyze");
            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"STEP_STARTED\"");
            assertTrue(json.contains("\"stepName\":\"analyze\""));
        }
    }

    @Nested
    class StepFinishedTest {

        @Test
        void testCreation() {
            AguiEvent.StepFinished event = new AguiEvent.StepFinished("thread-1", "run-1", "step1");
            assertEquals(AguiEventType.STEP_FINISHED, event.getType());
            assertEquals("step1", event.stepName());
        }

        @Test
        void testNullStepNameThrows() {
            assertThrows(
                    NullPointerException.class, () -> new AguiEvent.StepFinished("t", "r", null));
        }
    }

    @Nested
    class TextMessageChunkTest {

        @Test
        void testCreation() {
            AguiEvent.TextMessageChunk event =
                    new AguiEvent.TextMessageChunk("t1", "r1", "msg-1", "assistant", "Hello", null);
            assertEquals(AguiEventType.TEXT_MESSAGE_CHUNK, event.getType());
            assertEquals("msg-1", event.messageId());
            assertEquals("assistant", event.role());
            assertEquals("Hello", event.delta());
            assertNull(event.name());
        }

        @Test
        void testOptionalFieldsAreNullable() {
            AguiEvent.TextMessageChunk event =
                    new AguiEvent.TextMessageChunk("t1", "r1", null, null, null, null);
            assertNull(event.messageId());
            assertNull(event.role());
            assertNull(event.delta());
        }

        @Test
        void testJsonSerialization() throws Exception {
            AguiEvent.TextMessageChunk event =
                    new AguiEvent.TextMessageChunk("t1", "r1", "msg-1", "user", "chunk", "name");
            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"TEXT_MESSAGE_CHUNK\"");
        }
    }

    @Nested
    class ToolCallChunkTest {

        @Test
        void testCreation() {
            AguiEvent.ToolCallChunk event =
                    new AguiEvent.ToolCallChunk(
                            "t1", "r1", "tc-1", "tool", "parent-1", "{\"key\":\"val\"}");
            assertEquals(AguiEventType.TOOL_CALL_CHUNK, event.getType());
            assertEquals("tc-1", event.toolCallId());
            assertEquals("tool", event.toolCallName());
            assertEquals("parent-1", event.parentMessageId());
            assertEquals("{\"key\":\"val\"}", event.delta());
        }

        @Test
        void testOptionalFieldsAreNullable() {
            AguiEvent.ToolCallChunk event =
                    new AguiEvent.ToolCallChunk("t1", "r1", null, null, null, null);
            assertNull(event.toolCallId());
            assertNull(event.toolCallName());
        }

        @Test
        void testJsonSerialization() throws Exception {
            AguiEvent.ToolCallChunk event =
                    new AguiEvent.ToolCallChunk("t1", "r1", "tc-1", "tool", null, "data");
            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"TOOL_CALL_CHUNK\"");
        }
    }

    @Nested
    class MessagesSnapshotTest {

        @Test
        void testCreation() {
            List<AguiMessage> msgs = List.of(AguiMessage.userMessage("m1", "hello"));
            AguiEvent.MessagesSnapshot event = new AguiEvent.MessagesSnapshot("t1", "r1", msgs);
            assertEquals(AguiEventType.MESSAGES_SNAPSHOT, event.getType());
            assertEquals(1, event.messages().size());
            assertEquals("hello", event.messages().get(0).getContent());
        }

        @Test
        void testNullMessagesCreatesEmptyList() {
            AguiEvent.MessagesSnapshot event = new AguiEvent.MessagesSnapshot("t1", "r1", null);
            assertTrue(event.messages().isEmpty());
        }

        @Test
        void testMessagesIsImmutable() {
            AguiEvent.MessagesSnapshot event =
                    new AguiEvent.MessagesSnapshot("t1", "r1", List.of());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> event.messages().add(AguiMessage.userMessage("x", "y")));
        }

        @Test
        void testJsonSerialization() throws Exception {
            List<AguiMessage> msgs = List.of(AguiMessage.userMessage("m1", "hello"));
            AguiEvent.MessagesSnapshot event = new AguiEvent.MessagesSnapshot("t1", "r1", msgs);
            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"MESSAGES_SNAPSHOT\"");
            assertTrue(json.contains("\"role\":\"user\""));
        }
    }

    @Nested
    class ActivitySnapshotTest {

        @Test
        void testCreation() {
            Map<String, Object> content = Map.of("status", "running");
            AguiEvent.ActivitySnapshot event =
                    new AguiEvent.ActivitySnapshot("t1", "r1", "msg-1", "progress", content);
            assertEquals(AguiEventType.ACTIVITY_SNAPSHOT, event.getType());
            assertEquals("msg-1", event.messageId());
            assertEquals("progress", event.activityType());
            assertTrue(event.replace());
        }

        @Test
        void testReplaceDefaultTrue() {
            AguiEvent.ActivitySnapshot event =
                    new AguiEvent.ActivitySnapshot("t1", "r1", "msg-1", "progress", null);
            assertTrue(event.replace());
        }

        @Test
        void testNullContentCreatesEmptyMap() {
            AguiEvent.ActivitySnapshot event =
                    new AguiEvent.ActivitySnapshot("t1", "r1", "msg-1", "test", null);
            assertTrue(event.content().isEmpty());
        }

        @Test
        void testJsonSerialization() throws Exception {
            AguiEvent.ActivitySnapshot event =
                    new AguiEvent.ActivitySnapshot(
                            "t1", "r1", "msg-1", "progress", Map.of("pct", 50), false);
            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"ACTIVITY_SNAPSHOT\"");
            assertTrue(json.contains("\"replace\":false"));
        }
    }

    @Nested
    class ActivityDeltaTest {

        @Test
        void testCreation() {
            List<AguiEvent.JsonPatchOperation> patch =
                    List.of(AguiEvent.JsonPatchOperation.add("/path", "value"));
            AguiEvent.ActivityDelta event =
                    new AguiEvent.ActivityDelta("t1", "r1", "msg-1", "progress", patch);
            assertEquals(AguiEventType.ACTIVITY_DELTA, event.getType());
            assertEquals("msg-1", event.messageId());
            assertEquals(1, event.patch().size());
        }

        @Test
        void testNullPatchCreatesEmptyList() {
            AguiEvent.ActivityDelta event =
                    new AguiEvent.ActivityDelta("t1", "r1", "msg-1", "test", null);
            assertTrue(event.patch().isEmpty());
        }

        @Test
        void testJsonSerialization() throws Exception {
            AguiEvent.ActivityDelta event =
                    new AguiEvent.ActivityDelta(
                            "t1",
                            "r1",
                            "msg-1",
                            "typing",
                            List.of(AguiEvent.JsonPatchOperation.add("/status", "active")));
            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"ACTIVITY_DELTA\"");
        }
    }

    @Nested
    class ReasoningEncryptedValueTest {

        @Test
        void testCreation() {
            AguiEvent.ReasoningEncryptedValue event =
                    new AguiEvent.ReasoningEncryptedValue(
                            "t1", "r1", "tool-call", "tc-1", "encrypted_data");
            assertEquals(AguiEventType.REASONING_ENCRYPTED_VALUE, event.getType());
            assertEquals("tool-call", event.subtype());
            assertEquals("tc-1", event.entityId());
            assertEquals("encrypted_data", event.encryptedValue());
        }

        @Test
        void testRejectsNullSubtype() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiEvent.ReasoningEncryptedValue("t1", "r1", null, "e", "v"));
        }

        @Test
        void testJsonSerialization() throws Exception {
            AguiEvent.ReasoningEncryptedValue event =
                    new AguiEvent.ReasoningEncryptedValue("t1", "r1", "message", "msg-1", "enc");
            String json = JsonUtils.getJsonCodec().toJson(event);
            checkExistAndDuplicate(json, "\"type\":\"REASONING_ENCRYPTED_VALUE\"");
            assertTrue(json.contains("\"subtype\":\"message\""));
            assertTrue(json.contains("\"encryptedValue\":\"enc\""));
        }
    }

    @Nested
    class RunFinishedOutcomeTest {

        @Test
        void testSuccessOutcome() {
            AguiEvent.RunFinishedSuccessOutcome outcome = new AguiEvent.RunFinishedSuccessOutcome();
            assertNotNull(outcome);
        }

        @Test
        void testInterruptOutcome() {
            AguiEvent.Interrupt interrupt =
                    new AguiEvent.Interrupt(
                            "int-1", "user_confirmation", "Please confirm", null, null, null, null);
            AguiEvent.RunFinishedInterruptOutcome outcome =
                    new AguiEvent.RunFinishedInterruptOutcome(List.of(interrupt));
            assertEquals(1, outcome.interrupts().size());
            assertEquals("int-1", outcome.interrupts().get(0).id());
        }

        @Test
        void testRunFinishedWithSuccessOutcome() {
            AguiEvent.RunFinished event =
                    new AguiEvent.RunFinished(
                            "t1", "r1", "result_data", new AguiEvent.RunFinishedSuccessOutcome());
            assertEquals("result_data", event.result());
            assertTrue(event.outcome() instanceof AguiEvent.RunFinishedSuccessOutcome);
        }

        @Test
        void testRunFinishedWithInterruptOutcome() {
            AguiEvent.Interrupt interrupt =
                    new AguiEvent.Interrupt("int-1", "approval", null, "tc-1", null, null, null);
            AguiEvent.RunFinished event =
                    new AguiEvent.RunFinished(
                            "t1",
                            "r1",
                            null,
                            new AguiEvent.RunFinishedInterruptOutcome(List.of(interrupt)));
            assertTrue(event.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome);
        }

        @Test
        void testJsonSerialization() throws Exception {
            AguiEvent.Interrupt interrupt =
                    new AguiEvent.Interrupt(
                            "int-1",
                            "approval_required",
                            "Please approve",
                            "tc-1",
                            Map.of("type", "object"),
                            null,
                            null);
            AguiEvent.RunFinished event =
                    new AguiEvent.RunFinished(
                            "t1",
                            "r1",
                            null,
                            new AguiEvent.RunFinishedInterruptOutcome(List.of(interrupt)));
            String json = JsonUtils.getJsonCodec().toJson(event);
            assertTrue(json.contains("\"type\":\"RUN_FINISHED\""));
            assertTrue(json.contains("\"outcome\""));
            assertTrue(json.contains("\"interrupts\""));
        }
    }

    @Nested
    class RunStartedMissingFieldsTest {

        @Test
        void testConvenienceConstructor() {
            // Verify the 2-arg convenience constructor still works
            AguiEvent.RunStarted event = new AguiEvent.RunStarted("thread-1", "run-1");
            assertNull(event.parentRunId());
            assertNull(event.input());
        }

        @Test
        void testWithParentRunId() {
            AguiEvent.RunStarted event = new AguiEvent.RunStarted("t1", "r1", "parent-1", null);
            assertEquals("parent-1", event.parentRunId());
        }
    }

    @Nested
    class AguiEventTypeTest {

        @Test
        void testEventTypeCount() {
            assertEquals(28, AguiEventType.values().length);
        }

        @Test
        void testAllEventTypesExist() {
            // Verify all expected event types exist
            assertNotNull(AguiEventType.RUN_STARTED);
            assertNotNull(AguiEventType.RUN_FINISHED);
            assertNotNull(AguiEventType.RUN_ERROR);
            assertNotNull(AguiEventType.STEP_STARTED);
            assertNotNull(AguiEventType.STEP_FINISHED);
            assertNotNull(AguiEventType.TEXT_MESSAGE_START);
            assertNotNull(AguiEventType.TEXT_MESSAGE_CONTENT);
            assertNotNull(AguiEventType.TEXT_MESSAGE_END);
            assertNotNull(AguiEventType.TEXT_MESSAGE_CHUNK);
            assertNotNull(AguiEventType.TOOL_CALL_START);
            assertNotNull(AguiEventType.TOOL_CALL_ARGS);
            assertNotNull(AguiEventType.TOOL_CALL_END);
            assertNotNull(AguiEventType.TOOL_CALL_CHUNK);
            assertNotNull(AguiEventType.TOOL_CALL_RESULT);
            assertNotNull(AguiEventType.STATE_SNAPSHOT);
            assertNotNull(AguiEventType.STATE_DELTA);
            assertNotNull(AguiEventType.MESSAGES_SNAPSHOT);
            assertNotNull(AguiEventType.ACTIVITY_SNAPSHOT);
            assertNotNull(AguiEventType.ACTIVITY_DELTA);
            assertNotNull(AguiEventType.RAW);
            assertNotNull(AguiEventType.CUSTOM);
            assertNotNull(AguiEventType.REASONING_START);
            assertNotNull(AguiEventType.REASONING_MESSAGE_START);
            assertNotNull(AguiEventType.REASONING_MESSAGE_CONTENT);
            assertNotNull(AguiEventType.REASONING_MESSAGE_END);
            assertNotNull(AguiEventType.REASONING_MESSAGE_CHUNK);
            assertNotNull(AguiEventType.REASONING_END);
            assertNotNull(AguiEventType.REASONING_ENCRYPTED_VALUE);
        }

        @Test
        void testValueOf() {
            assertEquals(AguiEventType.RUN_STARTED, AguiEventType.valueOf("RUN_STARTED"));
            assertEquals(AguiEventType.RUN_FINISHED, AguiEventType.valueOf("RUN_FINISHED"));
            assertEquals(
                    AguiEventType.TEXT_MESSAGE_START, AguiEventType.valueOf("TEXT_MESSAGE_START"));
        }
    }

    static void checkExistAndDuplicate(String text, String checkText) {
        int index = text.indexOf(checkText);
        assertTrue(index >= 0);

        int duplicateIndex = text.indexOf(checkText, index + 1);
        assertTrue(duplicateIndex < 0);
    }
}
