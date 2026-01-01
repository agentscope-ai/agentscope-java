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
package io.agentscope.core.formatter.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for AnthropicConversationMerger. */
class AnthropicConversationMergerTest extends AnthropicFormatterTestBase {

    @Test
    void testMergeConversationWithTextOnly() {
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

        List<Object> result =
                AnthropicConversationMerger.mergeConversation(
                        List.of(msg1, msg2), "# Test Prompt\n");

        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof String);

        String merged = (String) result.get(0);
        assertTrue(merged.contains("# Test Prompt\n<history>"));
        assertTrue(merged.contains("Alice: Hello"));
        assertTrue(merged.contains("Bob: Hi there"));
        assertTrue(merged.contains("</history>"));
    }

    @Test
    void testMergeConversationWithImage() {
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(URLSource.builder().url("https://example.com/image.png").build())
                        .build();

        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("User")
                        .content(List.of(TextBlock.builder().text("What is this?").build()))
                        .build();

        Msg msg2 =
                Msg.builder().role(MsgRole.USER).name("User").content(List.of(imageBlock)).build();

        Msg msg3 =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Assistant")
                        .content(List.of(TextBlock.builder().text("It's an image").build()))
                        .build();

        List<Object> result =
                AnthropicConversationMerger.mergeConversation(
                        List.of(msg1, msg2, msg3), "# Prompt\n");

        assertTrue(result.size() >= 2);
        assertTrue(result.stream().anyMatch(obj -> obj instanceof String));
        assertTrue(result.stream().anyMatch(obj -> obj instanceof ImageBlock));
    }

    @Test
    void testMergeConversationEmptyPrompt() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("User")
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        List<Object> result = AnthropicConversationMerger.mergeConversation(List.of(msg), "");

        assertEquals(1, result.size());
        String merged = (String) result.get(0);
        assertTrue(merged.startsWith("<history>"));
        assertTrue(merged.endsWith("</history>"));
        assertTrue(merged.contains("User: Hello"));
    }

    @Test
    void testMergeConversationEmptyMessages() {
        List<Object> result =
                AnthropicConversationMerger.mergeConversation(List.of(), "# Prompt\n");

        assertEquals(0, result.size());
    }

    @Test
    void testMergeConversationMultipleTextBlocks() {
        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(
                                List.of(
                                        TextBlock.builder().text("First message").build(),
                                        TextBlock.builder().text("Second message").build()))
                        .build();

        List<Object> result = AnthropicConversationMerger.mergeConversation(List.of(msg1), "");

        assertEquals(1, result.size());
        String merged = (String) result.get(0);
        assertTrue(merged.contains("First message"));
        assertTrue(merged.contains("Second message"));
    }

    @Test
    void testMergeConversationWithRoleNames() {
        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("UserAgent")
                        .content(List.of(TextBlock.builder().text("Question").build()))
                        .build();

        Msg msg2 =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("AIAgent")
                        .content(List.of(TextBlock.builder().text("Answer").build()))
                        .build();

        List<Object> result =
                AnthropicConversationMerger.mergeConversation(List.of(msg1, msg2), "");

        String merged = (String) result.get(0);
        assertTrue(merged.contains("UserAgent: Question"));
        assertTrue(merged.contains("AIAgent: Answer"));
    }

    @Test
    void testMergeConversationWithNullName() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name(null)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        List<Object> result = AnthropicConversationMerger.mergeConversation(List.of(msg), "");

        assertEquals(1, result.size());
        String merged = (String) result.get(0);
        // Should use role name when name is null
        assertTrue(merged.contains("USER: Hello"));
    }

    @Test
    void testMergeConversationImageInMiddle() {
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(URLSource.builder().url("https://example.com/image.png").build())
                        .build();

        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("User")
                        .content(List.of(TextBlock.builder().text("Before").build()))
                        .build();

        Msg msg2 =
                Msg.builder().role(MsgRole.USER).name("User").content(List.of(imageBlock)).build();

        Msg msg3 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("User")
                        .content(List.of(TextBlock.builder().text("After").build()))
                        .build();

        List<Object> result =
                AnthropicConversationMerger.mergeConversation(List.of(msg1, msg2, msg3), "");

        // Should have: text before image, image, text after image
        assertTrue(result.size() >= 2);
        boolean hasString = result.stream().anyMatch(obj -> obj instanceof String);
        boolean hasImage = result.stream().anyMatch(obj -> obj instanceof ImageBlock);
        assertTrue(hasString);
        assertTrue(hasImage);
    }

    @Test
    void testMergeConversationMultipleImages() {
        ImageBlock image1 =
                ImageBlock.builder()
                        .source(URLSource.builder().url("https://example.com/image1.png").build())
                        .build();

        ImageBlock image2 =
                ImageBlock.builder()
                        .source(URLSource.builder().url("https://example.com/image2.png").build())
                        .build();

        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("User")
                        .content(List.of(TextBlock.builder().text("First text").build()))
                        .build();

        Msg msg2 = Msg.builder().role(MsgRole.USER).name("User").content(List.of(image1)).build();

        Msg msg3 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("User")
                        .content(List.of(TextBlock.builder().text("Second text").build()))
                        .build();

        Msg msg4 = Msg.builder().role(MsgRole.USER).name("User").content(List.of(image2)).build();

        List<Object> result =
                AnthropicConversationMerger.mergeConversation(List.of(msg1, msg2, msg3, msg4), "");

        // Should have text and images interleaved
        assertTrue(result.size() >= 3);
        long imageCount = result.stream().filter(obj -> obj instanceof ImageBlock).count();
        assertEquals(2, imageCount);
    }

    @Test
    void testMergeConversationWithLongPrompt() {
        String longPrompt =
                "# Detailed Instructions\n"
                        + "This is a very long prompt with multiple lines.\n"
                        + "It contains various instructions for the conversation.\n";

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("User")
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        List<Object> result =
                AnthropicConversationMerger.mergeConversation(List.of(msg), longPrompt);

        String merged = (String) result.get(0);
        assertTrue(merged.startsWith(longPrompt + "<history>"));
        assertTrue(merged.contains("User: Hello"));
        assertTrue(merged.endsWith("</history>"));
    }
}
