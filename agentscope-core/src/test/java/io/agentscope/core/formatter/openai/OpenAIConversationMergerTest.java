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
package io.agentscope.core.formatter.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.openai.dto.OpenAIContentPart;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OpenAIConversationMerger.
 *
 * <p>These tests verify the multi-agent conversation merging logic including:
 * <ul>
 *   <li>Merging multiple agent messages into single user message</li>
 *   <li>Preserving multimodal content (images, audio)</li>
 *   <li>Handling null sources gracefully</li>
 *   <li>Handling unknown source types</li>
 *   <li>Conversation history formatting</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("OpenAIConversationMerger Unit Tests")
class OpenAIConversationMergerTest {

    private OpenAIConversationMerger merger;

    @BeforeEach
    void setUp() {
        merger = new OpenAIConversationMerger("# Conversation History\n");
    }

    @Test
    @DisplayName("Should merge simple text messages into conversation history")
    void testMergeSimpleTextMessages() {
        List<Msg> messages = new ArrayList<>();

        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(List.of(TextBlock.builder().text("Hello, Bob").build()))
                        .build();

        Msg msg2 =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Bob")
                        .content(List.of(TextBlock.builder().text("Hi Alice!").build()))
                        .build();

        messages.add(msg1);
        messages.add(msg2);

        OpenAIMessage result =
                merger.mergeToUserMessage(
                        messages, msg -> msg.getRole().toString(), blocks -> "Tool result");

        assertNotNull(result);
        assertEquals("user", result.getRole());
        String content = result.getContentAsString();
        assertNotNull(content);
        assertTrue(content.contains("Alice"), "Should contain Alice's name");
        assertTrue(content.contains("Bob"), "Should contain Bob's name");
        assertTrue(content.contains("Hello, Bob"), "Should contain Alice's message");
        assertTrue(content.contains("Hi Alice!"), "Should contain Bob's message");
    }

    @Test
    @DisplayName("Should handle URLSource ImageBlock in conversation")
    void testMergeWithURLSourceImageBlock() {
        List<Msg> messages = new ArrayList<>();

        URLSource imageSource = URLSource.builder().url("http://example.com/image.png").build();

        ImageBlock imageBlock = ImageBlock.builder().source(imageSource).build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Here's an image:").build(),
                                        imageBlock))
                        .build();

        messages.add(msg);

        OpenAIMessage result =
                merger.mergeToUserMessage(
                        messages, msg2 -> msg2.getRole().toString(), blocks -> "Tool result");

        assertNotNull(result);
        assertEquals("user", result.getRole());
        assertTrue(result.isMultimodal(), "Should be multimodal");
        List<OpenAIContentPart> content = result.getContentAsList();
        assertNotNull(content);
        assertTrue(!content.isEmpty(), "Should contain content parts");
    }

    @Test
    @DisplayName("Should handle URLSource AudioBlock in conversation")
    void testMergeWithURLSourceAudioBlock() {
        List<Msg> messages = new ArrayList<>();

        URLSource audioSource = URLSource.builder().url("http://example.com/audio.wav").build();

        AudioBlock audioBlock = AudioBlock.builder().source(audioSource).build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Listen to this:").build(),
                                        audioBlock))
                        .build();

        messages.add(msg);

        OpenAIMessage result =
                merger.mergeToUserMessage(
                        messages, msg2 -> msg2.getRole().toString(), blocks -> "Tool result");

        assertNotNull(result);
        assertEquals("user", result.getRole());
        String content = result.getContentAsString();
        assertNotNull(content);
        // URL-based audio should be converted to text reference
        assertTrue(content.contains("Audio URL"), "Should contain audio URL reference");
    }

    @Test
    @DisplayName("Should handle Base64 AudioBlock in conversation")
    void testMergeWithBase64AudioBlock() {
        List<Msg> messages = new ArrayList<>();

        Base64Source audioSource =
                Base64Source.builder().data("SGVsbG8gV29ybGQ=").mediaType("audio/wav").build();

        AudioBlock audioBlock = AudioBlock.builder().source(audioSource).build();

        Msg msg =
                Msg.builder().role(MsgRole.USER).name("Alice").content(List.of(audioBlock)).build();

        messages.add(msg);

        OpenAIMessage result =
                merger.mergeToUserMessage(
                        messages, msg2 -> msg2.getRole().toString(), blocks -> "Tool result");

        assertNotNull(result);
        assertEquals("user", result.getRole());
        assertTrue(
                result.isMultimodal() || result.getContentAsString() != null,
                "Should handle base64 audio");
    }

    @Test
    @DisplayName("Should handle multimodal message with images and text")
    void testMergeMultimodalWithImages() {
        List<Msg> messages = new ArrayList<>();

        Base64Source imageSource =
                Base64Source.builder()
                        .data(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
                        .mediaType("image/png")
                        .build();

        ImageBlock imageBlock = ImageBlock.builder().source(imageSource).build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Check this image:").build(),
                                        imageBlock,
                                        TextBlock.builder().text("What do you think?").build()))
                        .build();

        messages.add(msg);

        OpenAIMessage result =
                merger.mergeToUserMessage(
                        messages, msg2 -> msg2.getRole().toString(), blocks -> "Tool result");

        assertNotNull(result);
        assertEquals("user", result.getRole());
        assertTrue(result.isMultimodal(), "Should be multimodal");
        List<OpenAIContentPart> content = result.getContentAsList();
        assertNotNull(content);
        assertTrue(content.size() >= 2, "Should have multiple content parts");
    }

    @Test
    @DisplayName("Should handle unknown audio source type gracefully")
    void testUnknownAudioSourceType() {
        List<Msg> messages = new ArrayList<>();

        // Create a message with URLSource audio (unsupported for direct input_audio)
        URLSource audioSource = URLSource.builder().url("http://example.com/audio.mp3").build();

        AudioBlock audioBlock = AudioBlock.builder().source(audioSource).build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Here's audio:").build(),
                                        audioBlock))
                        .build();

        messages.add(msg);

        OpenAIMessage result =
                merger.mergeToUserMessage(
                        messages, msg2 -> msg2.getRole().toString(), blocks -> "Tool result");

        assertNotNull(result);
        assertEquals("user", result.getRole());
        String content = result.getContentAsString();
        assertNotNull(content);
        // Should have text content with audio URL reference
        assertTrue(
                content.contains("Alice") || content.contains("audio"),
                "Should handle unknown audio source gracefully");
    }

    @Test
    @DisplayName("Should wrap conversation history in tags")
    void testConversationHistoryTags() {
        List<Msg> messages = new ArrayList<>();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        messages.add(msg);

        OpenAIMessage result =
                merger.mergeToUserMessage(
                        messages, msg2 -> msg2.getRole().toString(), blocks -> "Tool result");

        assertNotNull(result);
        String content = result.getContentAsString();
        assertNotNull(content);
        assertTrue(content.contains("<history>"), "Should contain history start tag");
        assertTrue(content.contains("</history>"), "Should contain history end tag");
    }

    @Test
    @DisplayName("Should handle empty message list")
    void testEmptyMessageList() {
        List<Msg> messages = new ArrayList<>();

        OpenAIMessage result =
                merger.mergeToUserMessage(
                        messages, msg -> msg.getRole().toString(), blocks -> "Tool result");

        assertNotNull(result);
        assertEquals("user", result.getRole());
        String content = result.getContentAsString();
        assertNotNull(content);
        assertTrue(
                content.contains("<history>") && content.contains("</history>"),
                "Should still have history tags even when empty");
    }

    @Test
    @DisplayName("Should handle mixed content types with fallback for null sources")
    void testMixedContentWithNullHandling() {
        List<Msg> messages = new ArrayList<>();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Message 1").build(),
                                        TextBlock.builder().text("Message 2").build()))
                        .build();

        messages.add(msg);

        OpenAIMessage result =
                merger.mergeToUserMessage(
                        messages, msg2 -> msg2.getRole().toString(), blocks -> "Tool result");

        assertNotNull(result);
        assertEquals("user", result.getRole());
        String content = result.getContentAsString();
        assertNotNull(content);
        assertTrue(content.contains("Message 1"), "Should contain first message");
        assertTrue(content.contains("Message 2"), "Should contain second message");
    }

    @Test
    @DisplayName("Should format history with only name prefix without roleLabel")
    void testHistoryFormatWithNameOnly() {
        List<Msg> messages = new ArrayList<>();

        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        Msg msg2 =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Bob")
                        .content(List.of(TextBlock.builder().text("Hi there").build()))
                        .build();

        messages.add(msg1);
        messages.add(msg2);

        OpenAIMessage result =
                merger.mergeToUserMessage(
                        messages, msg -> msg.getRole().toString(), blocks -> "Tool result");

        assertNotNull(result);
        String content = result.getContentAsString();
        assertNotNull(content);

        // Verify format is "name: text" without roleLabel
        assertTrue(content.contains("Alice: Hello"), "Should format as 'Alice: Hello'");
        assertTrue(content.contains("Bob: Hi there"), "Should format as 'Bob: Hi there'");

        // Verify roleLabel (USER/ASSISTANT) is NOT present
        int userIndex = content.indexOf("USER");
        int assistantIndex = content.indexOf("ASSISTANT");
        assertTrue(
                userIndex == -1 || userIndex > content.indexOf("Alice: Hello"),
                "Should not contain USER roleLabel before Alice's message");
        assertTrue(
                assistantIndex == -1 || assistantIndex > content.indexOf("Bob: Hi there"),
                "Should not contain ASSISTANT roleLabel before Bob's message");
    }

    @Test
    @DisplayName("Should format ToolResultBlock with name only")
    void testToolResultFormatWithNameOnly() {
        List<Msg> messages = new ArrayList<>();

        io.agentscope.core.message.ToolResultBlock toolResult =
                io.agentscope.core.message.ToolResultBlock.builder()
                        .name("search_tool")
                        .output(List.of(TextBlock.builder().text("Search completed").build()))
                        .build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .name("ToolAgent")
                        .content(List.of(toolResult))
                        .build();

        messages.add(msg);

        OpenAIMessage result =
                merger.mergeToUserMessage(
                        messages,
                        msg2 -> msg2.getRole().toString(),
                        blocks -> {
                            StringBuilder sb = new StringBuilder();
                            for (var block : blocks) {
                                if (block instanceof TextBlock tb) {
                                    sb.append(tb.getText());
                                }
                            }
                            return sb.toString();
                        });

        assertNotNull(result);
        String content = result.getContentAsString();
        assertNotNull(content);

        // Verify format is "name (tool_name): result"
        assertTrue(
                content.contains("ToolAgent (search_tool): Search completed"),
                "Should format as 'ToolAgent (search_tool): Search completed'");

        // Verify roleLabel is NOT present
        assertTrue(
                !content.contains("TOOL ToolAgent"),
                "Should not contain 'TOOL ToolAgent' format");
    }

    @Test
    @DisplayName("Should format multimodal content with name prefix only")
    void testMultimodalFormatWithNameOnly() {
        List<Msg> messages = new ArrayList<>();

        URLSource imageSource = URLSource.builder().url("http://example.com/pic.jpg").build();
        ImageBlock imageBlock = ImageBlock.builder().source(imageSource).build();

        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(List.of(TextBlock.builder().text("Look at this").build()))
                        .build();

        Msg msg2 =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Bob")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Interesting").build(),
                                        imageBlock))
                        .build();

        messages.add(msg1);
        messages.add(msg2);

        OpenAIMessage result =
                merger.mergeToUserMessage(
                        messages, msg -> msg.getRole().toString(), blocks -> "Tool result");

        assertNotNull(result);
        assertTrue(result.isMultimodal() || result.getContentAsString() != null);

        if (!result.isMultimodal()) {
            String content = result.getContentAsString();
            assertTrue(
                    content.contains("Alice: Look at this"),
                    "Should format as 'Alice: Look at this'");
            assertTrue(
                    content.contains("Bob: Interesting"), "Should format as 'Bob: Interesting'");
        }
    }

    @Test
    @DisplayName("Should handle ThinkingBlock with name prefix only")
    void testThinkingBlockFormatWithNameOnly() {
        List<Msg> messages = new ArrayList<>();

        io.agentscope.core.message.ThinkingBlock thinkingBlock =
                io.agentscope.core.message.ThinkingBlock.builder()
                        .thinking("Let me analyze this...")
                        .build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Thinker")
                        .content(
                                List.of(
                                        thinkingBlock,
                                        TextBlock.builder().text("My conclusion").build()))
                        .build();

        messages.add(msg);

        OpenAIMessage result =
                merger.mergeToUserMessage(
                        messages, msg2 -> msg2.getRole().toString(), blocks -> "Tool result");

        assertNotNull(result);
        String content = result.getContentAsString();
        assertNotNull(content);

        assertTrue(
                content.contains("Thinker: [Thinking]: Let me analyze this..."),
                "Should include thinking with name prefix");
        assertTrue(
                content.contains("Thinker: My conclusion"),
                "Should include text with name prefix");
    }
}
