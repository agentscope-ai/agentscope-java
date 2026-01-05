/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.ollama.dto.OllamaMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OllamaConversationMerger.
 */
@DisplayName("OllamaConversationMerger Unit Tests")
class OllamaConversationMergerTest {

    private OllamaConversationMerger merger;
    private static final String TEST_HISTORY_PROMPT = "Test history prompt";

    @BeforeEach
    void setUp() {
        merger = new OllamaConversationMerger(TEST_HISTORY_PROMPT);
    }

    @Test
    @DisplayName("Should create merger with history prompt")
    void testConstructor() {
        assertNotNull(merger);

        OllamaConversationMerger customMerger = new OllamaConversationMerger("Custom prompt");
        assertNotNull(customMerger);
    }

    @Test
    @DisplayName("Should merge single message correctly")
    void testMergeSingleMessage() {
        // Arrange
        Msg msg =
                Msg.builder()
                        .name("Alice")
                        .content(TextBlock.builder().text("Hello").build())
                        .build();
        List<Msg> msgs = Arrays.asList(msg);

        Function<Msg, String> nameExtractor = m -> m.getName() != null ? m.getName() : "Unknown";
        Function<List<ContentBlock>, String> toolResultConverter = blocks -> "Converted";
        String historyPrompt = "History:";

        // Act
        OllamaMessage merged =
                merger.mergeToMessage(msgs, nameExtractor, toolResultConverter, historyPrompt);

        // Assert
        assertNotNull(merged);
        assertEquals("user", merged.getRole());
        assertTrue(merged.getContent().contains("History:"));
        assertTrue(merged.getContent().contains("<history>"));
        assertTrue(merged.getContent().contains("Alice: Hello"));
        assertTrue(merged.getContent().contains("</history>"));
    }

    @Test
    @DisplayName("Should merge multiple messages correctly")
    void testMergeMultipleMessages() {
        // Arrange
        Msg msg1 =
                Msg.builder()
                        .name("Alice")
                        .content(TextBlock.builder().text("Hello").build())
                        .build();
        Msg msg2 =
                Msg.builder()
                        .name("Bob")
                        .content(TextBlock.builder().text("Hi there").build())
                        .build();
        List<Msg> msgs = Arrays.asList(msg1, msg2);

        Function<Msg, String> nameExtractor = m -> m.getName() != null ? m.getName() : "Unknown";
        Function<List<ContentBlock>, String> toolResultConverter = blocks -> "Converted";
        String historyPrompt = "History:";

        // Act
        OllamaMessage merged =
                merger.mergeToMessage(msgs, nameExtractor, toolResultConverter, historyPrompt);

        // Assert
        assertNotNull(merged);
        assertTrue(merged.getContent().contains("Alice: Hello"));
        assertTrue(merged.getContent().contains("Bob: Hi there"));
        assertTrue(merged.getContent().contains("<history>"));
        assertTrue(merged.getContent().contains("</history>"));
    }

    @Test
    @DisplayName("Should handle message without name")
    void testMergeMessageWithoutName() {
        // Arrange
        Msg msg = Msg.builder().content(TextBlock.builder().text("Hello").build()).build();
        List<Msg> msgs = Arrays.asList(msg);

        Function<Msg, String> nameExtractor = m -> m.getName() != null ? m.getName() : "Unknown";
        Function<List<ContentBlock>, String> toolResultConverter = blocks -> "Converted";
        String historyPrompt = "History:";

        // Act
        OllamaMessage merged =
                merger.mergeToMessage(msgs, nameExtractor, toolResultConverter, historyPrompt);

        // Assert
        assertNotNull(merged);
        assertTrue(merged.getContent().contains("Unknown: Hello"));
    }

    @Test
    @DisplayName("Should handle null history prompt")
    void testMergeWithNullHistoryPrompt() {
        // Arrange
        Msg msg =
                Msg.builder()
                        .name("Alice")
                        .content(TextBlock.builder().text("Hello").build())
                        .build();
        List<Msg> msgs = Arrays.asList(msg);

        Function<Msg, String> nameExtractor = m -> m.getName() != null ? m.getName() : "Unknown";
        Function<List<ContentBlock>, String> toolResultConverter = blocks -> "Converted";
        String historyPrompt = null;

        // Act
        OllamaMessage merged =
                merger.mergeToMessage(msgs, nameExtractor, toolResultConverter, historyPrompt);

        // Assert
        assertNotNull(merged);
        String content = merged.getContent();
        assertTrue(content.contains("<history>"));
        assertTrue(content.contains("Alice: Hello"));
        assertTrue(content.contains("</history>"));
        assertFalse(content.contains("History:")); // Should not contain the history prompt
    }

    @Test
    @DisplayName("Should handle empty history prompt")
    void testMergeWithEmptyHistoryPrompt() {
        // Arrange
        Msg msg =
                Msg.builder()
                        .name("Alice")
                        .content(TextBlock.builder().text("Hello").build())
                        .build();
        List<Msg> msgs = Arrays.asList(msg);

        Function<Msg, String> nameExtractor = m -> m.getName() != null ? m.getName() : "Unknown";
        Function<List<ContentBlock>, String> toolResultConverter = blocks -> "Converted";
        String historyPrompt = "";

        // Act
        OllamaMessage merged =
                merger.mergeToMessage(msgs, nameExtractor, toolResultConverter, historyPrompt);

        // Assert
        assertNotNull(merged);
        String content = merged.getContent();
        assertTrue(content.contains("<history>"));
        assertTrue(content.contains("Alice: Hello"));
        assertTrue(content.contains("</history>"));
        assertFalse(content.contains("History:")); // Should not contain the history prompt
    }

    @Test
    @DisplayName("Should handle tool result blocks")
    void testMergeWithToolResultBlocks() {
        // Arrange
        ToolResultBlock toolResult =
                new ToolResultBlock(
                        "call123",
                        "calculator",
                        List.of(TextBlock.builder().text("Result: 42").build()),
                        null);
        Msg msg = Msg.builder().name("Alice").content(toolResult).build();
        List<Msg> msgs = Arrays.asList(msg);

        Function<Msg, String> nameExtractor = m -> m.getName() != null ? m.getName() : "Unknown";
        Function<List<ContentBlock>, String> toolResultConverter = blocks -> "Converted";
        String historyPrompt = "History:";

        // Act
        OllamaMessage merged =
                merger.mergeToMessage(msgs, nameExtractor, toolResultConverter, historyPrompt);

        // Assert
        assertNotNull(merged);
        String content = merged.getContent();
        assertTrue(content.contains("Alice"));
        assertTrue(content.contains("calculator"));
        assertTrue(content.contains("Result: 42") || content.contains("TextBlock"));
        assertTrue(content.contains("<history>"));
        assertTrue(content.contains("</history>"));
    }

    @Test
    @DisplayName("Should handle mixed content blocks")
    void testMergeWithMixedContentBlocks() {
        // Arrange
        ToolResultBlock toolResult =
                new ToolResultBlock(
                        "call123",
                        "calculator",
                        List.of(TextBlock.builder().text("Result: 42").build()),
                        null);
        TextBlock textBlock = TextBlock.builder().text("Regular text").build();
        Msg msg = Msg.builder().name("Alice").content(Arrays.asList(textBlock, toolResult)).build();
        List<Msg> msgs = Arrays.asList(msg);

        Function<Msg, String> nameExtractor = m -> m.getName() != null ? m.getName() : "Unknown";
        Function<List<ContentBlock>, String> toolResultConverter = blocks -> "Converted";
        String historyPrompt = "History:";

        // Act
        OllamaMessage merged =
                merger.mergeToMessage(msgs, nameExtractor, toolResultConverter, historyPrompt);

        // Assert
        assertNotNull(merged);
        String content = merged.getContent();
        assertTrue(content.contains("Alice: Regular text"));
        assertTrue(
                content.contains("Alice (calculator)")
                        && (content.contains("Result: 42") || content.contains("TextBlock")));
        assertTrue(content.contains("<history>"));
        assertTrue(content.contains("</history>"));
    }

    @Test
    @DisplayName("Should set role to user for merged message")
    void testMergedMessageRole() {
        // Arrange
        Msg msg =
                Msg.builder()
                        .name("Alice")
                        .content(TextBlock.builder().text("Hello").build())
                        .build();
        List<Msg> msgs = Arrays.asList(msg);

        Function<Msg, String> nameExtractor = m -> m.getName() != null ? m.getName() : "Unknown";
        Function<List<ContentBlock>, String> toolResultConverter = blocks -> "Converted";
        String historyPrompt = "History:";

        // Act
        OllamaMessage merged =
                merger.mergeToMessage(msgs, nameExtractor, toolResultConverter, historyPrompt);

        // Assert
        assertEquals("user", merged.getRole());
    }
}
