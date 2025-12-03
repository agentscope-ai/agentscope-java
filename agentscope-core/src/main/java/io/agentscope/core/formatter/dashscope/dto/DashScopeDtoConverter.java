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
package io.agentscope.core.formatter.dashscope.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts AgentScope messages and options to DashScope DTOs.
 *
 * <p>This class handles the conversion from AgentScope's message format to
 * DashScope's native API format (independent DTOs).
 */
public class DashScopeDtoConverter {

    private static final Logger log = LoggerFactory.getLogger(DashScopeDtoConverter.class);

    private final ObjectMapper objectMapper;
    private final Function<List<ContentBlock>, String> toolResultConverter;

    public DashScopeDtoConverter(Function<List<ContentBlock>, String> toolResultConverter) {
        this.objectMapper = new ObjectMapper();
        this.toolResultConverter = toolResultConverter;
    }

    /**
     * Convert a single Msg to DashScopeMessage.
     *
     * @param msg the message to convert
     * @param useMultimodal whether to use multimodal content format
     * @return the converted DashScopeMessage
     */
    public DashScopeMessage convertMessage(Msg msg, boolean useMultimodal) {
        DashScopeMessage.Builder builder = DashScopeMessage.builder();

        // Handle TOOL role messages
        ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
        if (toolResult != null
                && (msg.getRole() == MsgRole.TOOL || msg.getRole() == MsgRole.SYSTEM)) {
            builder.role("tool")
                    .toolCallId(toolResult.getId())
                    .name(toolResult.getName())
                    .content(toolResultConverter.apply(toolResult.getOutput()));
            return builder.build();
        }

        builder.role(msg.getRole().name().toLowerCase());

        if (useMultimodal) {
            builder.content(convertToMultimodalContent(msg));
        } else {
            builder.content(extractTextContent(msg));
        }

        // Handle assistant messages with tool calls
        if (msg.getRole() == MsgRole.ASSISTANT) {
            List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
            if (!toolBlocks.isEmpty()) {
                builder.toolCalls(convertToolCalls(toolBlocks));
            }
        }

        return builder.build();
    }

    /**
     * Convert list of Msg to list of DashScopeMessage.
     *
     * @param messages the messages to convert
     * @param useMultimodal whether to use multimodal content format
     * @return the converted list of DashScopeMessage
     */
    public List<DashScopeMessage> convertMessages(List<Msg> messages, boolean useMultimodal) {
        List<DashScopeMessage> result = new ArrayList<>();
        for (Msg msg : messages) {
            result.add(convertMessage(msg, useMultimodal));
        }
        return result;
    }

    /**
     * Build DashScopeParameters from GenerateOptions.
     *
     * @param options the generate options
     * @param defaultOptions default options to use as fallback
     * @param tools list of tool schemas
     * @param toolChoice the tool choice configuration
     * @param streaming whether this is a streaming request
     * @return the built DashScopeParameters
     */
    public DashScopeParameters buildParameters(
            GenerateOptions options,
            GenerateOptions defaultOptions,
            List<ToolSchema> tools,
            ToolChoice toolChoice,
            boolean streaming) {

        DashScopeParameters.Builder builder = DashScopeParameters.builder().resultFormat("message");

        if (streaming) {
            builder.incrementalOutput(true);
        }

        // Apply options with fallback
        Double temp = getOption(options, defaultOptions, GenerateOptions::getTemperature);
        if (temp != null) {
            builder.temperature(temp);
        }

        Double topP = getOption(options, defaultOptions, GenerateOptions::getTopP);
        if (topP != null) {
            builder.topP(topP);
        }

        Integer maxTokens = getOption(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }

        Integer thinkingBudget =
                getOption(options, defaultOptions, GenerateOptions::getThinkingBudget);
        if (thinkingBudget != null) {
            builder.thinkingBudget(thinkingBudget);
            builder.enableThinking(true);
        }

        // Apply tools
        if (tools != null && !tools.isEmpty()) {
            builder.tools(convertToolSchemas(tools));
        }

        // Apply tool choice
        if (toolChoice != null) {
            builder.toolChoice(convertToolChoice(toolChoice));
        }

        return builder.build();
    }

    /**
     * Build a complete DashScopeRequest.
     *
     * @param modelName the model name
     * @param messages the messages
     * @param useMultimodal whether to use multimodal format
     * @param options the generate options
     * @param defaultOptions default options
     * @param tools list of tool schemas
     * @param toolChoice tool choice configuration
     * @param streaming whether streaming
     * @return the built DashScopeRequest
     */
    public DashScopeRequest buildRequest(
            String modelName,
            List<Msg> messages,
            boolean useMultimodal,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            List<ToolSchema> tools,
            ToolChoice toolChoice,
            boolean streaming) {

        List<DashScopeMessage> dsMessages = convertMessages(messages, useMultimodal);
        DashScopeParameters parameters =
                buildParameters(options, defaultOptions, tools, toolChoice, streaming);

        return DashScopeRequest.builder()
                .model(modelName)
                .input(DashScopeInput.of(dsMessages))
                .parameters(parameters)
                .build();
    }

    private List<DashScopeContentPart> convertToMultimodalContent(Msg msg) {
        List<DashScopeContentPart> parts = new ArrayList<>();

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock tb) {
                parts.add(DashScopeContentPart.text(tb.getText()));
            } else if (block instanceof ImageBlock ib) {
                try {
                    parts.add(DashScopeContentPart.image(convertImageToUrl(ib)));
                } catch (Exception e) {
                    log.warn("Failed to convert ImageBlock: {}", e.getMessage());
                }
            } else if (block instanceof VideoBlock vb) {
                try {
                    parts.add(DashScopeContentPart.video(convertVideoToUrl(vb)));
                } catch (Exception e) {
                    log.warn("Failed to convert VideoBlock: {}", e.getMessage());
                }
            } else if (block instanceof AudioBlock ab) {
                try {
                    parts.add(DashScopeContentPart.audio(convertAudioToUrl(ab)));
                } catch (Exception e) {
                    log.warn("Failed to convert AudioBlock: {}", e.getMessage());
                }
            } else if (block instanceof ThinkingBlock) {
                // Skip thinking blocks in input
                log.debug("Skipping ThinkingBlock when formatting for DashScope");
            } else if (block instanceof ToolResultBlock tr) {
                String text = toolResultConverter.apply(tr.getOutput());
                if (!text.isEmpty()) {
                    parts.add(DashScopeContentPart.text(text));
                }
            }
        }

        return parts;
    }

    private String extractTextContent(Msg msg) {
        return msg.getContent().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }

    private List<DashScopeToolCall> convertToolCalls(List<ToolUseBlock> toolBlocks) {
        List<DashScopeToolCall> result = new ArrayList<>();
        for (ToolUseBlock toolUse : toolBlocks) {
            if (toolUse == null) continue;

            String argsJson;
            try {
                argsJson = objectMapper.writeValueAsString(toolUse.getInput());
            } catch (Exception e) {
                log.warn("Failed to serialize tool call arguments: {}", e.getMessage());
                argsJson = "{}";
            }

            DashScopeFunction function = DashScopeFunction.of(toolUse.getName(), argsJson);
            DashScopeToolCall toolCall =
                    DashScopeToolCall.builder()
                            .id(toolUse.getId())
                            .type("function")
                            .function(function)
                            .build();
            result.add(toolCall);
        }
        return result;
    }

    private List<DashScopeTool> convertToolSchemas(List<ToolSchema> tools) {
        List<DashScopeTool> result = new ArrayList<>();
        for (ToolSchema tool : tools) {
            Map<String, Object> parameters = new HashMap<>();
            if (tool.getParameters() != null) {
                parameters.putAll(tool.getParameters());
            }

            DashScopeToolFunction function =
                    DashScopeToolFunction.builder()
                            .name(tool.getName())
                            .description(tool.getDescription())
                            .parameters(parameters)
                            .build();

            result.add(DashScopeTool.function(function));
        }
        return result;
    }

    private Object convertToolChoice(ToolChoice toolChoice) {
        if (toolChoice instanceof ToolChoice.Auto) {
            return "auto";
        } else if (toolChoice instanceof ToolChoice.None) {
            return "none";
        } else if (toolChoice instanceof ToolChoice.Required) {
            log.warn("ToolChoice.Required not supported by DashScope, using 'auto'");
            return "auto";
        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            Map<String, Object> choice = new HashMap<>();
            choice.put("type", "function");
            Map<String, String> function = new HashMap<>();
            function.put("name", specific.toolName());
            choice.put("function", function);
            return choice;
        }
        return null;
    }

    private <T> T getOption(
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Function<GenerateOptions, T> getter) {
        if (options != null) {
            T value = getter.apply(options);
            if (value != null) {
                return value;
            }
        }
        if (defaultOptions != null) {
            return getter.apply(defaultOptions);
        }
        return null;
    }

    private String convertImageToUrl(ImageBlock imageBlock) throws Exception {
        Source source = imageBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateImageExtension(url);
            if (MediaUtils.isLocalFile(url)) {
                return MediaUtils.toFileProtocolUrl(url);
            }
            return url;
        } else if (source instanceof Base64Source base64Source) {
            return String.format(
                    "data:%s;base64,%s", base64Source.getMediaType(), base64Source.getData());
        }
        throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
    }

    private String convertVideoToUrl(VideoBlock videoBlock) throws Exception {
        Source source = videoBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateVideoExtension(url);
            if (MediaUtils.isLocalFile(url)) {
                return MediaUtils.toFileProtocolUrl(url);
            }
            return url;
        } else if (source instanceof Base64Source base64Source) {
            return String.format(
                    "data:%s;base64,%s", base64Source.getMediaType(), base64Source.getData());
        }
        throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
    }

    private String convertAudioToUrl(AudioBlock audioBlock) throws Exception {
        Source source = audioBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            if (MediaUtils.isLocalFile(url)) {
                return MediaUtils.toFileProtocolUrl(url);
            }
            return url;
        } else if (source instanceof Base64Source base64Source) {
            return String.format(
                    "data:%s;base64,%s", base64Source.getMediaType(), base64Source.getData());
        }
        throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
    }
}
