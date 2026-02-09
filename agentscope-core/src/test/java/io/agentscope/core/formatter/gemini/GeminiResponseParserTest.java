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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.gemini.dto.GeminiContent;
import io.agentscope.core.formatter.gemini.dto.GeminiPart;
import io.agentscope.core.formatter.gemini.dto.GeminiPart.GeminiFunctionCall;
import io.agentscope.core.formatter.gemini.dto.GeminiResponse;
import io.agentscope.core.formatter.gemini.dto.GeminiResponse.GeminiCandidate;
import io.agentscope.core.formatter.gemini.dto.GeminiResponse.GeminiUsageMetadata;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GeminiResponseParser.
 */
class GeminiResponseParserTest {

    private final GeminiResponseParser parser = new GeminiResponseParser();
    private final Instant startTime = Instant.now();

    @Test
    void testParseSimpleTextResponse() {
        // Build response
        GeminiPart textPart = new GeminiPart();
        textPart.setText("Hello, how can I help you?");

        GeminiContent content = new GeminiContent("model", List.of(textPart));

        GeminiCandidate candidate = new GeminiCandidate();
        candidate.setContent(content);

        GeminiResponse response = new GeminiResponse();
        // responseId not strictly in simple DTO but parsed toChatResponse if needed,
        // current Parser implementation doesn't seem to set ID from response root (JSON
        // root usually has no ID in Gemini API??)
        // Wait, GeminiResponse DTO has no ID field at root?
        // Let's check GeminiResponse DTO later.
        response.setCandidates(List.of(candidate));

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify
        assertNotNull(chatResponse);
        // assertEquals("response-123", chatResponse.getId()); // ID might be missing or
        // different
        assertEquals(1, chatResponse.getContent().size());

        ContentBlock block = chatResponse.getContent().get(0);
        assertInstanceOf(TextBlock.class, block);
        assertEquals("Hello, how can I help you?", ((TextBlock) block).getText());
    }

    @Test
    void testParseThinkingResponse() {
        // Build response with thinking content (thought=true)
        GeminiPart thinkingPart = new GeminiPart();
        thinkingPart.setText("Let me think about this problem...");
        thinkingPart.setThought(true);

        GeminiPart textPart = new GeminiPart();
        textPart.setText("The answer is 42.");

        GeminiContent content = new GeminiContent("model", List.of(thinkingPart, textPart));

        GeminiCandidate candidate = new GeminiCandidate();
        candidate.setContent(content);

        GeminiResponse response = new GeminiResponse();
        response.setCandidates(List.of(candidate));

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify
        assertNotNull(chatResponse);
        assertEquals(2, chatResponse.getContent().size());

        // First should be ThinkingBlock
        ContentBlock block1 = chatResponse.getContent().get(0);
        assertInstanceOf(ThinkingBlock.class, block1);
        assertEquals("Let me think about this problem...", ((ThinkingBlock) block1).getThinking());

        // Second should be TextBlock
        ContentBlock block2 = chatResponse.getContent().get(1);
        assertInstanceOf(TextBlock.class, block2);
        assertEquals("The answer is 42.", ((TextBlock) block2).getText());
    }

    @Test
    void testParseToolCallResponse() {
        // Build response with function call
        Map<String, Object> args = new HashMap<>();
        args.put("city", "Tokyo");

        GeminiFunctionCall functionCall = new GeminiFunctionCall("call-123", "get_weather", args);

        GeminiPart functionCallPart = new GeminiPart();
        functionCallPart.setFunctionCall(functionCall);

        GeminiContent content = new GeminiContent("model", List.of(functionCallPart));

        GeminiCandidate candidate = new GeminiCandidate();
        candidate.setContent(content);

        GeminiResponse response = new GeminiResponse();
        response.setCandidates(List.of(candidate));

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify
        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());

        ContentBlock block = chatResponse.getContent().get(0);
        assertInstanceOf(ToolUseBlock.class, block);

        ToolUseBlock toolUse = (ToolUseBlock) block;
        assertEquals("call-123", toolUse.getId());
        assertEquals("get_weather", toolUse.getName());
        assertTrue(toolUse.getInput().containsKey("city"));
        assertEquals("Tokyo", toolUse.getInput().get("city"));
    }

    @Test
    void testParseMixedContentResponse() {
        // Build response with thinking, text, and tool call
        GeminiPart thinkingPart = new GeminiPart();
        thinkingPart.setText("I need to check the weather first.");
        thinkingPart.setThought(true);

        Map<String, Object> args = new HashMap<>();
        args.put("city", "Tokyo");
        GeminiFunctionCall functionCall = new GeminiFunctionCall("call-456", "get_weather", args);

        GeminiPart functionCallPart = new GeminiPart();
        functionCallPart.setFunctionCall(functionCall);

        GeminiPart textPart = new GeminiPart();
        textPart.setText("Let me check that for you.");

        GeminiContent content =
                new GeminiContent("model", List.of(thinkingPart, textPart, functionCallPart));

        GeminiCandidate candidate = new GeminiCandidate();
        candidate.setContent(content);

        GeminiResponse response = new GeminiResponse();
        response.setCandidates(List.of(candidate));

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify
        assertNotNull(chatResponse);
        assertEquals(3, chatResponse.getContent().size());

        // First: ThinkingBlock
        assertInstanceOf(ThinkingBlock.class, chatResponse.getContent().get(0));
        assertEquals(
                "I need to check the weather first.",
                ((ThinkingBlock) chatResponse.getContent().get(0)).getThinking());

        // Second: TextBlock
        assertInstanceOf(TextBlock.class, chatResponse.getContent().get(1));
        assertEquals(
                "Let me check that for you.",
                ((TextBlock) chatResponse.getContent().get(1)).getText());

        // Third: ToolUseBlock
        assertInstanceOf(ToolUseBlock.class, chatResponse.getContent().get(2));
        ToolUseBlock toolUse = (ToolUseBlock) chatResponse.getContent().get(2);
        assertEquals("get_weather", toolUse.getName());
    }

    @Test
    void testParseUsageMetadata() {
        // Build response with usage metadata
        GeminiPart textPart = new GeminiPart();
        textPart.setText("Response text");

        GeminiContent content = new GeminiContent("model", List.of(textPart));

        GeminiCandidate candidate = new GeminiCandidate();
        candidate.setContent(content);

        GeminiUsageMetadata usageMetadata = new GeminiUsageMetadata();
        usageMetadata.setPromptTokenCount(100);
        usageMetadata.setCandidatesTokenCount(60);
        usageMetadata.setTotalTokenCount(160);

        GeminiResponse response = new GeminiResponse();
        response.setCandidates(List.of(candidate));
        response.setUsageMetadata(usageMetadata);

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify usage
        assertNotNull(chatResponse.getUsage());
        ChatUsage usage = chatResponse.getUsage();

        // Input tokens = promptTokenCount
        assertEquals(100, usage.getInputTokens());

        // Output tokens = candidatesTokenCount (DTO doesn't seem to have
        // thoughtsTokenCount yet)
        assertEquals(60, usage.getOutputTokens());

        // Time should be > 0
        assertTrue(usage.getTime() >= 0);
    }

    @Test
    void testParseUsageMetadataWithReasoning() {
        // Build response with usage metadata including reasoning
        GeminiPart textPart = new GeminiPart();
        textPart.setText("Response text");

        GeminiContent content = new GeminiContent("model", List.of(textPart));

        GeminiCandidate candidate = new GeminiCandidate();
        candidate.setContent(content);

        GeminiUsageMetadata usageMetadata = new GeminiUsageMetadata();
        usageMetadata.setPromptTokenCount(100);
        usageMetadata.setCandidatesTokenCount(60);
        usageMetadata.setTotalTokenCount(160);

        // Add candidatesTokensDetails with thought tokens
        Map<String, Object> details = new HashMap<>();
        Map<String, Object> modalityCount = new HashMap<>();
        modalityCount.put("thought", 20);
        modalityCount.put("text", 40);
        details.put("modalityTokenCount", modalityCount);

        usageMetadata.setCandidatesTokensDetails(details);

        GeminiResponse response = new GeminiResponse();
        response.setCandidates(List.of(candidate));
        response.setUsageMetadata(usageMetadata);

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify usage
        assertNotNull(chatResponse.getUsage());
        ChatUsage usage = chatResponse.getUsage();

        assertEquals(100, usage.getInputTokens());
        assertEquals(60, usage.getOutputTokens());
        assertEquals(20, usage.getReasoningTokens());
    }

    @Test
    void testParseEmptyResponse() {
        // Build empty response (no candidates)
        GeminiResponse response = new GeminiResponse();
        response.setCandidates(new ArrayList<>());

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify - should now include an explanatory TextBlock instead of being empty
        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());
        assertTrue(chatResponse.getContent().get(0) instanceof TextBlock);
        String text = ((TextBlock) chatResponse.getContent().get(0)).getText();
        assertTrue(
                text.contains("Gemini returned no candidates"),
                "Error message should explain no candidates were returned");
    }

    @Test
    void testParseResponseWithoutId() {
        // Build response without responseId
        GeminiPart textPart = new GeminiPart();
        textPart.setText("Hello");

        GeminiContent content = new GeminiContent("model", List.of(textPart));

        GeminiCandidate candidate = new GeminiCandidate();
        candidate.setContent(content);

        GeminiResponse response = new GeminiResponse();
        response.setCandidates(List.of(candidate));

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify - should handle null ID gracefully
        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());
    }

    @Test
    void testParseResponseWithId() {
        // Build response with explicit responseId
        GeminiPart textPart = new GeminiPart();
        textPart.setText("Hello");

        GeminiContent content = new GeminiContent("model", List.of(textPart));

        GeminiCandidate candidate = new GeminiCandidate();
        candidate.setContent(content);

        GeminiResponse response = new GeminiResponse();
        response.setResponseId("req-12345");
        response.setCandidates(List.of(candidate));

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify
        assertNotNull(chatResponse);
        assertEquals("req-12345", chatResponse.getId());
    }

    @Test
    void testParseToolCallWithoutId() {
        // Build function call without explicit ID
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test");

        GeminiFunctionCall functionCall = new GeminiFunctionCall();
        functionCall.setName("search");
        functionCall.setArgs(args);

        GeminiPart functionCallPart = new GeminiPart();
        functionCallPart.setFunctionCall(functionCall);

        GeminiContent content = new GeminiContent("model", List.of(functionCallPart));

        GeminiCandidate candidate = new GeminiCandidate();
        candidate.setContent(content);

        GeminiResponse response = new GeminiResponse();
        response.setCandidates(List.of(candidate));

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify - should generate ID
        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());

        ToolUseBlock toolUse = (ToolUseBlock) chatResponse.getContent().get(0);
        assertNotNull(toolUse.getId());
        assertTrue(toolUse.getId().startsWith("tool_call_"));
        assertEquals("search", toolUse.getName());
    }

    @Test
    void testParseThinkingResponseWithSignature() {
        // Build response with thinking content and signature
        GeminiPart thinkingPart = new GeminiPart();
        thinkingPart.setText("Let me think about this problem...");
        thinkingPart.setThought(true);
        thinkingPart.setSignature("sig-thought-123");

        GeminiPart textPart = new GeminiPart();
        textPart.setText("The answer is 42.");

        GeminiContent content = new GeminiContent("model", List.of(thinkingPart, textPart));

        GeminiCandidate candidate = new GeminiCandidate();
        candidate.setContent(content);

        GeminiResponse response = new GeminiResponse();
        response.setCandidates(List.of(candidate));

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify
        assertNotNull(chatResponse);
        assertEquals(2, chatResponse.getContent().size());

        // First should be ThinkingBlock
        ContentBlock block1 = chatResponse.getContent().get(0);
        assertInstanceOf(ThinkingBlock.class, block1);
        ThinkingBlock thinkingBlock = (ThinkingBlock) block1;
        assertEquals("Let me think about this problem...", thinkingBlock.getThinking());
        assertEquals(
                "sig-thought-123",
                thinkingBlock.getMetadata().get(GeminiResponseParser.METADATA_THOUGHT_SIGNATURE));

        // Second should be TextBlock
        ContentBlock block2 = chatResponse.getContent().get(1);
        assertInstanceOf(TextBlock.class, block2);
        assertEquals("The answer is 42.", ((TextBlock) block2).getText());
    }
}
