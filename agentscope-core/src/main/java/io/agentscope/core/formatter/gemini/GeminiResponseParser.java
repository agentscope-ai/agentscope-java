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
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses Gemini API responses to AgentScope ChatResponse.
 *
 * <p>
 * This parser handles the conversion of Gemini's GenerateContentResponse to
 * AgentScope's
 * ChatResponse format, including:
 * <ul>
 * <li>Text blocks from text parts</li>
 * <li>Thinking blocks from parts with thought=true flag</li>
 * <li>Tool use blocks from function_call parts</li>
 * <li>Usage metadata with token counts</li>
 * </ul>
 *
 * <p>
 * <b>Important:</b> In Gemini API, thinking content is indicated by the
 * "thought" flag
 * on Part objects.
 */
public class GeminiResponseParser {

    private static final Logger log = LoggerFactory.getLogger(GeminiResponseParser.class);

    /**
     * Metadata key for Gemini thought signature.
     *
     * <p>
     * Gemini thinking models return encrypted thought signatures that must be
     * passed back in
     * subsequent requests to maintain reasoning context across turns.
     */
    public static final String METADATA_THOUGHT_SIGNATURE = "thoughtSignature";

    /**
     * Creates a new GeminiResponseParser.
     */
    public GeminiResponseParser() {}

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

                // Log debug if content is empty (common in streaming or metadata-only updates)
                if (blocks.isEmpty()) {
                    log.debug(
                            "Gemini returned empty content blocks in this chunk/response."
                                    + " finishReason={}",
                            finishReason);
                }
            } else {
                // No candidates at all
                log.warn(
                        "Gemini returned no candidates. promptFeedback={}",
                        response.getPromptFeedback());
                // Add error block to inform the user that no content was returned
                // This aligns with the GenAI SDK's behavior of throwing on unexpected finish
                // reasons
                String errorMessage = "Gemini returned no candidates";
                if (finishReason != null && !finishReason.isEmpty()) {
                    errorMessage += " (finishReason: " + finishReason + ")";
                }
                blocks.add(TextBlock.builder().text(errorMessage).build());
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

                int outputTokens = totalOutputTokens;
                int reasoningTokens = 0;

                // Extract thinking/reasoning tokens if available
                if (metadata.getCandidatesTokensDetails() != null) {
                    Map<String, Object> details = metadata.getCandidatesTokensDetails();
                    if (details.containsKey("modalityTokenCount")
                            && details.get("modalityTokenCount") instanceof Map) {
                        Map<?, ?> modalityCount = (Map<?, ?>) details.get("modalityTokenCount");
                        // Check for common keys for thinking tokens
                        if (modalityCount.containsKey("thought")
                                && modalityCount.get("thought") instanceof Number) {
                            reasoningTokens = ((Number) modalityCount.get("thought")).intValue();
                        } else if (modalityCount.containsKey("reasoning")
                                && modalityCount.get("reasoning") instanceof Number) {
                            reasoningTokens = ((Number) modalityCount.get("reasoning")).intValue();
                        }
                    }
                }

                usage =
                        ChatUsage.builder()
                                .inputTokens(inputTokens)
                                .outputTokens(outputTokens)
                                .reasoningTokens(reasoningTokens)
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();
            }

            return ChatResponse.builder()
                    // Use actual response ID if available, otherwise generate one
                    .id(
                            response.getResponseId() != null
                                    ? response.getResponseId()
                                    : UUID.randomUUID().toString())
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
            boolean processedAsThought = false;

            // Check for thinking content (parts with thought=true flag OR having
            // thoughtSignature)
            boolean isThought = Boolean.TRUE.equals(part.getThought());
            String thoughtSignature = part.getThoughtSignature();
            if (thoughtSignature == null || thoughtSignature.isEmpty()) {
                // Fallback for older API or different field name
                thoughtSignature = part.getSignature();
            }
            boolean hasThoughtSignature = thoughtSignature != null && !thoughtSignature.isEmpty();

            if ((isThought || hasThoughtSignature) && part.getText() != null) {
                String thinkingText = part.getText();
                // Create block if there is text OR signature (to preserve context)
                if (!thinkingText.isEmpty() || hasThoughtSignature) {
                    blocks.add(
                            ThinkingBlock.builder()
                                    .thinking(thinkingText)
                                    .signature(thoughtSignature)
                                    .build());
                    processedAsThought = true;
                }
            }

            // Check for standard text content (only if not processed as thought)
            if (!processedAsThought && part.getText() != null) {
                String text = part.getText();
                if (!text.isEmpty()) {
                    blocks.add(TextBlock.builder().text(text).build());
                }
            }

            // Check for function call (tool use) - check this INDEPENDENTLY
            if (part.getFunctionCall() != null) {
                GeminiFunctionCall functionCall = part.getFunctionCall();
                // Try thoughtSignature first (Gemini 2.5+), fall back to signature
                String thoughtSig = part.getThoughtSignature();
                if (thoughtSig == null || thoughtSig.isEmpty()) {
                    thoughtSig = part.getSignature();
                }
                parseToolCall(functionCall, thoughtSig, blocks);
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
            GeminiFunctionCall functionCall, String thoughtSignature, List<ContentBlock> blocks) {
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
                    rawContent = JsonUtils.getJsonCodec().toJson(functionCall.getArgs());
                } catch (Exception e) {
                    log.warn("Failed to serialize function call arguments: {}", e.getMessage());
                }
            }

            // Build metadata with thought signature if present
            Map<String, Object> metadata = null;
            if (thoughtSignature != null && !thoughtSignature.isEmpty()) {
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
