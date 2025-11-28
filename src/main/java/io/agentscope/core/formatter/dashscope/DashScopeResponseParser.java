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
package io.agentscope.core.formatter.dashscope;

import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.generation.GenerationUsage;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.formatter.FormatterException;
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
     * Parse DashScope GenerationResult to AgentScope ChatResponse.
     *
     * @param result DashScope generation result
     * @param startTime Request start time for calculating duration
     * @return AgentScope ChatResponse
     */
    public ChatResponse parseResponse(GenerationResult result, Instant startTime) {
        try {
            List<ContentBlock> blocks = new ArrayList<>();
            GenerationOutput out = result.getOutput();
            String finishReason = null;
            if (out != null && out.getChoices() != null && !out.getChoices().isEmpty()) {
                Message message = out.getChoices().get(0).getMessage();
                if (message != null) {
                    // Order matters! Follow this processing order:
                    // 1. ThinkingBlock first (reasoning_content)
                    // 2. Then TextBlock (content)
                    // 3. Finally ToolUseBlock (tool_calls)
                    String reasoningContent = message.getReasoningContent();
                    if (reasoningContent != null && !reasoningContent.isEmpty()) {
                        blocks.add(ThinkingBlock.builder().thinking(reasoningContent).build());
                    }

                    String content = message.getContent();
                    if (content != null && !content.isEmpty()) {
                        blocks.add(TextBlock.builder().text(content).build());
                    }

                    addToolCallsFromSdkMessage(message, blocks);
                }
                finishReason = out.getFinishReason();
            }

            ChatUsage usage = null;
            GenerationUsage u = result.getUsage();
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
                    .id(result.getRequestId())
                    .content(blocks)
                    .usage(usage)
                    .finishReason(finishReason)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse DashScope result: {}", e.getMessage(), e);
            throw new FormatterException("Failed to parse DashScope result: " + e.getMessage(), e);
        }
    }

    /**
     * Parse tool calls from DashScope SDK message and add to blocks.
     *
     * @param message DashScope message
     * @param blocks Content blocks to add tool use blocks to
     */
    protected void addToolCallsFromSdkMessage(Message message, List<ContentBlock> blocks) {
        List<ToolCallBase> tcs = message.getToolCalls();
        if (tcs == null || tcs.isEmpty()) return;
        int idx = 0;
        for (ToolCallBase base : tcs) {
            String id = base.getId();
            if (base instanceof ToolCallFunction fcall) {
                ToolCallFunction.CallFunction cf = fcall.getFunction();
                if (cf == null) continue;
                String name = cf.getName();
                String argsJson = cf.getArguments();
                Map<String, Object> argsMap = new HashMap<>();
                String rawContent = null;

                if (argsJson != null && !argsJson.isEmpty()) {
                    rawContent = argsJson;
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = objectMapper.readValue(argsJson, Map.class);
                        if (parsed != null) argsMap.putAll(parsed);
                    } catch (Exception ignored) {
                        // Keep raw content for later aggregation when JSON parsing fails
                        // This handles streaming tool calls where arguments are fragmented
                    }
                }
                // For DashScope streaming tool calls:
                // - First chunk: has name, callId, and partial arguments
                // - Subsequent chunks: only have arguments fragments, no name/callId
                if (name != null && !name.trim().isEmpty()) {
                    // First chunk with complete metadata
                    String callId =
                            id != null
                                    ? id
                                    : ("tool_call_" + System.currentTimeMillis() + "_" + idx);
                    blocks.add(
                            ToolUseBlock.builder()
                                    .id(callId)
                                    .name(name)
                                    .input(argsMap)
                                    .content(rawContent)
                                    .build());
                } else if (rawContent != null) {
                    // Subsequent chunks with only argument fragments
                    // Use placeholder values for aggregation by ToolCallAccumulator
                    String callId =
                            id != null
                                    ? id
                                    : ("fragment_" + System.currentTimeMillis() + "_" + idx);
                    blocks.add(
                            ToolUseBlock.builder()
                                    .id(callId)
                                    .name(FRAGMENT_PLACEHOLDER) // Placeholder name for fragments
                                    .input(argsMap)
                                    .content(rawContent)
                                    .build());
                }
            }
            idx++;
        }
    }
}
