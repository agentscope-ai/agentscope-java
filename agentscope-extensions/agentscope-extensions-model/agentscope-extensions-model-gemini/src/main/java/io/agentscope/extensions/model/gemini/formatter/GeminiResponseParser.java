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
package io.agentscope.extensions.model.gemini.formatter;

import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.ToolCall;
import com.google.genai.types.ToolResponse;
import com.google.genai.types.ToolType;
import io.agentscope.core.formatter.FormatterException;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
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
 *   <li>Tool use blocks from function_call parts (local function calls)</li>
 *   <li>Tool use and tool result blocks from tool_call/tool_response parts (server-side
 *       built-in tools)</li>
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
     * Order of block types: ThinkingBlock, TextBlock, ToolUseBlock, ToolResultBlock
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

            // Check for tool call (server tool use)
            if (part.toolCall().isPresent()) {
                ToolCall toolCall = part.toolCall().get();
                byte[] thoughtSignature = part.thoughtSignature().orElse(null);
                parseToolCall(toolCall, thoughtSignature, blocks);
            }

            // Check for tool response (server tool result)
            if (part.toolResponse().isPresent()) {
                ToolResponse toolResponse = part.toolResponse().get();
                byte[] thoughtSignature = part.thoughtSignature().orElse(null);
                parseToolResponse(toolResponse, thoughtSignature, blocks);
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
            FunctionCall functionCall, byte[] thoughtSignature, List<ContentBlock> blocks) {
        try {
            String id = functionCall.id().orElse("tool_call_" + System.currentTimeMillis());
            String name = functionCall.name().orElse("");

            if (name.isEmpty()) {
                log.warn("FunctionCall with empty name, skipping");
                return;
            }

            blocks.add(
                    convertToolUseBlock(
                            id,
                            name,
                            functionCall.args().orElse(null),
                            thoughtSignature,
                            null,
                            false));
        } catch (Exception e) {
            log.warn("Failed to parse function call: {}", e.getMessage(), e);
        }
    }

    /**
     * Parse Gemini ToolCall to ToolUseBlock for server-side (built-in) tools.
     *
     * @param toolCall         Gemini ToolCall object
     * @param thoughtSignature Thought signature from the Part (may be null)
     * @param blocks           List to add parsed ToolUseBlock to
     */
    protected void parseToolCall(
            ToolCall toolCall, byte[] thoughtSignature, List<ContentBlock> blocks) {
        try {
            String id = toolCall.id().orElse("tool_call_" + System.currentTimeMillis());
            String name = toolCall.toolType().map(ToolType::toString).orElse("");

            if (name.isEmpty()) {
                log.warn("ToolCall with empty name, skipping");
                return;
            }
            blocks.add(
                    convertToolUseBlock(
                            id,
                            name,
                            toolCall.args().orElse(null),
                            thoughtSignature,
                            ToolCallState.FINISHED,
                            true));
        } catch (Exception e) {
            log.warn("Failed to parse tool call: {}", e.getMessage(), e);
        }
    }

    /**
     * Converts a Gemini tool invocation to a ToolUseBlock.
     *
     * @param id               Tool call ID
     * @param name             Tool name (function name or tool type)
     * @param args             Tool arguments map (may be null)
     * @param thoughtSignature Thought signature from the Part (may be null)
     * @param server           Whether the tool is executed by the model provider server-side
     * @return A new ToolUseBlock
     */
    protected ToolUseBlock convertToolUseBlock(
            String id,
            String name,
            Map<String, Object> args,
            byte[] thoughtSignature,
            ToolCallState state,
            boolean server) {
        // Parse arguments
        Map<String, Object> argsMap = new HashMap<>();
        String rawContent = null;

        if (args != null && !args.isEmpty()) {
            argsMap.putAll(args);
            // Convert to JSON string for raw content
            try {
                rawContent = JsonUtils.getJsonCodec().toJson(args);
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

        return ToolUseBlock.builder()
                .id(id)
                .server(server)
                .name(name)
                .input(argsMap)
                .content(rawContent)
                .metadata(metadata)
                .state(state)
                .build();
    }

    /**
     * Parse Gemini ToolResponse to ToolResultBlock for server-side (built-in) tools.
     *
     * @param toolResponse     Gemini ToolResponse object
     * @param thoughtSignature Thought signature from the Part (may be null)
     * @param blocks           List to add parsed ToolResultBlock to
     */
    protected void parseToolResponse(
            ToolResponse toolResponse, byte[] thoughtSignature, List<ContentBlock> blocks) {
        try {
            String id = toolResponse.id().orElse("tool_call_" + System.currentTimeMillis());
            String name = toolResponse.toolType().map(ToolType::toString).orElse("");

            if (name.isEmpty()) {
                log.warn("ToolResponse with empty name, skipping");
                return;
            }

            // Build metadata with thought signature if present
            Map<String, Object> metadata = null;
            if (thoughtSignature != null) {
                metadata = new HashMap<>();
                metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, thoughtSignature);
            }

            ToolResultBlock.Builder toolResultBuilder =
                    ToolResultBlock.builder()
                            .id(id)
                            .name(name)
                            .server(true)
                            .state(ToolResultState.SUCCESS)
                            .metadata(metadata);

            if (toolResponse.response().isPresent()) {
                toolResultBuilder.output(
                        TextBlock.builder()
                                .text(
                                        JsonUtils.getJsonCodec()
                                                .toJson(toolResponse.response().get()))
                                .build());
            }

            blocks.add(toolResultBuilder.build());
        } catch (Exception e) {
            log.warn("Failed to parse tool response: {}", e.getMessage(), e);
        }
    }
}
