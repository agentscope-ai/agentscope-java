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

import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.agentscope.core.formatter.FormatterException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses Gemini API responses to AgentScope ChatResponse.
 *
 * <p>This parser handles the conversion of Gemini's GenerateContentResponse to AgentScope's
 * ChatResponse format, including:
 * <ul>
 *   <li>Text blocks from text parts</li>
 *   <li>Thinking blocks from parts with thought=true flag</li>
 *   <li>Tool use blocks from function_call parts</li>
 *   <li>Usage metadata with token counts</li>
 * </ul>
 *
 * <p><b>Important:</b> In Gemini API, thinking content is indicated by the "thought" flag
 * on Part objects.
 */
public class GeminiResponseParser {

    private static final Logger log = LoggerFactory.getLogger(GeminiResponseParser.class);

    /**
     * Creates a new GeminiResponseParser.
     */
    public GeminiResponseParser() {}

    /**
     * Parse Gemini GenerateContentResponse to AgentScope ChatResponse.
     *
     * @param response Gemini generation response
     * @param startTime Request start time for calculating duration
     * @return AgentScope ChatResponse
     */
    public ChatResponse parseResponse(GenerateContentResponse response, Instant startTime) {
        try {
            List<ContentBlock> blocks = new ArrayList<>();
            String finishReason = null;

            // Parse content from first candidate
            if (response.candidates().isPresent() && !response.candidates().get().isEmpty()) {
                Candidate candidate = response.candidates().get().get(0);

                if (candidate.content().isPresent()) {
                    Content content = candidate.content().get();

                    if (content.parts().isPresent()) {
                        List<Part> parts = content.parts().get();
                        parsePartsToBlocks(parts, blocks);
                    }
                }
                finishReason = candidate.finishMessage().orElse(null);
            }

            // Parse usage metadata
            ChatUsage usage = null;
            if (response.usageMetadata().isPresent()) {
                GenerateContentResponseUsageMetadata metadata = response.usageMetadata().get();

                int inputTokens = metadata.promptTokenCount().orElse(0);
                int totalOutputTokens = metadata.candidatesTokenCount().orElse(0);
                int thinkingTokens = metadata.thoughtsTokenCount().orElse(0);

                // Output tokens exclude thinking tokens (following DashScope behavior)
                // In Gemini, candidatesTokenCount includes thinking, so we subtract it
                int outputTokens = totalOutputTokens - thinkingTokens;

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
                    .id(response.responseId().orElse(null))
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
     * @param parts List of Gemini Part objects
     * @param blocks List to add parsed ContentBlocks to
     */
    protected void parsePartsToBlocks(List<Part> parts, List<ContentBlock> blocks) {
        for (Part part : parts) {
            // Check for thinking content first (parts with thought=true flag)
            if (part.thought().isPresent() && part.thought().get() && part.text().isPresent()) {
                String thinkingText = part.text().get();
                if (thinkingText != null && !thinkingText.isEmpty()) {
                    blocks.add(ThinkingBlock.builder().thinking(thinkingText).build());
                }
                continue;
            }

            // Check for text content
            if (part.text().isPresent()) {
                String text = part.text().get();
                if (text != null && !text.isEmpty()) {
                    blocks.add(TextBlock.builder().text(text).build());
                }
            }

            // Check for function call (tool use)
            if (part.functionCall().isPresent()) {
                FunctionCall functionCall = part.functionCall().get();
                byte[] thoughtSignature = part.thoughtSignature().orElse(null);
                parseToolCall(functionCall, thoughtSignature, blocks);
            }
        }
    }

    /**
     * Parse Gemini FunctionCall to ToolUseBlock.
     *
     * @param functionCall Gemini FunctionCall object
     * @param thoughtSignature Thought signature from the Part (may be null)
     * @param blocks List to add parsed ToolUseBlock to
     */
    protected void parseToolCall(
            FunctionCall functionCall, byte[] thoughtSignature, List<ContentBlock> blocks) {
        try {
            String id = functionCall.id().orElse("tool_call_" + System.currentTimeMillis());
            String name = functionCall.name().orElse("");

            if (name.isEmpty()) {
                log.warn("FunctionCall with empty name, skipping");
                return;
            }

            // Parse arguments
            Map<String, Object> argsMap = new HashMap<>();
            String rawContent = null;

            if (functionCall.args().isPresent()) {
                Map<String, Object> args = functionCall.args().get();
                if (args != null && !args.isEmpty()) {
                    argsMap.putAll(args);
                    // Convert to JSON string for raw content
                    try {
                        rawContent = JsonUtils.getJsonCodec().toJson(args);
                    } catch (Exception e) {
                        log.warn("Failed to serialize function call arguments: {}", e.getMessage());
                    }
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
