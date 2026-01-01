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
package io.agentscope.core.formatter.dashscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.formatter.FormatterException;
import io.agentscope.core.formatter.dashscope.dto.DashScopeChoice;
import io.agentscope.core.formatter.dashscope.dto.DashScopeFunction;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.formatter.dashscope.dto.DashScopeOutput;
import io.agentscope.core.formatter.dashscope.dto.DashScopeResponse;
import io.agentscope.core.formatter.dashscope.dto.DashScopeToolCall;
import io.agentscope.core.formatter.dashscope.dto.DashScopeUsage;
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
 * Parses DashScope API responses to AgentScope ChatResponse.
 */
public class DashScopeResponseParser {

    private static final Logger log = LoggerFactory.getLogger(DashScopeResponseParser.class);

    /** Placeholder name for tool call argument fragments in streaming responses. */
    protected static final String FRAGMENT_PLACEHOLDER = "__fragment__";

    private final ObjectMapper objectMapper;

    public DashScopeResponseParser() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Parse DashScopeResponse to AgentScope ChatResponse.
     *
     * @param response DashScope response DTO
     * @param startTime Request start time for calculating duration
     * @return AgentScope ChatResponse
     */
    public ChatResponse parseResponse(DashScopeResponse response, Instant startTime) {
        try {
            List<ContentBlock> blocks = new ArrayList<>();
            String finishReason = null;

            DashScopeOutput output = response.getOutput();
            if (output != null) {
                DashScopeChoice choice = output.getFirstChoice();
                if (choice != null) {
                    DashScopeMessage message = choice.getMessage();
                    if (message != null) {
                        // Order matters! Follow this processing order:
                        // 1. ThinkingBlock first (reasoning_content)
                        // 2. Then TextBlock (content)
                        // 3. Finally ToolUseBlock (tool_calls)

                        String reasoningContent = message.getReasoningContent();
                        if (reasoningContent != null && !reasoningContent.isEmpty()) {
                            blocks.add(ThinkingBlock.builder().thinking(reasoningContent).build());
                        }

                        String content = message.getContentAsString();
                        if (content != null && !content.isEmpty()) {
                            blocks.add(TextBlock.builder().text(content).build());
                        }

                        // Handle tool calls
                        addToolCallsFromMessage(message, blocks);
                    }
                    finishReason = choice.getFinishReason();
                }

                // Fallback to output-level finish reason
                if (finishReason == null) {
                    finishReason = output.getFinishReason();
                }
            }

            ChatUsage usage = null;
            DashScopeUsage u = response.getUsage();
            if (u != null) {
                usage =
                        ChatUsage.builder()
                                .inputTokens(u.getInputTokens() != null ? u.getInputTokens() : 0)
                                .outputTokens(u.getOutputTokens() != null ? u.getOutputTokens() : 0)
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();
            }

            return ChatResponse.builder()
                    .id(response.getRequestId())
                    .content(blocks)
                    .usage(usage)
                    .finishReason(finishReason)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse DashScope response: {}", e.getMessage(), e);
            throw new FormatterException(
                    "Failed to parse DashScope response: " + e.getMessage(), e);
        }
    }

    /**
     * Parse tool calls from DashScopeMessage and add to blocks.
     *
     * @param message DashScopeMessage
     * @param blocks Content blocks to add tool use blocks to
     */
    protected void addToolCallsFromMessage(DashScopeMessage message, List<ContentBlock> blocks) {
        List<DashScopeToolCall> toolCalls = message.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }

        int idx = 0;
        for (DashScopeToolCall toolCall : toolCalls) {
            if (toolCall == null) continue;

            String id = toolCall.getId();
            DashScopeFunction function = toolCall.getFunction();
            if (function == null) continue;

            String name = function.getName();
            String argsJson = function.getArguments();

            Map<String, Object> argsMap = new HashMap<>();
            String rawContent = null;

            if (argsJson != null && !argsJson.isEmpty()) {
                rawContent = argsJson;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue(argsJson, Map.class);
                    if (parsed != null) {
                        argsMap.putAll(parsed);
                    }
                } catch (Exception ignored) {
                    // Keep raw content for later aggregation when JSON parsing fails
                    // This handles streaming tool calls where arguments are fragmented
                }
            }

            // For DashScope streaming tool calls:
            // - First chunk: has name, id, and partial arguments
            // - Subsequent chunks: only have argument fragments, no name/id
            if (name != null && !name.trim().isEmpty()) {
                // First chunk with complete metadata
                String callId =
                        id != null ? id : ("tool_call_" + System.currentTimeMillis() + "_" + idx);
                blocks.add(
                        ToolUseBlock.builder()
                                .id(callId)
                                .name(name)
                                .input(argsMap)
                                .content(rawContent)
                                .build());
            } else if (rawContent != null) {
                // Subsequent chunks with only argument fragments
                String callId =
                        id != null ? id : ("fragment_" + System.currentTimeMillis() + "_" + idx);
                blocks.add(
                        ToolUseBlock.builder()
                                .id(callId)
                                .name(FRAGMENT_PLACEHOLDER)
                                .input(argsMap)
                                .content(rawContent)
                                .build());
            }
            idx++;
        }
    }
}
