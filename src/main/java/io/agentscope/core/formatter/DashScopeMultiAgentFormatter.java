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
import com.alibaba.dashscope.aigc.generation.GenerationParam;
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
import io.agentscope.core.model.GenerateOptions;
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
 * DashScope formatter for multi-agent conversations.
 * Converts AgentScope Msg objects to DashScope SDK Message objects with multi-agent support.
 * Collapses multi-agent conversation into a single user message with history tags.
 */
public class DashScopeMultiAgentFormatter
        implements Formatter<Message, GenerationResult, GenerationParam> {

    private static final Logger log = LoggerFactory.getLogger(DashScopeMultiAgentFormatter.class);
    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<Message> format(List<Msg> msgs) {
        List<Message> result = new ArrayList<>();

        // Separate tool sequences from conversation
        List<Msg> conversation = new ArrayList<>();
        List<Msg> toolSeq = new ArrayList<>();

        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.TOOL
                    || (msg.getRole() == MsgRole.ASSISTANT
                            && msg.hasContentBlocks(ToolUseBlock.class))) {
                toolSeq.add(msg);
            } else {
                conversation.add(msg);
            }
        }

        if (!conversation.isEmpty()) {
            result.add(formatAgentConversation(conversation));
        }
        if (!toolSeq.isEmpty()) {
            result.addAll(formatToolSeq(toolSeq));
        }
        return result;
    }

    private Message formatAgentConversation(List<Msg> msgs) {
        // Build conversation with agent names
        StringBuilder textAccumulator = new StringBuilder();
        textAccumulator.append(HISTORY_START_TAG).append("\n");

        for (Msg msg : msgs) {
            String name = msg.getName() != null ? msg.getName() : "Unknown";
            String role = formatRoleLabel(msg.getRole());

            List<ContentBlock> blocks = msg.getContent();
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock tb) {
                    textAccumulator
                            .append(role)
                            .append(" ")
                            .append(name)
                            .append(": ")
                            .append(tb.getText())
                            .append("\n");
                } else if (block instanceof ThinkingBlock tb) {
                    textAccumulator
                            .append(role)
                            .append(" ")
                            .append(name)
                            .append(": ")
                            .append(tb.getThinking())
                            .append("\n");
                } else if (block instanceof ToolResultBlock toolResult) {
                    ContentBlock output = toolResult.getOutput();
                    String resultText =
                            output instanceof TextBlock textBlock
                                    ? textBlock.getText()
                                    : extractTextContent(output);
                    textAccumulator
                            .append(role)
                            .append(" ")
                            .append(name)
                            .append(" (")
                            .append(toolResult.getName())
                            .append("): ")
                            .append(resultText)
                            .append("\n");
                }
            }
        }

        textAccumulator.append(HISTORY_END_TAG);

        Message message = new Message();
        message.setRole("user");
        message.setContent(textAccumulator.toString());
        return message;
    }

    private List<Message> formatToolSeq(List<Msg> msgs) {
        List<Message> result = new ArrayList<>();
        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.ASSISTANT) {
                result.add(formatAssistantToolCall(msg));
            } else if (msg.getRole() == MsgRole.TOOL) {
                result.add(formatToolResult(msg));
            }
        }
        return result;
    }

    private Message formatAssistantToolCall(Msg msg) {
        Message message = new Message();
        message.setRole("assistant");
        message.setContent(extractTextContent(msg));

        // Handle tool calls
        List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
        if (!toolBlocks.isEmpty()) {
            List<ToolCallBase> toolCalls = new ArrayList<>();
            for (ToolUseBlock toolUse : toolBlocks) {
                ToolCallFunction tcf = new ToolCallFunction();
                tcf.setId(toolUse.getId());

                ToolCallFunction.CallFunction cf = tcf.new CallFunction();
                cf.setName(toolUse.getName());

                try {
                    String argsJson = objectMapper.writeValueAsString(toolUse.getInput());
                    cf.setArguments(argsJson);
                } catch (Exception e) {
                    log.warn("Failed to serialize tool call arguments: {}", e.getMessage());
                    cf.setArguments("{}");
                }

                tcf.setFunction(cf);
                toolCalls.add(tcf);

                log.debug(
                        "Formatted multi-agent tool call: id={}, name={}",
                        toolUse.getId(),
                        toolUse.getName());
            }
            message.setToolCalls(toolCalls);
        }

        return message;
    }

    private Message formatToolResult(Msg msg) {
        Message message = new Message();
        message.setRole("tool");
        message.setContent(extractTextContent(msg));

        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        if (result != null) {
            message.setToolCallId(result.getId());
        } else {
            message.setToolCallId("tool_call_" + System.currentTimeMillis());
        }

        return message;
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
                ContentBlock output = toolResult.getOutput();
                if (output instanceof TextBlock textBlock) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(textBlock.getText());
                }
            }
        }
        return sb.toString();
    }

    private String extractTextContent(ContentBlock block) {
        if (block instanceof TextBlock tb) {
            return tb.getText();
        }
        return "";
    }

    private String formatRoleLabel(MsgRole role) {
        return switch (role) {
            case USER -> "User";
            case ASSISTANT -> "Assistant";
            case SYSTEM -> "System";
            case TOOL -> "Tool";
        };
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
    public void applyOptions(
            GenerationParam param, GenerateOptions options, GenerateOptions defaultOptions) {
        GenerateOptions opt = options != null ? options : defaultOptions;
        if (opt.getTemperature() != null) param.setTemperature(opt.getTemperature().floatValue());
        if (opt.getTopP() != null) param.setTopP(opt.getTopP());
        if (opt.getMaxTokens() != null) param.setMaxTokens(opt.getMaxTokens());
    }

    @Override
    public FormatterCapabilities getCapabilities() {
        return FormatterCapabilities.builder()
                .providerName("DashScope")
                .supportToolsApi(true)
                .supportMultiAgent(true)
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
