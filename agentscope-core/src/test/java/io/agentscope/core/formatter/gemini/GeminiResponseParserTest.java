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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.time.Instant;
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
        Part textPart = Part.builder().text("Hello, how can I help you?").build();

        Content content = Content.builder().role("model").parts(List.of(textPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-123")
                        .candidates(List.of(candidate))
                        .build();

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify
        assertNotNull(chatResponse);
        assertEquals("response-123", chatResponse.getId());
        assertEquals(1, chatResponse.getContent().size());

        ContentBlock block = chatResponse.getContent().get(0);
        assertInstanceOf(TextBlock.class, block);
        assertEquals("Hello, how can I help you?", ((TextBlock) block).getText());
    }

    @Test
    void testParseThinkingResponse() {
        // Build response with thinking content (thought=true)
        Part thinkingPart =
                Part.builder().text("Let me think about this problem...").thought(true).build();

        Part textPart = Part.builder().text("The answer is 42.").build();

        Content content =
                Content.builder().role("model").parts(List.of(thinkingPart, textPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-456")
                        .candidates(List.of(candidate))
                        .build();

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

        FunctionCall functionCall =
                FunctionCall.builder().id("call-123").name("get_weather").args(args).build();

        Part functionCallPart = Part.builder().functionCall(functionCall).build();

        Content content = Content.builder().role("model").parts(List.of(functionCallPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-789")
                        .candidates(List.of(candidate))
                        .build();

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
        assertEquals("Tokyo", toolUse.getInput().get("city"));
    }

    @Test
    void testParseMixedContentResponse() {
        // Build response with thinking, text, and tool call
        Part thinkingPart =
                Part.builder().text("I need to check the weather first.").thought(true).build();

        Map<String, Object> args = new HashMap<>();
        args.put("city", "Tokyo");

        FunctionCall functionCall =
                FunctionCall.builder().id("call-456").name("get_weather").args(args).build();

        Part functionCallPart = Part.builder().functionCall(functionCall).build();

        Part textPart = Part.builder().text("Let me check that for you.").build();

        Content content =
                Content.builder()
                        .role("model")
                        .parts(List.of(thinkingPart, textPart, functionCallPart))
                        .build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-mixed")
                        .candidates(List.of(candidate))
                        .build();

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
        Part textPart = Part.builder().text("Response text").build();

        Content content = Content.builder().role("model").parts(List.of(textPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponseUsageMetadata usageMetadata =
                GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount(100)
                        .candidatesTokenCount(60) // Includes thinking
                        .thoughtsTokenCount(10) // Thinking tokens
                        .totalTokenCount(160)
                        .build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-usage")
                        .candidates(List.of(candidate))
                        .usageMetadata(usageMetadata)
                        .build();

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify usage
        assertNotNull(chatResponse.getUsage());
        ChatUsage usage = chatResponse.getUsage();

        // Input tokens = promptTokenCount
        assertEquals(100, usage.getInputTokens());

        // Output tokens = candidatesTokenCount - thoughtsTokenCount
        assertEquals(50, usage.getOutputTokens());

        // Time should be > 0
        assertTrue(usage.getTime() >= 0);
    }

    @Test
    void testParseEmptyResponse() {
        // Build empty response (no candidates)
        GenerateContentResponse response =
                GenerateContentResponse.builder().responseId("response-empty").build();

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify
        assertNotNull(chatResponse);
        assertEquals("response-empty", chatResponse.getId());
        assertEquals(0, chatResponse.getContent().size());
    }

    @Test
    void testParseResponseWithoutId() {
        // Build response without responseId
        Part textPart = Part.builder().text("Hello").build();

        Content content = Content.builder().role("model").parts(List.of(textPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder().candidates(List.of(candidate)).build();

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify - should handle null ID gracefully
        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());
    }

    @Test
    void testParseToolCallWithoutId() {
        // Build function call without explicit ID
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test");

        FunctionCall functionCall = FunctionCall.builder().name("search").args(args).build();

        Part functionCallPart = Part.builder().functionCall(functionCall).build();

        Content content = Content.builder().role("model").parts(List.of(functionCallPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-no-tool-id")
                        .candidates(List.of(candidate))
                        .build();

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
    void testParseToolCallWithThoughtSignature() {
        // Build function call with thought signature (for Gemini 3 Pro)
        Map<String, Object> args = new HashMap<>();
        args.put("city", "Tokyo");

        FunctionCall functionCall =
                FunctionCall.builder().id("call-with-sig").name("get_weather").args(args).build();

        byte[] thoughtSignature = "test-signature-bytes".getBytes();
        Part functionCallPart =
                Part.builder()
                        .functionCall(functionCall)
                        .thoughtSignature(thoughtSignature)
                        .build();

        Content content = Content.builder().role("model").parts(List.of(functionCallPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-with-sig")
                        .candidates(List.of(candidate))
                        .build();

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify
        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());

        ToolUseBlock toolUse = (ToolUseBlock) chatResponse.getContent().get(0);
        assertEquals("call-with-sig", toolUse.getId());
        assertEquals("get_weather", toolUse.getName());

        // Verify thought signature is stored in metadata
        assertNotNull(toolUse.getMetadata());
        assertTrue(toolUse.getMetadata().containsKey(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));
        byte[] extractedSig =
                (byte[]) toolUse.getMetadata().get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE);
        assertArrayEquals(thoughtSignature, extractedSig);
    }

    @Test
    void testParseToolCallWithoutThoughtSignature() {
        // Build function call without thought signature
        Map<String, Object> args = new HashMap<>();
        args.put("city", "London");

        FunctionCall functionCall =
                FunctionCall.builder().id("call-no-sig").name("get_weather").args(args).build();

        Part functionCallPart = Part.builder().functionCall(functionCall).build();

        Content content = Content.builder().role("model").parts(List.of(functionCallPart)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-no-sig")
                        .candidates(List.of(candidate))
                        .build();

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify - metadata should be empty (no thoughtSignature)
        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());

        ToolUseBlock toolUse = (ToolUseBlock) chatResponse.getContent().get(0);
        assertTrue(toolUse.getMetadata().isEmpty());
    }

    @Test
    void testParseParallelFunctionCallsWithThoughtSignature() {
        // Gemini 3 Pro: parallel function calls - only first has thought signature
        Map<String, Object> args1 = new HashMap<>();
        args1.put("city", "Paris");

        Map<String, Object> args2 = new HashMap<>();
        args2.put("city", "London");

        byte[] thoughtSignature = "parallel-sig".getBytes();

        // First function call with signature
        FunctionCall fc1 =
                FunctionCall.builder().id("call-1").name("get_weather").args(args1).build();
        Part part1 = Part.builder().functionCall(fc1).thoughtSignature(thoughtSignature).build();

        // Second function call without signature
        FunctionCall fc2 =
                FunctionCall.builder().id("call-2").name("get_weather").args(args2).build();
        Part part2 = Part.builder().functionCall(fc2).build();

        Content content = Content.builder().role("model").parts(List.of(part1, part2)).build();

        Candidate candidate = Candidate.builder().content(content).build();

        GenerateContentResponse response =
                GenerateContentResponse.builder()
                        .responseId("response-parallel")
                        .candidates(List.of(candidate))
                        .build();

        // Parse
        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        // Verify
        assertNotNull(chatResponse);
        assertEquals(2, chatResponse.getContent().size());

        // First tool call should have signature
        ToolUseBlock toolUse1 = (ToolUseBlock) chatResponse.getContent().get(0);
        assertEquals("call-1", toolUse1.getId());
        assertTrue(toolUse1.getMetadata().containsKey(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));

        // Second tool call should not have signature
        ToolUseBlock toolUse2 = (ToolUseBlock) chatResponse.getContent().get(1);
        assertEquals("call-2", toolUse2.getId());
        assertTrue(toolUse2.getMetadata().isEmpty());
    }
}
