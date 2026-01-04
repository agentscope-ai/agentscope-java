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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.formatter.dashscope.dto.DashScopeFunction;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.formatter.dashscope.dto.DashScopeToolCall;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Ground truth test for DashScope formatters with text-only messages.
 * Tests various combinations of messages with tools but no multimodal content.
 * Ground truth is built using DashScope SDK POJOs directly in test code.
 */
public class DashScopeTextOnlyGroundTruthTest {

    private static DashScopeChatFormatter chatFormatter;
    private static DashScopeMultiAgentFormatter multiAgentFormatter;

    @BeforeAll
    static void setUp() {
        chatFormatter = new DashScopeChatFormatter();
        multiAgentFormatter = new DashScopeMultiAgentFormatter();
    }

    // ========== DashScopeChatFormatter Tests ==========

    @Test
    void testChatFormatter_SimpleConversation() {
        // Input messages
        List<Msg> inputMsgs =
                List.of(
                        msg("system", "You are a helpful assistant.", MsgRole.SYSTEM),
                        msg("user", "What is 2+2?", MsgRole.USER),
                        msg("assistant", "2+2 equals 4.", MsgRole.ASSISTANT));

        // Expected output
        List<DashScopeMessage> expected =
                List.of(
                        buildMessage("system", "You are a helpful assistant."),
                        buildMessage("user", "What is 2+2?"),
                        buildMessage("assistant", "2+2 equals 4."));

        // Test
        List<DashScopeMessage> actual = chatFormatter.format(inputMsgs);
        assertMessagesEqual(expected, actual);
    }

    @Test
    void testChatFormatter_WithSingleTool() {
        // Input messages
        List<Msg> inputMsgs =
                List.of(
                        msg("system", "You are a helpful assistant with tools.", MsgRole.SYSTEM),
                        msg("user", "What's the weather in Beijing?", MsgRole.USER),
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call_1")
                                                .name("get_weather")
                                                .input(Map.of("city", "Beijing"))
                                                .build())
                                .build(),
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call_1")
                                                .name("get_weather")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "The weather in"
                                                                                    + " Beijing is"
                                                                                    + " sunny,"
                                                                                    + " 25°C.")
                                                                        .build()))
                                                .build())
                                .build(),
                        msg(
                                "assistant",
                                "The weather in Beijing is sunny with a temperature of 25°C.",
                                MsgRole.ASSISTANT));

        // Expected output
        List<DashScopeMessage> expected =
                List.of(
                        buildMessage("system", "You are a helpful assistant with tools."),
                        buildMessage("user", "What's the weather in Beijing?"),
                        buildAssistantWithToolCall(
                                "call_1", "get_weather", "{\"city\": \"Beijing\"}"),
                        buildToolMessage(
                                "call_1", "get_weather", "The weather in Beijing is sunny, 25°C."),
                        buildMessage(
                                "assistant",
                                "The weather in Beijing is sunny with a temperature of 25°C."));

        // Test
        List<DashScopeMessage> actual = chatFormatter.format(inputMsgs);
        assertMessagesEqual(expected, actual);
    }

    @Test
    void testChatFormatter_WithMultipleTools() {
        // Input messages
        List<Msg> inputMsgs =
                List.of(
                        msg("system", "You are a helpful assistant.", MsgRole.SYSTEM),
                        msg("user", "What's the weather in Beijing and Shanghai?", MsgRole.USER),
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("call_1")
                                                        .name("get_weather")
                                                        .input(Map.of("city", "Beijing"))
                                                        .build(),
                                                ToolUseBlock.builder()
                                                        .id("call_2")
                                                        .name("get_weather")
                                                        .input(Map.of("city", "Shanghai"))
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call_1")
                                                .name("get_weather")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "Beijing: sunny,"
                                                                                        + " 25°C")
                                                                        .build()))
                                                .build())
                                .build(),
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call_2")
                                                .name("get_weather")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "Shanghai: rainy,"
                                                                                        + " 20°C")
                                                                        .build()))
                                                .build())
                                .build(),
                        msg(
                                "assistant",
                                "Beijing is sunny at 25°C, Shanghai is rainy at 20°C.",
                                MsgRole.ASSISTANT));

        // Expected output
        List<DashScopeMessage> expected =
                List.of(
                        buildMessage("system", "You are a helpful assistant."),
                        buildMessage("user", "What's the weather in Beijing and Shanghai?"),
                        buildAssistantWithMultipleToolCalls(
                                List.of(
                                        new ToolCallSpec(
                                                "call_1", "get_weather", "{\"city\": \"Beijing\"}"),
                                        new ToolCallSpec(
                                                "call_2",
                                                "get_weather",
                                                "{\"city\": \"Shanghai\"}"))),
                        buildToolMessage("call_1", "get_weather", "Beijing: sunny, 25°C"),
                        buildToolMessage("call_2", "get_weather", "Shanghai: rainy, 20°C"),
                        buildMessage(
                                "assistant",
                                "Beijing is sunny at 25°C, Shanghai is rainy at 20°C."));

        // Test
        List<DashScopeMessage> actual = chatFormatter.format(inputMsgs);
        assertMessagesEqual(expected, actual);
    }

    @Test
    void testChatFormatter_NoSystemMessage() {
        // Input messages
        List<Msg> inputMsgs =
                List.of(
                        msg("user", "Hello!", MsgRole.USER),
                        msg("assistant", "Hi! How can I help you?", MsgRole.ASSISTANT));

        // Expected output
        List<DashScopeMessage> expected =
                List.of(
                        buildMessage("user", "Hello!"),
                        buildMessage("assistant", "Hi! How can I help you?"));

        // Test
        List<DashScopeMessage> actual = chatFormatter.format(inputMsgs);
        assertMessagesEqual(expected, actual);
    }

    @Test
    void testChatFormatter_OnlySystemMessage() {
        // Input messages
        List<Msg> inputMsgs =
                List.of(msg("system", "You are a helpful assistant.", MsgRole.SYSTEM));

        // Expected output
        List<DashScopeMessage> expected =
                List.of(buildMessage("system", "You are a helpful assistant."));

        // Test
        List<DashScopeMessage> actual = chatFormatter.format(inputMsgs);
        assertMessagesEqual(expected, actual);
    }

    @Test
    void testChatFormatter_EmptyAssistantWithTool() {
        // Input messages
        List<Msg> inputMsgs =
                List.of(
                        msg("system", "You are a helpful assistant.", MsgRole.SYSTEM),
                        msg("user", "Search for Python", MsgRole.USER),
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call_1")
                                                .name("search")
                                                .input(Map.of("query", "Python"))
                                                .build())
                                .build(),
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call_1")
                                                .name("search")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "Python is a"
                                                                                    + " programming"
                                                                                    + " language.")
                                                                        .build()))
                                                .build())
                                .build(),
                        msg("assistant", "Python is a programming language.", MsgRole.ASSISTANT));

        // Expected output
        List<DashScopeMessage> expected =
                List.of(
                        buildMessage("system", "You are a helpful assistant."),
                        buildMessage("user", "Search for Python"),
                        buildAssistantWithToolCall("call_1", "search", "{\"query\": \"Python\"}"),
                        buildToolMessage("call_1", "search", "Python is a programming language."),
                        buildMessage("assistant", "Python is a programming language."));

        // Test
        List<DashScopeMessage> actual = chatFormatter.format(inputMsgs);
        assertMessagesEqual(expected, actual);
    }

    // ========== DashScopeMultiAgentFormatter Tests ==========

    @Test
    void testMultiAgentFormatter_Simple() {
        // Input messages
        List<Msg> inputMsgs =
                List.of(
                        msg("system", "You are a helpful assistant.", MsgRole.SYSTEM),
                        msg("Alice", "Hello Bob!", MsgRole.USER),
                        msg("Bob", "Hi Alice, how are you?", MsgRole.ASSISTANT),
                        msg("Alice", "I'm fine, thanks!", MsgRole.USER));

        // Expected output
        List<DashScopeMessage> expected =
                List.of(
                        buildMessage("system", "You are a helpful assistant."),
                        buildMessage(
                                "user",
                                "# Conversation History\n"
                                    + "The content between <history></history> tags contains your"
                                    + " conversation history\n"
                                    + "<history>\n"
                                    + "Alice: Hello Bob!\n"
                                    + "Bob: Hi Alice, how are you?\n"
                                    + "Alice: I'm fine, thanks!\n"
                                    + "</history>"));

        // Test
        List<DashScopeMessage> actual = multiAgentFormatter.format(inputMsgs);
        assertMessagesEqual(expected, actual);
    }

    @Test
    void testMultiAgentFormatter_WithTools() {
        // Input messages
        List<Msg> inputMsgs =
                List.of(
                        msg("system", "You are a helpful assistant.", MsgRole.SYSTEM),
                        msg("User", "What's the weather?", MsgRole.USER),
                        msg("Assistant", "Let me check that for you.", MsgRole.ASSISTANT),
                        Msg.builder()
                                .name("Assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call_1")
                                                .name("get_weather")
                                                .input(Map.of("city", "Beijing"))
                                                .build())
                                .build(),
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call_1")
                                                .name("get_weather")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Sunny, 25°C")
                                                                        .build()))
                                                .build())
                                .build(),
                        msg("Assistant", "The weather is sunny, 25°C.", MsgRole.ASSISTANT));

        // Expected output
        List<DashScopeMessage> expected =
                List.of(
                        buildMessage("system", "You are a helpful assistant."),
                        buildMessage(
                                "user",
                                "# Conversation History\n"
                                    + "The content between <history></history> tags contains your"
                                    + " conversation history\n"
                                    + "<history>\n"
                                    + "User: What's the weather?\n"
                                    + "Assistant: Let me check that for you.\n"
                                    + "</history>"),
                        buildAssistantWithToolCall(
                                "call_1", "get_weather", "{\"city\": \"Beijing\"}"),
                        buildToolMessage("call_1", "get_weather", "Sunny, 25°C"),
                        buildMessage(
                                "user",
                                "<history>\n"
                                        + "Assistant: The weather is sunny, 25°C.\n"
                                        + "</history>"));

        // Test
        List<DashScopeMessage> actual = multiAgentFormatter.format(inputMsgs);
        assertMessagesEqual(expected, actual);
    }

    @Test
    void testMultiAgentFormatter_MultipleRounds() {
        // Input messages
        List<Msg> inputMsgs =
                List.of(
                        msg("system", "You are a helpful assistant.", MsgRole.SYSTEM),
                        msg("User", "Tell me about Beijing", MsgRole.USER),
                        Msg.builder()
                                .name("Assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call_1")
                                                .name("get_info")
                                                .input(Map.of("topic", "Beijing"))
                                                .build())
                                .build(),
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call_1")
                                                .name("get_info")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "Beijing is the"
                                                                                    + " capital of"
                                                                                    + " China.")
                                                                        .build()))
                                                .build())
                                .build(),
                        msg("Assistant", "Beijing is the capital of China.", MsgRole.ASSISTANT),
                        msg("User", "What about Shanghai?", MsgRole.USER),
                        Msg.builder()
                                .name("Assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call_2")
                                                .name("get_info")
                                                .input(Map.of("topic", "Shanghai"))
                                                .build())
                                .build(),
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call_2")
                                                .name("get_info")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "Shanghai is the"
                                                                                        + " largest"
                                                                                        + " city in"
                                                                                        + " China.")
                                                                        .build()))
                                                .build())
                                .build(),
                        msg(
                                "Assistant",
                                "Shanghai is the largest city in China.",
                                MsgRole.ASSISTANT));

        // Expected output
        List<DashScopeMessage> expected =
                List.of(
                        buildMessage("system", "You are a helpful assistant."),
                        buildMessage(
                                "user",
                                "# Conversation History\n"
                                    + "The content between <history></history> tags contains your"
                                    + " conversation history\n"
                                    + "<history>\n"
                                    + "User: Tell me about Beijing\n"
                                    + "</history>"),
                        buildAssistantWithToolCall(
                                "call_1", "get_info", "{\"topic\": \"Beijing\"}"),
                        buildToolMessage("call_1", "get_info", "Beijing is the capital of China."),
                        buildMessage(
                                "user",
                                "<history>\n"
                                        + "Assistant: Beijing is the capital of China.\n"
                                        + "User: What about Shanghai?\n"
                                        + "</history>"),
                        buildAssistantWithToolCall(
                                "call_2", "get_info", "{\"topic\": \"Shanghai\"}"),
                        buildToolMessage(
                                "call_2", "get_info", "Shanghai is the largest city in China."),
                        buildMessage(
                                "user",
                                "<history>\n"
                                        + "Assistant: Shanghai is the largest city in China.\n"
                                        + "</history>"));

        // Test
        List<DashScopeMessage> actual = multiAgentFormatter.format(inputMsgs);
        assertMessagesEqual(expected, actual);
    }

    @Test
    void testMultiAgentFormatter_NoSystem() {
        // Input messages
        List<Msg> inputMsgs =
                List.of(
                        msg("User", "Hello", MsgRole.USER),
                        msg("Assistant", "Hi there!", MsgRole.ASSISTANT));

        // Expected output
        List<DashScopeMessage> expected =
                List.of(
                        buildMessage(
                                "user",
                                "# Conversation History\n"
                                    + "The content between <history></history> tags contains your"
                                    + " conversation history\n"
                                    + "<history>\n"
                                    + "User: Hello\n"
                                    + "Assistant: Hi there!\n"
                                    + "</history>"));

        // Test
        List<DashScopeMessage> actual = multiAgentFormatter.format(inputMsgs);
        assertMessagesEqual(expected, actual);
    }

    @Test
    void testMultiAgentFormatter_NoTools() {
        // Input messages
        List<Msg> inputMsgs =
                List.of(
                        msg("system", "You are a helpful assistant.", MsgRole.SYSTEM),
                        msg("Alice", "What do you think about AI?", MsgRole.USER),
                        msg("Bob", "I think AI is fascinating.", MsgRole.ASSISTANT),
                        msg("Alice", "I agree!", MsgRole.USER));

        // Expected output
        List<DashScopeMessage> expected =
                List.of(
                        buildMessage("system", "You are a helpful assistant."),
                        buildMessage(
                                "user",
                                "# Conversation History\n"
                                    + "The content between <history></history> tags contains your"
                                    + " conversation history\n"
                                    + "<history>\n"
                                    + "Alice: What do you think about AI?\n"
                                    + "Bob: I think AI is fascinating.\n"
                                    + "Alice: I agree!\n"
                                    + "</history>"));

        // Test
        List<DashScopeMessage> actual = multiAgentFormatter.format(inputMsgs);
        assertMessagesEqual(expected, actual);
    }

    // ========== Helper Methods ==========

    private static Msg msg(String name, String content, MsgRole role) {
        return Msg.builder()
                .name(name)
                .role(role)
                .content(TextBlock.builder().text(content).build())
                .build();
    }

    private static DashScopeMessage buildMessage(String role, String content) {
        return DashScopeMessage.builder().role(role).content(content).build();
    }

    private static DashScopeMessage buildAssistantWithToolCall(
            String callId, String toolName, String arguments) {
        return buildAssistantWithMultipleToolCalls(
                List.of(new ToolCallSpec(callId, toolName, arguments)));
    }

    private static DashScopeMessage buildAssistantWithMultipleToolCalls(
            List<ToolCallSpec> toolCalls) {
        List<DashScopeToolCall> toolCallList =
                toolCalls.stream()
                        .map(
                                spec -> {
                                    DashScopeFunction function = new DashScopeFunction();
                                    function.setName(spec.name);
                                    function.setArguments(spec.arguments);

                                    DashScopeToolCall toolCall = new DashScopeToolCall();
                                    toolCall.setId(spec.id);
                                    toolCall.setType("function");
                                    toolCall.setFunction(function);
                                    return toolCall;
                                })
                        .toList();

        return DashScopeMessage.builder()
                .role("assistant")
                .content((String) null)
                .toolCalls(toolCallList)
                .build();
    }

    private static DashScopeMessage buildToolMessage(
            String callId, String toolName, String content) {
        return DashScopeMessage.builder()
                .role("tool")
                .toolCallId(callId)
                .name(toolName)
                .content(content)
                .build();
    }

    private static void assertMessagesEqual(
            List<DashScopeMessage> expected, List<DashScopeMessage> actual) {
        if (expected.size() != actual.size()) {
            System.out.println("\n=== Expected Messages ===");
            for (int i = 0; i < expected.size(); i++) {
                DashScopeMessage msg = expected.get(i);
                String contentPreview =
                        msg.getContentAsString() != null
                                ? msg.getContentAsString()
                                        .substring(
                                                0, Math.min(150, msg.getContentAsString().length()))
                                : "null";
                System.out.println(
                        i + ": role=" + msg.getRole() + ", content=" + contentPreview + "...");
            }
            System.out.println("\n=== Actual Messages ===");
            for (int i = 0; i < actual.size(); i++) {
                DashScopeMessage msg = actual.get(i);
                String contentPreview =
                        msg.getContentAsString() != null
                                ? msg.getContentAsString()
                                        .substring(
                                                0, Math.min(150, msg.getContentAsString().length()))
                                : "null";
                System.out.println(
                        i + ": role=" + msg.getRole() + ", content=" + contentPreview + "...");
            }
            System.out.println();
        }
        assertEquals(
                expected.size(),
                actual.size(),
                String.format("Expected %d messages but got %d", expected.size(), actual.size()));

        for (int i = 0; i < expected.size(); i++) {
            DashScopeMessage expectedMsg = expected.get(i);
            DashScopeMessage actualMsg = actual.get(i);

            assertEquals(
                    expectedMsg.getRole(),
                    actualMsg.getRole(),
                    String.format("Message %d: role mismatch", i));

            // Compare content
            assertEquals(
                    expectedMsg.getContentAsString(),
                    actualMsg.getContentAsString(),
                    String.format("Message %d: content mismatch", i));

            // Compare tool calls
            if (expectedMsg.getToolCalls() != null) {
                assertNotNull(
                        actualMsg.getToolCalls(),
                        String.format("Message %d: expected tool_calls", i));
                assertEquals(
                        expectedMsg.getToolCalls().size(),
                        actualMsg.getToolCalls().size(),
                        String.format("Message %d: tool_calls count mismatch", i));

                for (int j = 0; j < expectedMsg.getToolCalls().size(); j++) {
                    DashScopeToolCall expectedCall = expectedMsg.getToolCalls().get(j);
                    DashScopeToolCall actualCall = actualMsg.getToolCalls().get(j);

                    assertEquals(
                            expectedCall.getId(),
                            actualCall.getId(),
                            String.format("Message %d, tool_call %d: id mismatch", i, j));
                    assertEquals(
                            expectedCall.getType(),
                            actualCall.getType(),
                            String.format("Message %d, tool_call %d: type mismatch", i, j));

                    assertEquals(
                            expectedCall.getFunction().getName(),
                            actualCall.getFunction().getName(),
                            String.format(
                                    "Message %d, tool_call %d: function name mismatch", i, j));
                    // Compare arguments (normalize JSON whitespace)
                    String expectedArgs = normalizeJson(expectedCall.getFunction().getArguments());
                    String actualArgs = normalizeJson(actualCall.getFunction().getArguments());
                    assertEquals(
                            expectedArgs,
                            actualArgs,
                            String.format(
                                    "Message %d, tool_call %d: function arguments mismatch", i, j));
                }
            }

            // Compare tool-specific fields
            assertEquals(
                    expectedMsg.getToolCallId(),
                    actualMsg.getToolCallId(),
                    String.format("Message %d: tool_call_id mismatch", i));
            assertEquals(
                    expectedMsg.getName(),
                    actualMsg.getName(),
                    String.format("Message %d: name mismatch", i));
        }
    }

    private static String normalizeJson(Object args) {
        if (args == null) {
            return "";
        }
        // Remove whitespace from JSON for comparison
        return args.toString().replaceAll("\\s+", "");
    }

    private static class ToolCallSpec {
        final String id;
        final String name;
        final String arguments;

        ToolCallSpec(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
    }
}
