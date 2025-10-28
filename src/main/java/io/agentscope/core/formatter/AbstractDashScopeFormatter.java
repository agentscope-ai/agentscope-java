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
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.tools.ToolFunction;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base formatter for DashScope API formatters.
 *
 * <p>This class contains shared logic for DashScope formatters including:
 * <ul>
 *   <li>Response parsing (parseResponse)
 *   <li>Options application (applyOptions)
 *   <li>Tools application (applyTools)
 *   <li>Tool calls conversion (convertToolCalls)
 *   <li>Tool calls parsing (addToolCallsFromSdkMessage)
 * </ul>
 */
public abstract class AbstractDashScopeFormatter
        extends AbstractBaseFormatter<Message, GenerationResult, GenerationParam> {

    private static final Logger log = LoggerFactory.getLogger(AbstractDashScopeFormatter.class);

    /** Placeholder name for tool call argument fragments in streaming responses. */
    protected static final String FRAGMENT_PLACEHOLDER = "__fragment__";

    @Override
    public ChatResponse parseResponse(GenerationResult result, Instant startTime) {
        try {
            List<ContentBlock> blocks = new ArrayList<>();
            GenerationOutput out = result.getOutput();
            if (out != null && out.getChoices() != null && !out.getChoices().isEmpty()) {
                Message message = out.getChoices().get(0).getMessage();
                if (message != null) {
                    // Order matters! Match Python implementation:
                    // 1. ThinkingBlock first (reasoning_content)
                    // 2. Then TextBlock (content)
                    // 3. Finally ToolUseBlock (tool_calls)
                    String reasoningContent = message.getReasoningContent();
                    if (reasoningContent != null && !reasoningContent.isEmpty()) {
                        blocks.add(ThinkingBlock.builder().text(reasoningContent).build());
                    }

                    String content = message.getContent();
                    if (content != null && !content.isEmpty()) {
                        blocks.add(TextBlock.builder().text(content).build());
                    }

                    addToolCallsFromSdkMessage(message, blocks);
                }
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

    @Override
    public void applyOptions(
            GenerationParam param, GenerateOptions options, GenerateOptions defaultOptions) {
        // Apply each option individually, falling back to defaultOptions if the specific field is
        // null
        Double temperature =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getTemperature);
        if (temperature != null) param.setTemperature(temperature.floatValue());

        Double topP = getOptionOrDefault(options, defaultOptions, GenerateOptions::getTopP);
        if (topP != null) param.setTopP(topP);

        Integer maxTokens =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) param.setMaxTokens(maxTokens);

        Integer thinkingBudget =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getThinkingBudget);
        if (thinkingBudget != null) param.setThinkingBudget(thinkingBudget);
    }

    @Override
    public void applyTools(GenerationParam param, List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }

        // Use Gson for DashScope SDK compatibility (required by DashScope API)
        // Note: DashScope SDK expects Gson JsonElement/JsonObject, not Jackson JsonNode
        Gson gson = new Gson();
        List<ToolBase> toolList = new ArrayList<>();
        for (ToolSchema t : tools) {
            FunctionDefinition.FunctionDefinitionBuilder<?, ?> fdb = FunctionDefinition.builder();
            if (t.getName() != null) fdb.name(t.getName());
            if (t.getDescription() != null) fdb.description(t.getDescription());
            if (t.getParameters() != null) {
                // Must use Gson here because DashScope SDK's FunctionDefinition.parameters()
                // specifically requires com.google.gson.JsonObject type
                JsonElement el = gson.toJsonTree(t.getParameters());
                if (el != null && el.isJsonObject()) {
                    fdb.parameters(el.getAsJsonObject());
                } else {
                    fdb.parameters(new com.google.gson.JsonObject());
                }
            }
            FunctionDefinition fd = fdb.build();
            ToolFunction toolFn = ToolFunction.builder().type("function").function(fd).build();
            toolList.add(toolFn);
        }
        param.setTools(toolList);
        log.debug("DashScope tools registered: {}", toolList.size());
    }

    /**
     * Convert ToolUseBlock list to DashScope ToolCallBase format.
     *
     * @param toolBlocks The tool use blocks to convert
     * @return List of ToolCallBase objects for DashScope API (empty list if input is null/empty)
     */
    protected List<ToolCallBase> convertToolCalls(List<ToolUseBlock> toolBlocks) {
        if (toolBlocks == null || toolBlocks.isEmpty()) {
            return List.of();
        }

        List<ToolCallBase> result = new ArrayList<>();

        for (ToolUseBlock toolUse : toolBlocks) {
            if (toolUse == null) {
                log.warn("Skipping null ToolUseBlock in convertToolCalls");
                continue;
            }
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
}
