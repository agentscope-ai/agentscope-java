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

import io.agentscope.core.formatter.gemini.dto.GeminiContent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("GeminiConversationMerger Unit Tests")
class GeminiConversationMergerTest {

    private static final String HISTORY_PROMPT =
            "# Conversation History\nThe content between <history></history> tags contains your"
                    + " conversation history\n";

    private final GeminiConversationMerger merger = new GeminiConversationMerger(HISTORY_PROMPT);

    @Test
    @DisplayName("Should merge text conversation with history tags and skip thinking block")
    void testMergeTextConversation() {
        Msg user =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();
        Msg assistantThinking =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(ThinkingBlock.builder().thinking("internal").build()))
                        .build();
        Msg toolResult =
                Msg.builder()
                        .name("system")
                        .role(MsgRole.SYSTEM)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call-1")
                                                .name("search")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("ok")
                                                                        .build()))
                                                .build()))
                        .build();

        GeminiContent merged =
                merger.mergeToContent(
                        List.of(user, assistantThinking, toolResult),
                        m -> m.getName() != null ? m.getName() : m.getRole().name().toLowerCase(),
                        blocks -> "tool output",
                        HISTORY_PROMPT);

        assertEquals("user", merged.getRole());
        assertEquals(1, merged.getParts().size());
        String text = merged.getParts().get(0).getText();
        assertTrue(text.startsWith(HISTORY_PROMPT + "<history>"));
        assertTrue(text.contains("user: Hello"));
        assertTrue(text.contains("Tool: search\ntool output"));
        assertTrue(text.endsWith("</history>"));
        assertTrue(!text.contains("internal"));
    }

    @Test
    @DisplayName("Should insert history tags when first and last parts are media")
    void testMergeMediaOnlyConversation() {
        String base64 = Base64.getEncoder().encodeToString("img".getBytes());
        ImageBlock image =
                ImageBlock.builder()
                        .source(Base64Source.builder().mediaType("image/png").data(base64).build())
                        .build();

        Msg mediaMsg =
                Msg.builder().name("user").role(MsgRole.USER).content(List.of(image)).build();

        GeminiContent merged =
                merger.mergeToContent(
                        List.of(mediaMsg),
                        m -> m.getName() != null ? m.getName() : "user",
                        blocks -> "",
                        "");

        assertEquals("user", merged.getRole());
        assertEquals(3, merged.getParts().size());
        assertEquals("<history>", merged.getParts().get(0).getText());
        assertNotNull(merged.getParts().get(1).getInlineData());
        assertEquals("</history>", merged.getParts().get(2).getText());
    }

    @Test
    @DisplayName("Should append closing tag as separate part when last part is media")
    void testMergeTextThenMediaConversation() {
        String base64 = Base64.getEncoder().encodeToString("img".getBytes());
        ImageBlock image =
                ImageBlock.builder()
                        .source(Base64Source.builder().mediaType("image/png").data(base64).build())
                        .build();

        Msg msg =
                Msg.builder()
                        .name("agent")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Look").build(), image))
                        .build();

        GeminiContent merged =
                merger.mergeToContent(List.of(msg), m -> "agent", blocks -> "", "# Prompt\n");

        assertEquals(3, merged.getParts().size());
        assertTrue(merged.getParts().get(0).getText().startsWith("# Prompt\n<history>agent: Look"));
        assertNotNull(merged.getParts().get(1).getInlineData());
        assertEquals("</history>", merged.getParts().get(2).getText());
    }

    @Test
    @DisplayName("Should return empty parts for empty conversation input")
    void testMergeEmptyConversation() {
        GeminiContent merged =
                merger.mergeToContent(
                        List.of(),
                        m -> m.getName() != null ? m.getName() : "unknown",
                        blocks -> "",
                        HISTORY_PROMPT);

        assertEquals("user", merged.getRole());
        assertNotNull(merged.getParts());
        assertTrue(merged.getParts().isEmpty());
    }
}
