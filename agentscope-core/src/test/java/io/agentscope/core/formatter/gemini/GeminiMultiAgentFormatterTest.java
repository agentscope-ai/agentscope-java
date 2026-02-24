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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.gemini.dto.GeminiContent;
import io.agentscope.core.formatter.gemini.dto.GeminiGenerationConfig;
import io.agentscope.core.formatter.gemini.dto.GeminiRequest;
import io.agentscope.core.formatter.gemini.dto.GeminiResponse;
import io.agentscope.core.formatter.gemini.dto.GeminiResponse.GeminiCandidate;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

        List<GeminiContent> contents = formatter.format(List.of(systemMsg));

        assertNotNull(contents);
        // System message is now extracted to systemInstruction field, not included in contents
        assertEquals(0, contents.size());

        GeminiRequest request = new GeminiRequest();
        formatter.applySystemInstruction(request, List.of(systemMsg));
        assertNotNull(request.getSystemInstruction());
        assertEquals(
                "You are a helpful AI", request.getSystemInstruction().getParts().get(0).getText());
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

        List<GeminiContent> contents = formatter.format(List.of(agent1, agent2));

        assertNotNull(contents);
        // Should merge into single content with history tags
        assertTrue(contents.size() >= 1);

        // Check that history tags are present in the text
        GeminiContent firstContent = contents.get(0);
        assertNotNull(firstContent.getParts());
        String text = firstContent.getParts().get(0).getText();
        if (text == null) text = "";

        assertTrue(text.contains("<history>"));
        assertTrue(text.contains("</history>"));
        assertTrue(text.contains("Agent1"));
        assertTrue(text.contains("Agent2"));
    }

    @Test
    void testFormatEmptyMessages() {
        List<GeminiContent> contents = formatter.format(List.of());

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

        List<GeminiContent> contents = formatter.format(List.of(userMsg));

        assertNotNull(contents);
        assertTrue(contents.size() >= 1);
    }

    @Test
    void testFormatWithNullMessages() {
        List<GeminiContent> contents = formatter.format(null);
        assertNotNull(contents);
        assertEquals(0, contents.size());
    }

    @Test
    void testCustomConversationHistoryPrompt() {
        GeminiMultiAgentFormatter customFormatter =
                new GeminiMultiAgentFormatter("CUSTOM_PROMPT\n");

        Msg userMsg =
                Msg.builder()
                        .name("Alice")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hi").build()))
                        .build();

        List<GeminiContent> contents = customFormatter.format(List.of(userMsg));

        assertEquals(1, contents.size());
        String text = contents.get(0).getParts().get(0).getText();
        assertTrue(text.startsWith("CUSTOM_PROMPT\n<history>Alice: Hi"));
        assertTrue(text.endsWith("</history>"));
    }

    @Test
    void testApplyOptionsDelegatesToChatFormatter() {
        GeminiRequest request = new GeminiRequest();
        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).maxTokens(256).build();

        formatter.applyOptions(request, options, null);

        GeminiGenerationConfig config = request.getGenerationConfig();
        assertNotNull(config);
        assertEquals(0.7, config.getTemperature(), 0.001);
        assertEquals(256, config.getMaxOutputTokens());
    }

    @Test
    void testApplyToolsAndToolChoiceDelegation() {
        GeminiRequest request = new GeminiRequest();
        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("search tool")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("query", Map.of("type", "string"))))
                        .build();

        formatter.applyTools(request, List.of(schema));
        formatter.applyToolChoice(request, new ToolChoice.Specific("search"));

        assertNotNull(request.getTools());
        assertEquals(1, request.getTools().size());
        assertNotNull(request.getToolConfig());
        assertEquals(
                List.of("search"),
                request.getToolConfig().getFunctionCallingConfig().getAllowedFunctionNames());
    }

    @Test
    void testParseResponseDelegatesToResponseParser() {
        GeminiResponse response = new GeminiResponse();
        GeminiCandidate candidate = new GeminiCandidate();
        io.agentscope.core.formatter.gemini.dto.GeminiPart part =
                new io.agentscope.core.formatter.gemini.dto.GeminiPart();
        part.setText("ok");
        candidate.setContent(new GeminiContent("model", List.of(part)));
        response.setCandidates(List.of(candidate));

        ChatResponse parsed = formatter.parseResponse(response, Instant.now());

        assertNotNull(parsed);
        assertEquals(1, parsed.getContent().size());
        assertEquals("ok", ((TextBlock) parsed.getContent().get(0)).getText());
    }

    @Test
    void testApplySystemInstructionIsStateless() {
        Msg system1 =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(List.of(TextBlock.builder().text("Sys1").build()))
                        .build();
        Msg system2 =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(List.of(TextBlock.builder().text("Sys2").build()))
                        .build();
        Msg user =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("User message").build()))
                        .build();

        GeminiRequest request1 = new GeminiRequest();
        formatter.applySystemInstruction(request1, List.of(system1));
        assertNotNull(request1.getSystemInstruction());
        assertEquals("Sys1", request1.getSystemInstruction().getParts().get(0).getText());

        GeminiRequest request2 = new GeminiRequest();
        formatter.applySystemInstruction(request2, List.of(system2));
        assertNotNull(request2.getSystemInstruction());
        assertEquals("Sys2", request2.getSystemInstruction().getParts().get(0).getText());

        // Ensure no leakage between calls
        assertEquals("Sys1", request1.getSystemInstruction().getParts().get(0).getText());

        GeminiRequest requestWithoutSystem = new GeminiRequest();
        formatter.applySystemInstruction(requestWithoutSystem, List.of(user));
        assertNull(requestWithoutSystem.getSystemInstruction());
    }
}
