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
package io.agentscope.core.agent.test;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility methods for Agent module tests.
 *
 * This class provides helper methods for creating test data and common operations.
 */
public class TestUtils {

    /**
     * Create a simple user message with text content.
     */
    public static Msg createUserMessage(String name, String text) {
        return Msg.builder()
                .name(name)
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /**
     * Create a simple assistant message with text content.
     */
    public static Msg createAssistantMessage(String name, String text) {
        return Msg.builder()
                .name(name)
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /**
     * Create a thinking message.
     */
    public static Msg createThinkingMessage(String name, String thinking) {
        return Msg.builder()
                .name(name)
                .role(MsgRole.ASSISTANT)
                .content(ThinkingBlock.builder().thinking(thinking).build())
                .build();
    }

    /**
     * Create a tool use message.
     */
    public static Msg createToolUseMessage(
            String name, String toolName, String toolCallId, Map<String, Object> arguments) {
        return Msg.builder()
                .name(name)
                .role(MsgRole.ASSISTANT)
                .content(
                        ToolUseBlock.builder()
                                .name(toolName)
                                .id(toolCallId)
                                .input(arguments != null ? arguments : new HashMap<>())
                                .build())
                .build();
    }

    /**
     * Create a tool result message.
     */
    public static Msg createToolResultMessage(String toolCallId, String result) {
        return Msg.builder()
                .name("tool")
                .role(MsgRole.TOOL)
                .content(
                        ToolResultBlock.builder()
                                .id(toolCallId)
                                .output(TextBlock.builder().text(result).build())
                                .build())
                .build();
    }

    /**
     * Extract text content from a message.
     */
    public static String extractTextContent(Msg msg) {
        if (msg == null) {
            return null;
        }

        return msg.getContent().stream()
                .map(
                        block -> {
                            if (block instanceof TextBlock) {
                                return ((TextBlock) block).getText();
                            } else if (block instanceof ThinkingBlock) {
                                return ((ThinkingBlock) block).getThinking();
                            }
                            return "";
                        })
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Check if a message is a tool use message.
     */
    public static boolean isToolUseMessage(Msg msg) {
        return msg != null && msg.hasContentBlocks(ToolUseBlock.class);
    }

    /**
     * Check if a message is a text message.
     */
    public static boolean isTextMessage(Msg msg) {
        return msg != null && msg.hasContentBlocks(TextBlock.class);
    }

    /**
     * Check if a message is a thinking message.
     */
    public static boolean isThinkingMessage(Msg msg) {
        return msg != null && msg.hasContentBlocks(ThinkingBlock.class);
    }

    /**
     * Extract tool name from a tool use message.
     */
    public static String extractToolName(Msg msg) {
        if (!isToolUseMessage(msg)) {
            return null;
        }

        ToolUseBlock toolUse = msg.getFirstContentBlock(ToolUseBlock.class);
        return toolUse.getName();
    }

    /**
     * Extract tool call ID from a tool use message.
     */
    public static String extractToolCallId(Msg msg) {
        if (!isToolUseMessage(msg)) {
            return null;
        }

        ToolUseBlock toolUse = msg.getFirstContentBlock(ToolUseBlock.class);
        return toolUse.getId();
    }

    /**
     * Create a simple arguments map for tool calls.
     */
    public static Map<String, Object> createToolArguments(String... keyValuePairs) {
        Map<String, Object> arguments = new HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            if (i + 1 < keyValuePairs.length) {
                arguments.put(keyValuePairs[i], keyValuePairs[i + 1]);
            }
        }
        return arguments;
    }

    /**
     * Sleep for a specified duration (for testing async operations).
     */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private TestUtils() {
        // Utility class, prevent instantiation
    }
}
