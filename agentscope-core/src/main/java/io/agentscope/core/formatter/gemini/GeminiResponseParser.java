/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.formatter.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.formatter.FormatterException;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses Gemini API responses to AgentScope ChatResponse.
 */
public class GeminiResponseParser {

    private static final Logger log = LoggerFactory.getLogger(GeminiResponseParser.class);

    private final ObjectMapper objectMapper;

    /**
     * Creates a new GeminiResponseParser with default ObjectMapper.
     */
    public GeminiResponseParser() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Parse Gemini GenerateContentResponse to AgentScope ChatResponse.
     *
     * @param response  Gemini generation response
     * @param startTime Request start time for calculating duration
     * @return AgentScope ChatResponse
     */
    public ChatResponse parseResponse(GeminiResponse response, Instant startTime) {
        try {
            List<ContentBlock> blocks = new ArrayList<>();
            String finishReason = null;

            // Parse content from first candidate
            if (response.getCandidates() != null && !response.getCandidates().isEmpty()) {
                GeminiCandidate candidate = response.getCandidates().get(0);

                if (candidate.getContent() != null) {
                    GeminiContent content = candidate.getContent();

                    if (content.getParts() != null) {
                        List<GeminiPart> parts = content.getParts();
                        parsePartsToBlocks(parts, blocks);
                    }
                }
                finishReason = candidate.getFinishReason();
            }

            // Parse usage metadata
            ChatUsage usage = null;
            if (response.getUsageMetadata() != null) {
                GeminiUsageMetadata metadata = response.getUsageMetadata();

                int inputTokens =
                        metadata.getPromptTokenCount() != null ? metadata.getPromptTokenCount() : 0;
                int totalOutputTokens =
                        metadata.getCandidatesTokenCount() != null
                                ? metadata.getCandidatesTokenCount()
                                : 0;

                // Note: thinking tokens field might not be in generic UsageMetadata unless we
                // add it
                // Assuming it's not crucial or we add it to DTO if needed.
                // For now, use totalOutputTokens.
                int outputTokens = totalOutputTokens;

                usage =
                        ChatUsage.builder()
                                .inputTokens(inputTokens)
                                .outputTokens(outputTokens)
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();
            }

            return ChatResponse.builder()
                    // Response ID is not always present in simple JSON or might be different key
                    .content(blocks)
                    .usage(usage)
                    .finishReason(finishReason)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", e.getMessage(), e);
            throw new FormatterException("Failed to parse Gemini response: " + e.getMessage(), e);
        }
    }

    /**
     * Parse Gemini Part objects to AgentScope ContentBlocks.
     * Order of block types: ThinkingBlock, TextBlock, ToolUseBlock
     *
     * @param parts  List of Gemini Part objects
     * @param blocks List to add parsed ContentBlocks to
     */
    protected void parsePartsToBlocks(List<GeminiPart> parts, List<ContentBlock> blocks) {
        for (GeminiPart part : parts) {
            // Check for thinking content first (parts with thought=true flag)
            if (Boolean.TRUE.equals(part.getThought()) && part.getText() != null) {
                String thinkingText = part.getText();
                if (!thinkingText.isEmpty()) {
                    blocks.add(ThinkingBlock.builder().thinking(thinkingText).build());
                }
                continue;
            }

            // Check for text content
            if (part.getText() != null) {
                String text = part.getText();
                if (!text.isEmpty()) {
                    blocks.add(TextBlock.builder().text(text).build());
                }
            }

            // Check for function call (tool use)
            if (part.getFunctionCall() != null) {
                GeminiFunctionCall functionCall = part.getFunctionCall();
                // Thought signature not in current DTO, passing null or removing logic
                parseToolCall(functionCall, null, blocks);
            }
        }
    }

    /**
     * Parse Gemini FunctionCall to ToolUseBlock.
     *
     * @param functionCall     Gemini FunctionCall object
     * @param thoughtSignature Thought signature from the Part (may be null)
     * @param blocks           List to add parsed ToolUseBlock to
     */
    protected void parseToolCall(
            GeminiFunctionCall functionCall, byte[] thoughtSignature, List<ContentBlock> blocks) {
        try {
            String id = functionCall.getId();
            if (id == null || id.isEmpty()) {
                id = "tool_call_" + System.currentTimeMillis(); // Fallback if ID is missing
            }
            String name = functionCall.getName() != null ? functionCall.getName() : "";

            if (name.isEmpty()) {
                log.warn("FunctionCall with empty name, skipping");
                return;
            }

            // Parse arguments
            Map<String, Object> argsMap = new HashMap<>();
            String rawContent = null;

            if (functionCall.getArgs() != null && !functionCall.getArgs().isEmpty()) {
                argsMap.putAll(functionCall.getArgs());
                // Convert to JSON string for raw content
                try {
                    rawContent = objectMapper.writeValueAsString(functionCall.getArgs());
                } catch (Exception e) {
                    log.warn("Failed to serialize function call arguments: {}", e.getMessage());
                }
            }

            // Build metadata with thought signature if present
            Map<String, Object> metadata = null;
            if (thoughtSignature != null) {
                metadata = new HashMap<>();
                metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, thoughtSignature);
            }

            blocks.add(
                    ToolUseBlock.builder()
                            .id(id)
                            .name(name)
                            .input(argsMap)
                            .content(rawContent)
                            .metadata(metadata)
                            .build());

        } catch (Exception e) {
            log.warn("Failed to parse function call: {}", e.getMessage(), e);
        }
    }
}
