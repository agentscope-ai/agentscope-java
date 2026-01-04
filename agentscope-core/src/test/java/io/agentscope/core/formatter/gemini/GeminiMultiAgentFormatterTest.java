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
package io.agentscope.core.formatter.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.Content;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GeminiMultiAgentFormatter.
 */
class GeminiMultiAgentFormatterTest {

    private final GeminiMultiAgentFormatter formatter = new GeminiMultiAgentFormatter();

    @Test
    void testFormatSystemMessage() {
        Msg systemMsg =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(List.of(TextBlock.builder().text("You are a helpful AI").build()))
                        .build();

        List<Content> contents = formatter.format(List.of(systemMsg));

        assertNotNull(contents);
        assertEquals(1, contents.size());

        // System message should be converted to user role for Gemini
        Content content = contents.get(0);
        assertEquals("user", content.role().get());
    }

    @Test
    void testFormatMultiAgentConversation() {
        Msg agent1 =
                Msg.builder()
                        .name("Agent1")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello from Agent1").build()))
                        .build();

        Msg agent2 =
                Msg.builder()
                        .name("Agent2")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Hello from Agent2").build()))
                        .build();

        List<Content> contents = formatter.format(List.of(agent1, agent2));

        assertNotNull(contents);
        // Should merge into single content with history tags
        assertTrue(contents.size() >= 1);

        // Check that history tags are present in the text
        Content firstContent = contents.get(0);
        assertTrue(firstContent.parts().isPresent());
        String text = firstContent.parts().get().get(0).text().orElse("");
        assertTrue(text.contains("<history>"));
        assertTrue(text.contains("</history>"));
        assertTrue(text.contains("Agent1"));
        assertTrue(text.contains("Agent2"));
    }

    @Test
    void testFormatEmptyMessages() {
        List<Content> contents = formatter.format(List.of());

        assertNotNull(contents);
        assertEquals(0, contents.size());
    }

    @Test
    void testFormatSingleUserMessage() {
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        List<Content> contents = formatter.format(List.of(userMsg));

        assertNotNull(contents);
        assertTrue(contents.size() >= 1);
    }
}
