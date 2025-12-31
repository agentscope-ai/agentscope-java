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
package io.agentscope.core.agui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * Unit tests for all AG-UI model classes.
 */
class AguiModelTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Nested
    class AguiMessageTest {

        @Test
        void testUserMessageFactory() {
            AguiMessage msg = AguiMessage.userMessage("msg-1", "Hello world");

            assertEquals("msg-1", msg.getId());
            assertEquals("user", msg.getRole());
            assertEquals("Hello world", msg.getContent());
            assertTrue(msg.isUserMessage());
            assertFalse(msg.isAssistantMessage());
        }

        @Test
        void testAssistantMessageFactory() {
            AguiMessage msg = AguiMessage.assistantMessage("msg-2", "Hi there!");

            assertEquals("msg-2", msg.getId());
            assertEquals("assistant", msg.getRole());
            assertTrue(msg.isAssistantMessage());
            assertFalse(msg.isUserMessage());
        }

        @Test
        void testSystemMessageFactory() {
            AguiMessage msg = AguiMessage.systemMessage("msg-3", "You are helpful");

            assertEquals("msg-3", msg.getId());
            assertEquals("system", msg.getRole());
            assertTrue(msg.isSystemMessage());
        }

        @Test
        void testToolMessageFactory() {
            AguiMessage msg = AguiMessage.toolMessage("msg-4", "tc-1", "Result: 42");

            assertEquals("msg-4", msg.getId());
            assertEquals("tool", msg.getRole());
            assertEquals("tc-1", msg.getToolCallId());
            assertTrue(msg.isToolMessage());
        }

        @Test
        void testMessageWithToolCalls() {
            AguiFunctionCall function = new AguiFunctionCall("get_weather", "{\"city\":\"NYC\"}");
            AguiToolCall toolCall = new AguiToolCall("tc-1", function);
            AguiMessage msg =
                    new AguiMessage("msg-5", "assistant", "Let me check", List.of(toolCall), null);

            assertTrue(msg.hasToolCalls());
            assertEquals(1, msg.getToolCalls().size());
            assertEquals("tc-1", msg.getToolCalls().get(0).getId());
        }

        @Test
        void testMessageWithoutToolCalls() {
            AguiMessage msg = AguiMessage.userMessage("msg-6", "Just text");

            assertFalse(msg.hasToolCalls());
            assertTrue(msg.getToolCalls().isEmpty());
        }

        @Test
        void testToolCallsAreImmutable() {
            AguiFunctionCall function = new AguiFunctionCall("test", "{}");
            AguiToolCall toolCall = new AguiToolCall("tc-1", function);
            AguiMessage msg = new AguiMessage("msg-7", "assistant", null, List.of(toolCall), null);

            assertThrows(
                    UnsupportedOperationException.class,
                    () ->
                            msg.getToolCalls()
                                    .add(
                                            new AguiToolCall(
                                                    "tc-2", new AguiFunctionCall("t", "{}"))));
        }

        @Test
        void testNullIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiMessage(null, "user", "content", null, null));
        }

        @Test
        void testNullRoleThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AguiMessage("id", null, "content", null, null));
        }

        @Test
        void testEquals() {
            AguiMessage msg1 = AguiMessage.userMessage("msg-1", "Hello");
            AguiMessage msg2 = AguiMessage.userMessage("msg-1", "Hello");
            AguiMessage msg3 = AguiMessage.userMessage("msg-2", "Hello");

            assertEquals(msg1, msg2);
            assertNotEquals(msg1, msg3);
            assertNotEquals(msg1, null);
            assertNotEquals(msg1, "string");
        }

        @Test
        void testHashCode() {
            AguiMessage msg1 = AguiMessage.userMessage("msg-1", "Hello");
            AguiMessage msg2 = AguiMessage.userMessage("msg-1", "Hello");

            assertEquals(msg1.hashCode(), msg2.hashCode());
        }

        @Test
        void testToString() {
            AguiMessage msg = AguiMessage.userMessage("msg-1", "Test content");

            String str = msg.toString();
            assertTrue(str.contains("msg-1"));
            assertTrue(str.contains("user"));
            assertTrue(str.contains("Test content"));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiMessage msg = AguiMessage.assistantMessage("msg-1", "Hello");

            String json = objectMapper.writeValueAsString(msg);
            assertTrue(json.contains("\"id\":\"msg-1\""));
            assertTrue(json.contains("\"role\":\"assistant\""));

            AguiMessage deserialized = objectMapper.readValue(json, AguiMessage.class);
            assertEquals(msg.getId(), deserialized.getId());
            assertEquals(msg.getRole(), deserialized.getRole());
        }

        @Test
        void testNullContent() {
            AguiMessage msg = new AguiMessage("msg-1", "user", null, null, null);

            assertNull(msg.getContent());
        }
    }

    @Nested
    class AguiToolCallTest {

        @Test
        void testCreation() {
            AguiFunctionCall function = new AguiFunctionCall("get_time", "{}");
            AguiToolCall toolCall = new AguiToolCall("tc-1", function);

            assertEquals("tc-1", toolCall.getId());
            assertEquals("function", toolCall.getType());
            assertEquals("get_time", toolCall.getFunction().getName());
        }

        @Test
        void testCreationWithExplicitType() {
            AguiFunctionCall function = new AguiFunctionCall("test", "{}");
            AguiToolCall toolCall = new AguiToolCall("tc-1", "custom_type", function);

            assertEquals("custom_type", toolCall.getType());
        }

        @Test
        void testNullTypeDefaultsToFunction() {
            AguiFunctionCall function = new AguiFunctionCall("test", "{}");
            AguiToolCall toolCall = new AguiToolCall("tc-1", null, function);

            assertEquals("function", toolCall.getType());
        }

        @Test
        void testNullIdThrows() {
            AguiFunctionCall function = new AguiFunctionCall("test", "{}");
            assertThrows(NullPointerException.class, () -> new AguiToolCall(null, function));
        }

        @Test
        void testNullFunctionThrows() {
            assertThrows(NullPointerException.class, () -> new AguiToolCall("tc-1", null));
        }

        @Test
        void testEquals() {
            AguiFunctionCall function = new AguiFunctionCall("test", "{}");
            AguiToolCall tc1 = new AguiToolCall("tc-1", function);
            AguiToolCall tc2 = new AguiToolCall("tc-1", function);
            AguiToolCall tc3 = new AguiToolCall("tc-2", function);

            assertEquals(tc1, tc2);
            assertNotEquals(tc1, tc3);
        }

        @Test
        void testHashCode() {
            AguiFunctionCall function = new AguiFunctionCall("test", "{}");
            AguiToolCall tc1 = new AguiToolCall("tc-1", function);
            AguiToolCall tc2 = new AguiToolCall("tc-1", function);

            assertEquals(tc1.hashCode(), tc2.hashCode());
        }

        @Test
        void testToString() {
            AguiFunctionCall function = new AguiFunctionCall("get_weather", "{\"city\":\"NYC\"}");
            AguiToolCall toolCall = new AguiToolCall("tc-123", function);

            String str = toolCall.toString();
            assertTrue(str.contains("tc-123"));
            assertTrue(str.contains("function"));
            assertTrue(str.contains("get_weather"));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiFunctionCall function = new AguiFunctionCall("test", "{\"key\":\"value\"}");
            AguiToolCall toolCall = new AguiToolCall("tc-1", function);

            String json = objectMapper.writeValueAsString(toolCall);
            assertTrue(json.contains("\"id\":\"tc-1\""));
            assertTrue(json.contains("\"type\":\"function\""));

            AguiToolCall deserialized = objectMapper.readValue(json, AguiToolCall.class);
            assertEquals(toolCall.getId(), deserialized.getId());
        }
    }

    @Nested
    class AguiFunctionCallTest {

        @Test
        void testCreation() {
            AguiFunctionCall function = new AguiFunctionCall("calculate", "{\"expr\":\"2+2\"}");

            assertEquals("calculate", function.getName());
            assertEquals("{\"expr\":\"2+2\"}", function.getArguments());
        }

        @Test
        void testNullArgumentsDefaultsToEmptyObject() {
            AguiFunctionCall function = new AguiFunctionCall("test", null);

            assertEquals("{}", function.getArguments());
        }

        @Test
        void testNullNameThrows() {
            assertThrows(NullPointerException.class, () -> new AguiFunctionCall(null, "{}"));
        }

        @Test
        void testEquals() {
            AguiFunctionCall f1 = new AguiFunctionCall("test", "{\"a\":1}");
            AguiFunctionCall f2 = new AguiFunctionCall("test", "{\"a\":1}");
            AguiFunctionCall f3 = new AguiFunctionCall("other", "{\"a\":1}");

            assertEquals(f1, f2);
            assertNotEquals(f1, f3);
        }

        @Test
        void testHashCode() {
            AguiFunctionCall f1 = new AguiFunctionCall("test", "{\"a\":1}");
            AguiFunctionCall f2 = new AguiFunctionCall("test", "{\"a\":1}");

            assertEquals(f1.hashCode(), f2.hashCode());
        }

        @Test
        void testToString() {
            AguiFunctionCall function = new AguiFunctionCall("get_data", "{\"id\":123}");

            String str = function.toString();
            assertTrue(str.contains("get_data"));
            assertTrue(str.contains("id"));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiFunctionCall function = new AguiFunctionCall("process", "{\"input\":\"data\"}");

            String json = objectMapper.writeValueAsString(function);
            assertTrue(json.contains("\"name\":\"process\""));

            AguiFunctionCall deserialized = objectMapper.readValue(json, AguiFunctionCall.class);
            assertEquals(function.getName(), deserialized.getName());
        }
    }

    @Nested
    class AguiToolTest {

        @Test
        void testCreation() {
            Map<String, Object> params = Map.of("type", "object", "properties", Map.of());
            AguiTool tool = new AguiTool("get_weather", "Get weather info", params);

            assertEquals("get_weather", tool.getName());
            assertEquals("Get weather info", tool.getDescription());
            assertEquals("object", tool.getParameters().get("type"));
        }

        @Test
        void testNullParametersCreatesEmptyMap() {
            AguiTool tool = new AguiTool("test", "description", null);

            assertNotNull(tool.getParameters());
            assertTrue(tool.getParameters().isEmpty());
        }

        @Test
        void testParametersAreImmutable() {
            AguiTool tool = new AguiTool("test", "description", Map.of("key", "value"));

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> tool.getParameters().put("new", "value"));
        }

        @Test
        void testNullNameThrows() {
            assertThrows(
                    NullPointerException.class, () -> new AguiTool(null, "description", Map.of()));
        }

        @Test
        void testNullDescriptionThrows() {
            assertThrows(NullPointerException.class, () -> new AguiTool("name", null, Map.of()));
        }

        @Test
        void testEquals() {
            Map<String, Object> params = Map.of("type", "object");
            AguiTool t1 = new AguiTool("tool1", "desc1", params);
            AguiTool t2 = new AguiTool("tool1", "desc1", params);
            AguiTool t3 = new AguiTool("tool2", "desc1", params);

            assertEquals(t1, t2);
            assertNotEquals(t1, t3);
        }

        @Test
        void testHashCode() {
            Map<String, Object> params = Map.of("type", "object");
            AguiTool t1 = new AguiTool("tool1", "desc1", params);
            AguiTool t2 = new AguiTool("tool1", "desc1", params);

            assertEquals(t1.hashCode(), t2.hashCode());
        }

        @Test
        void testToString() {
            AguiTool tool = new AguiTool("calculator", "Performs calculations", Map.of());

            String str = tool.toString();
            assertTrue(str.contains("calculator"));
            assertTrue(str.contains("Performs calculations"));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiTool tool = new AguiTool("search", "Search for data", Map.of("type", "object"));

            String json = objectMapper.writeValueAsString(tool);
            assertTrue(json.contains("\"name\":\"search\""));
            assertTrue(json.contains("\"description\":\"Search for data\""));

            AguiTool deserialized = objectMapper.readValue(json, AguiTool.class);
            assertEquals(tool.getName(), deserialized.getName());
        }
    }

    @Nested
    class AguiContextTest {

        @Test
        void testCreation() {
            AguiContext context = new AguiContext("Current user", "John Doe");

            assertEquals("Current user", context.getDescription());
            assertEquals("John Doe", context.getValue());
        }

        @Test
        void testNullDescriptionThrows() {
            assertThrows(NullPointerException.class, () -> new AguiContext(null, "value"));
        }

        @Test
        void testNullValueThrows() {
            assertThrows(NullPointerException.class, () -> new AguiContext("description", null));
        }

        @Test
        void testEquals() {
            AguiContext c1 = new AguiContext("desc", "val");
            AguiContext c2 = new AguiContext("desc", "val");
            AguiContext c3 = new AguiContext("other", "val");

            assertEquals(c1, c2);
            assertNotEquals(c1, c3);
            assertNotEquals(c1, null);
            assertNotEquals(c1, "string");
        }

        @Test
        void testHashCode() {
            AguiContext c1 = new AguiContext("desc", "val");
            AguiContext c2 = new AguiContext("desc", "val");

            assertEquals(c1.hashCode(), c2.hashCode());
        }

        @Test
        void testToString() {
            AguiContext context = new AguiContext("User location", "New York");

            String str = context.toString();
            assertTrue(str.contains("User location"));
            assertTrue(str.contains("New York"));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            AguiContext context = new AguiContext("Time zone", "UTC");

            String json = objectMapper.writeValueAsString(context);
            assertTrue(json.contains("\"description\":\"Time zone\""));
            assertTrue(json.contains("\"value\":\"UTC\""));

            AguiContext deserialized = objectMapper.readValue(json, AguiContext.class);
            assertEquals(context.getDescription(), deserialized.getDescription());
        }
    }

    @Nested
    class RunAgentInputTest {

        @Test
        void testBuilderBasic() {
            RunAgentInput input =
                    RunAgentInput.builder()
                            .threadId("thread-1")
                            .runId("run-1")
                            .messages(List.of(AguiMessage.userMessage("m1", "Hello")))
                            .build();

            assertEquals("thread-1", input.getThreadId());
            assertEquals("run-1", input.getRunId());
            assertTrue(input.hasMessages());
            assertEquals(1, input.getMessages().size());
        }

        @Test
        void testBuilderWithAllFields() {
            AguiMessage message = AguiMessage.userMessage("m1", "Hello");
            AguiTool tool = new AguiTool("test_tool", "A test tool", Map.of("type", "object"));
            AguiContext context = new AguiContext("User name", "Alice");
            Map<String, Object> state = Map.of("count", 10);
            Map<String, Object> forwardedProps = Map.of("custom", "value");

            RunAgentInput input =
                    RunAgentInput.builder()
                            .threadId("thread-1")
                            .runId("run-1")
                            .messages(List.of(message))
                            .tools(List.of(tool))
                            .context(List.of(context))
                            .state(state)
                            .forwardedProps(forwardedProps)
                            .build();

            assertTrue(input.hasMessages());
            assertTrue(input.hasTools());
            assertTrue(input.hasContext());
            assertTrue(input.hasState());
            assertEquals("value", input.getForwardedProp("custom"));
        }

        @Test
        void testEmptyCollectionsWhenNull() {
            RunAgentInput input = RunAgentInput.builder().threadId("t1").runId("r1").build();

            assertNotNull(input.getMessages());
            assertTrue(input.getMessages().isEmpty());
            assertNotNull(input.getTools());
            assertTrue(input.getTools().isEmpty());
            assertNotNull(input.getContext());
            assertTrue(input.getContext().isEmpty());
            assertNotNull(input.getState());
            assertTrue(input.getState().isEmpty());
            assertNotNull(input.getForwardedProps());
            assertTrue(input.getForwardedProps().isEmpty());
        }

        @Test
        void testGetForwardedPropWithDefault() {
            RunAgentInput input = RunAgentInput.builder().threadId("t1").runId("r1").build();

            assertEquals("default", input.getForwardedProp("missing", "default"));
            assertNull(input.getForwardedProp("missing"));
        }

        @Test
        void testHasMethods() {
            RunAgentInput emptyInput = RunAgentInput.builder().threadId("t1").runId("r1").build();

            assertFalse(emptyInput.hasMessages());
            assertFalse(emptyInput.hasTools());
            assertFalse(emptyInput.hasContext());
            assertFalse(emptyInput.hasState());
        }

        @Test
        void testCollectionsAreImmutable() {
            RunAgentInput input =
                    RunAgentInput.builder()
                            .threadId("t1")
                            .runId("r1")
                            .messages(List.of(AguiMessage.userMessage("m1", "Hi")))
                            .build();

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> input.getMessages().add(AguiMessage.userMessage("m2", "World")));
        }

        @Test
        void testNullThreadIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> RunAgentInput.builder().threadId(null).runId("r1").build());
        }

        @Test
        void testNullRunIdThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> RunAgentInput.builder().threadId("t1").runId(null).build());
        }

        @Test
        void testToString() {
            RunAgentInput input =
                    RunAgentInput.builder()
                            .threadId("thread-123")
                            .runId("run-456")
                            .messages(List.of(AguiMessage.userMessage("m1", "Hello")))
                            .build();

            String str = input.toString();
            assertTrue(str.contains("thread-123"));
            assertTrue(str.contains("run-456"));
            assertTrue(str.contains("messages=1"));
        }

        @Test
        void testJsonSerialization() throws JsonProcessingException {
            RunAgentInput input =
                    RunAgentInput.builder()
                            .threadId("t1")
                            .runId("r1")
                            .messages(List.of(AguiMessage.userMessage("m1", "Test")))
                            .state(Map.of("key", "value"))
                            .build();

            String json = objectMapper.writeValueAsString(input);
            assertTrue(json.contains("\"threadId\":\"t1\""));
            assertTrue(json.contains("\"runId\":\"r1\""));

            RunAgentInput deserialized = objectMapper.readValue(json, RunAgentInput.class);
            assertEquals(input.getThreadId(), deserialized.getThreadId());
            assertEquals(input.getMessages().size(), deserialized.getMessages().size());
        }

        @Test
        void testJsonCreatorConstructor() throws JsonProcessingException {
            String json =
                    "{\"threadId\":\"t1\",\"runId\":\"r1\","
                        + "\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"Hello\"}],"
                        + "\"tools\":[],\"context\":[],\"state\":{\"key\":\"value\"},"
                        + "\"forwardedProps\":{\"prop1\":123}}";

            RunAgentInput input = objectMapper.readValue(json, RunAgentInput.class);

            assertEquals("t1", input.getThreadId());
            assertEquals("r1", input.getRunId());
            assertEquals(1, input.getMessages().size());
            assertEquals("value", input.getState().get("key"));
            assertEquals(123, input.getForwardedProp("prop1"));
        }
    }

    @Nested
    class ToolMergeModeTest {

        @Test
        void testAllModesExist() {
            assertNotNull(ToolMergeMode.FRONTEND_ONLY);
            assertNotNull(ToolMergeMode.AGENT_ONLY);
            assertNotNull(ToolMergeMode.MERGE_FRONTEND_PRIORITY);
        }

        @Test
        void testModeCount() {
            assertEquals(3, ToolMergeMode.values().length);
        }

        @Test
        void testValueOf() {
            assertEquals(ToolMergeMode.FRONTEND_ONLY, ToolMergeMode.valueOf("FRONTEND_ONLY"));
            assertEquals(ToolMergeMode.AGENT_ONLY, ToolMergeMode.valueOf("AGENT_ONLY"));
            assertEquals(
                    ToolMergeMode.MERGE_FRONTEND_PRIORITY,
                    ToolMergeMode.valueOf("MERGE_FRONTEND_PRIORITY"));
        }
    }
}
