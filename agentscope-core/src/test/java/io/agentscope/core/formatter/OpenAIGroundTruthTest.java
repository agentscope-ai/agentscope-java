/*
 * Copyright 2024-2025 the original author or authors.
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.models.chat.completions.ChatCompletionMessageParam;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.formatter.openai.OpenAIMultiAgentFormatter;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * OpenAI formatter ground truth tests based on Python implementation.
 * These tests verify that the Java implementation matches Python's behavior exactly.
 */
public class OpenAIGroundTruthTest {

    private static final String FAKE_IMAGE_BASE64 = "ZmFrZSBpbWFnZSBjb250ZW50";
    private static final String FAKE_AUDIO_BASE64 = "ZmFrZSBhdWRpbyBjb250ZW50";

    @Test
    public void testChatFormatter_FullConversation() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        List<Msg> msgs = buildFullConversation();
        List<ChatCompletionMessageParam> result = formatter.format(msgs);

        // Based on Python ground truth: should have 9 messages
        // System, User+Image, Assistant, User+Audio, Assistant, User, Assistant+ToolCall, Tool,
        // Assistant
        assertEquals(9, result.size(), "Should have 9 messages");

        // Verify each message type
        // Message 0: System
        assertEquals("system", extractRole(result.get(0)));
        assertTrue(extractContent(result.get(0)).contains("helpful assistant"));

        // Message 1: User with image
        assertEquals("user", extractRole(result.get(1)));
        assertTrue(extractContent(result.get(1)).contains("capital of France"));

        // Message 2: Assistant
        assertEquals("assistant", extractRole(result.get(2)));
        assertTrue(extractContent(result.get(2)).contains("Paris"));

        // Message 3: User with audio
        assertEquals("user", extractRole(result.get(3)));
        assertTrue(extractContent(result.get(3)).contains("capital of Germany"));

        // Message 4: Assistant
        assertEquals("assistant", extractRole(result.get(4)));
        assertTrue(extractContent(result.get(4)).contains("Berlin"));

        // Message 5: User simple
        assertEquals("user", extractRole(result.get(5)));
        assertTrue(extractContent(result.get(5)).contains("capital of Japan"));

        // Message 6: Assistant with tool call
        assertEquals("assistant", extractRole(result.get(6)));

        // Message 7: Tool result
        assertEquals("tool", extractRole(result.get(7)));
        assertTrue(extractContent(result.get(7)).contains("Tokyo"));

        // Message 8: Assistant
        assertEquals("assistant", extractRole(result.get(8)));
        assertTrue(extractContent(result.get(8)).contains("Tokyo"));
    }

    @Test
    public void testChatFormatter_WithoutSystemMessage() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        List<Msg> msgs = buildFullConversation();
        // Remove system message
        msgs = msgs.subList(1, msgs.size());

        List<ChatCompletionMessageParam> result = formatter.format(msgs);

        // Should have 8 messages (without system)
        assertEquals(8, result.size());

        // First message should be user
        assertEquals("user", extractRole(result.get(0)));
    }

    @Test
    public void testMultiAgentFormatter_FullConversation() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        List<Msg> msgs = buildFullConversation();
        List<ChatCompletionMessageParam> result = formatter.format(msgs);

        // Based on Python ground truth: should have 5 messages
        // System, User(merged conversation+media), Assistant+ToolCall, Tool, User(final
        // assistant)
        assertEquals(5, result.size(), "MultiAgent should merge conversation into 5 messages");

        // Message 0: System
        assertEquals("system", extractRole(result.get(0)));

        // Message 1: User (merged conversation with history tags)
        assertEquals("user", extractRole(result.get(1)));
        String userContent = extractContent(result.get(1));
        assertTrue(userContent.contains("<history>"), "Should contain history tags");
        assertTrue(userContent.contains("</history>"), "Should contain closing history tag");
        assertTrue(userContent.contains("capital of France"), "Should contain France question");
        assertTrue(userContent.contains("capital of Germany"), "Should contain Germany question");
        assertTrue(userContent.contains("capital of Japan"), "Should contain Japan question");

        // Message 2: Assistant with tool call
        assertEquals("assistant", extractRole(result.get(2)));

        // Message 3: Tool result
        assertEquals("tool", extractRole(result.get(3)));

        // Message 4: User (final assistant response wrapped in history)
        assertEquals("user", extractRole(result.get(4)));
        assertTrue(extractContent(result.get(4)).contains("<history>"));
    }

    @Test
    public void testMultiAgentFormatter_WithoutConversation() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        List<Msg> msgs = new ArrayList<>();

        // System message
        msgs.add(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .name("system")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("You're a helpful assistant.")
                                                .build()))
                        .build());

        // Add only tool messages (no conversation)
        msgs.addAll(buildToolMessages());

        List<ChatCompletionMessageParam> result = formatter.format(msgs);

        // Based on Python ground truth: should have 4 messages
        // System, Assistant+ToolCall, Tool, User(assistant response)
        assertEquals(4, result.size());

        // First should be system
        assertEquals("system", extractRole(result.get(0)));

        // Second should be assistant with tool call
        assertEquals("assistant", extractRole(result.get(1)));

        // Third should be tool
        assertEquals("tool", extractRole(result.get(2)));

        // Fourth should be user with history
        assertEquals("user", extractRole(result.get(3)));
        assertTrue(extractContent(result.get(3)).contains("# Conversation History"));
    }

    // Helper methods

    private List<Msg> buildFullConversation() {
        List<Msg> msgs = new ArrayList<>();

        // System message
        msgs.add(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .name("system")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("You're a helpful assistant.")
                                                .build()))
                        .build());

        // User with text and image
        msgs.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What is the capital of France?")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("image/png")
                                                                .data(FAKE_IMAGE_BASE64)
                                                                .build())
                                                .build()))
                        .build());

        // Assistant response
        msgs.add(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("The capital of France is Paris.")
                                                .build()))
                        .build());

        // User with text and audio
        msgs.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What is the capital of Germany?")
                                                .build(),
                                        AudioBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("audio/wav")
                                                                .data(FAKE_AUDIO_BASE64)
                                                                .build())
                                                .build()))
                        .build());

        // Assistant response
        msgs.add(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("The capital of Germany is Berlin.")
                                                .build()))
                        .build());

        // User simple text
        msgs.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What is the capital of Japan?")
                                                .build()))
                        .build());

        msgs.addAll(buildToolMessages());

        return msgs;
    }

    private List<Msg> buildToolMessages() {
        List<Msg> msgs = new ArrayList<>();

        // Assistant with tool call
        Map<String, Object> toolArgs = new HashMap<>();
        toolArgs.put("country", "Japan");
        msgs.add(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("1")
                                                .name("get_capital")
                                                .input(toolArgs)
                                                .build()))
                        .build());

        // Tool result with multimodal content
        msgs.add(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("1")
                                                .name("get_capital")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "The capital of"
                                                                                    + " Japan is"
                                                                                    + " Tokyo.")
                                                                        .build(),
                                                                ImageBlock.builder()
                                                                        .source(
                                                                                Base64Source
                                                                                        .builder()
                                                                                        .mediaType(
                                                                                                "image/png")
                                                                                        .data(
                                                                                                FAKE_IMAGE_BASE64)
                                                                                        .build())
                                                                        .build(),
                                                                AudioBlock.builder()
                                                                        .source(
                                                                                Base64Source
                                                                                        .builder()
                                                                                        .mediaType(
                                                                                                "audio/wav")
                                                                                        .data(
                                                                                                FAKE_AUDIO_BASE64)
                                                                                        .build())
                                                                        .build()))
                                                .build()))
                        .build());

        // Final assistant response
        msgs.add(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("The capital of Japan is Tokyo.")
                                                .build()))
                        .build());

        return msgs;
    }

    private String extractRole(ChatCompletionMessageParam msg) {
        String str = msg.toString();
        if (str.contains("ChatCompletionSystemMessageParam")) return "system";
        if (str.contains("ChatCompletionUserMessageParam")) return "user";
        if (str.contains("ChatCompletionAssistantMessageParam")) return "assistant";
        if (str.contains("ChatCompletionToolMessageParam")) return "tool";
        return "unknown";
    }

    private String extractContent(ChatCompletionMessageParam msg) {
        return msg.toString();
    }
}
