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
package io.agentscope.core.formatter;

import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.generation.GenerationUsage;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formatter for DashScope Conversation/Generation APIs.
 * Converts between AgentScope Msg objects and DashScope SDK types.
 */
public class DashScopeChatFormatter implements Formatter<Message, GenerationResult> {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatFormatter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<Message> format(List<Msg> msgs) {
        List<Message> result = new ArrayList<>();
        for (Msg msg : msgs) {
            Message dsMsg = convertToMessage(msg);
            if (dsMsg != null) {
                result.add(dsMsg);
            }
        }
        return result;
    }

    @Override
    public ChatResponse parseResponse(GenerationResult result, Instant startTime) {
        try {
            List<ContentBlock> blocks = new ArrayList<>();
            GenerationOutput out = result.getOutput();
            if (out != null) {
                String text = out.getText();
                if (text != null && !text.isEmpty()) {
                    blocks.add(TextBlock.builder().text(text).build());
                }

                if (out.getChoices() != null && !out.getChoices().isEmpty()) {
                    Message message = out.getChoices().get(0).getMessage();
                    if (message != null) {
                        String reasoningContent = message.getReasoningContent();
                        if (reasoningContent != null && !reasoningContent.isEmpty()) {
                            blocks.add(ThinkingBlock.builder().text(reasoningContent).build());
                        }
                        String content = message.getContent();
                        if (content != null && !content.isEmpty()) {
                            blocks.add(TextBlock.builder().text(content).build());
                        }
                        // Parse tool calls via SDK types
                        addToolCallsFromSdkMessage(message, blocks);
                    }
                }
            }

            ChatUsage usage = null;
            GenerationUsage u = result.getUsage();
            if (u != null) {
                usage =
                        ChatUsage.builder()
                                .inputTokens(
                                        u.getInputTokens() != null
                                                ? u.getInputTokens().intValue()
                                                : 0)
                                .outputTokens(
                                        u.getOutputTokens() != null
                                                ? u.getOutputTokens().intValue()
                                                : 0)
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();
            }
            return ChatResponse.builder()
                    .id(result.getRequestId())
                    .content(blocks)
                    .usage(usage)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse DashScope result: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse DashScope result: " + e.getMessage(), e);
        }
    }

    private Message convertToMessage(Msg msg) {
        Message dsMsg = new Message();
        dsMsg.setRole(msg.getRole().name().toLowerCase());
        dsMsg.setContent(extractTextContent(msg));

        // Handle tool calls for assistant messages
        if (msg.getRole() == MsgRole.ASSISTANT) {
            List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
            if (!toolBlocks.isEmpty()) {
                List<ToolCallBase> toolCalls = convertToolCalls(toolBlocks);
                dsMsg.setToolCalls(toolCalls);
            }
        }

        // Handle tool results for tool messages
        if (msg.getRole() == MsgRole.TOOL) {
            ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
            if (result != null) {
                dsMsg.setToolCallId(result.getId());
            }
        }

        return dsMsg;
    }

    private String extractTextContent(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock tb) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(tb.getText());
            } else if (block instanceof ThinkingBlock tb) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(tb.getThinking());
            } else if (block instanceof ToolResultBlock toolResult) {
                // Extract text from tool result output
                ContentBlock output = toolResult.getOutput();
                if (output instanceof TextBlock textBlock) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(textBlock.getText());
                }
            }
        }
        return sb.toString();
    }

    private List<ToolCallBase> convertToolCalls(List<ToolUseBlock> toolBlocks) {
        List<ToolCallBase> result = new ArrayList<>();

        for (ToolUseBlock toolUse : toolBlocks) {
            ToolCallFunction tcf = new ToolCallFunction();
            tcf.setId(toolUse.getId());

            // Create CallFunction as inner class instance
            ToolCallFunction.CallFunction cf = tcf.new CallFunction();
            cf.setName(toolUse.getName());

            // Convert arguments map to JSON string
            try {
                String argsJson = objectMapper.writeValueAsString(toolUse.getInput());
                cf.setArguments(argsJson);
            } catch (Exception e) {
                log.warn("Failed to serialize tool call arguments: {}", e.getMessage());
                cf.setArguments("{}");
            }

            tcf.setFunction(cf);
            result.add(tcf);
        }

        return result;
    }

    private void addToolCallsFromSdkMessage(Message message, List<ContentBlock> blocks) {
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
                if (name != null) {
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
                } else if (rawContent != null && !rawContent.isEmpty()) {
                    // Subsequent chunks with only argument fragments
                    // Use placeholder values for aggregation by ToolCallAccumulator
                    String callId =
                            id != null
                                    ? id
                                    : ("fragment_" + System.currentTimeMillis() + "_" + idx);
                    blocks.add(
                            ToolUseBlock.builder()
                                    .id(callId)
                                    .name("__fragment__") // Placeholder name for fragments
                                    .input(argsMap)
                                    .content(rawContent)
                                    .build());
                }
            }
            idx++;
        }
    }

    @Override
    public FormatterCapabilities getCapabilities() {
        return FormatterCapabilities.builder()
                .providerName("DashScope")
                .supportToolsApi(true)
                .supportMultiAgent(false)
                .supportVision(true)
                .supportedBlocks(
                        Set.of(
                                TextBlock.class,
                                ToolUseBlock.class,
                                ToolResultBlock.class,
                                ThinkingBlock.class))
                .build();
    }
}
