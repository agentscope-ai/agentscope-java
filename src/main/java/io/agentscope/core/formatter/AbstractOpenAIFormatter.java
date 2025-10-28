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

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartInputAudio;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
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
 * Abstract base formatter for OpenAI API formatters.
 *
 * <p>This class contains shared logic for OpenAI formatters including:
 * <ul>
 *   <li>Response parsing (parseCompletionResponse, parseChunkResponse)
 *   <li>Options application (applyOptions)
 *   <li>Tools application (applyTools)
 *   <li>Media content conversion (convertImageBlockToContentPart, convertAudioBlockToContentPart)
 * </ul>
 */
public abstract class AbstractOpenAIFormatter
        extends AbstractBaseFormatter<
                ChatCompletionMessageParam, Object, ChatCompletionCreateParams.Builder> {

    private static final Logger log = LoggerFactory.getLogger(AbstractOpenAIFormatter.class);

    /** Placeholder name for tool call argument fragments in streaming responses. */
    protected static final String FRAGMENT_PLACEHOLDER = "__fragment__";

    @Override
    public ChatResponse parseResponse(Object response, Instant startTime) {
        // Dispatch to the appropriate parsing method based on actual type
        if (response instanceof ChatCompletion completion) {
            return parseCompletionResponse(completion, startTime);
        } else if (response instanceof ChatCompletionChunk chunk) {
            return parseChunkResponse(chunk, startTime);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported response type: " + response.getClass().getName());
        }
    }

    /**
     * Parse OpenAI non-streaming response.
     *
     * @param completion ChatCompletion from OpenAI
     * @param startTime Request start time
     * @return AgentScope ChatResponse
     */
    protected ChatResponse parseCompletionResponse(ChatCompletion completion, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;

        try {
            // Parse usage information
            if (completion.usage().isPresent()) {
                var openAIUsage = completion.usage().get();
                usage =
                        ChatUsage.builder()
                                .inputTokens((int) openAIUsage.promptTokens())
                                .outputTokens((int) openAIUsage.completionTokens())
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();
            }

            // Parse response content
            if (!completion.choices().isEmpty()) {
                ChatCompletion.Choice choice = completion.choices().get(0);
                ChatCompletionMessage message = choice.message();

                // Parse text content
                if (message.content() != null && message.content().isPresent()) {
                    String textContent = message.content().get();
                    if (textContent != null && !textContent.isEmpty()) {
                        contentBlocks.add(TextBlock.builder().text(textContent).build());
                    }
                }

                // Parse tool calls
                if (message.toolCalls() != null && message.toolCalls().isPresent()) {
                    var toolCalls = message.toolCalls().get();
                    log.debug("Tool calls detected in non-stream response: {}", toolCalls.size());

                    for (var toolCall : toolCalls) {
                        if (toolCall.function().isPresent()) {
                            // Convert OpenAI tool call to AgentScope ToolUseBlock
                            try {
                                var functionToolCall = toolCall.function().get();
                                var function = functionToolCall.function();
                                String arguments = function.arguments();

                                log.debug(
                                        "Non-stream tool call: id={}, name={}, arguments={}",
                                        functionToolCall.id(),
                                        function.name(),
                                        arguments);

                                Map<String, Object> argsMap = new HashMap<>();
                                if (arguments != null && !arguments.isEmpty()) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> parsed =
                                            objectMapper.readValue(arguments, Map.class);
                                    if (parsed != null) argsMap.putAll(parsed);
                                }

                                contentBlocks.add(
                                        ToolUseBlock.builder()
                                                .id(functionToolCall.id())
                                                .name(function.name())
                                                .input(argsMap)
                                                .content(arguments)
                                                .build());

                                log.debug(
                                        "Parsed tool call: id={}, name={}",
                                        functionToolCall.id(),
                                        function.name());
                            } catch (Exception ex) {
                                log.warn(
                                        "Failed to parse tool call arguments: {}", ex.getMessage());
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse OpenAI completion response: {}", e.getMessage(), e);
            // Return fallback response with error message instead of throwing exception
            // This allows partial parsing and provides better error messages to users
            contentBlocks.add(
                    TextBlock.builder().text("Error parsing response: " + e.getMessage()).build());
        }

        return ChatResponse.builder()
                .id(completion.id())
                .content(contentBlocks)
                .usage(usage)
                .build();
    }

    /**
     * Parse OpenAI streaming response chunk.
     *
     * @param chunk ChatCompletionChunk from OpenAI
     * @param startTime Request start time
     * @return AgentScope ChatResponse
     */
    protected ChatResponse parseChunkResponse(ChatCompletionChunk chunk, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;

        try {
            // Parse usage information (usually only in the last chunk)
            if (chunk.usage().isPresent()) {
                var openAIUsage = chunk.usage().get();
                usage =
                        ChatUsage.builder()
                                .inputTokens((int) openAIUsage.promptTokens())
                                .outputTokens((int) openAIUsage.completionTokens())
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();
            }

            // Parse chunk content
            if (!chunk.choices().isEmpty()) {
                ChatCompletionChunk.Choice choice = chunk.choices().get(0);
                ChatCompletionChunk.Choice.Delta delta = choice.delta();

                // Parse text content
                if (delta.content() != null && delta.content().isPresent()) {
                    String textContent = delta.content().get();
                    if (textContent != null && !textContent.isEmpty()) {
                        contentBlocks.add(TextBlock.builder().text(textContent).build());
                    }
                }

                // Parse tool calls (in streaming, these come incrementally)
                if (delta.toolCalls() != null && delta.toolCalls().isPresent()) {
                    var toolCalls = delta.toolCalls().get();
                    log.debug("Streaming tool calls detected: {}", toolCalls.size());

                    for (var toolCall : toolCalls) {
                        if (toolCall.function().isPresent()) {
                            try {
                                var function = toolCall.function().get();
                                String toolCallId =
                                        toolCall.id()
                                                .orElse("streaming_" + System.currentTimeMillis());
                                String toolName = function.name().orElse("");
                                String arguments = function.arguments().orElse("");

                                log.debug(
                                        "Streaming tool call chunk: id={}, name={}, arguments={}",
                                        toolCallId,
                                        toolName,
                                        arguments);

                                // For streaming, we get partial tool calls that need to be
                                // accumulated
                                // Process when we have a tool name OR when we have argument
                                // fragments
                                if (!toolName.isEmpty()) {
                                    // First chunk with complete metadata (has tool name)
                                    Map<String, Object> argsMap = new HashMap<>();

                                    // Try to parse arguments only if they look complete
                                    // (simple heuristic: starts with { and ends with })
                                    if (!arguments.isEmpty()
                                            && arguments.trim().startsWith("{")
                                            && arguments.trim().endsWith("}")) {
                                        try {
                                            @SuppressWarnings("unchecked")
                                            Map<String, Object> parsed =
                                                    objectMapper.readValue(arguments, Map.class);
                                            if (parsed != null) argsMap.putAll(parsed);
                                        } catch (Exception parseEx) {
                                            log.debug(
                                                    "Partial arguments in streaming (expected): {}",
                                                    arguments.length() > 50
                                                            ? arguments.substring(0, 50) + "..."
                                                            : arguments);
                                            // Don't warn for partial JSON - this is normal in
                                            // streaming
                                        }
                                    } else if (!arguments.isEmpty()) {
                                        log.debug(
                                                "Partial tool arguments received: {}",
                                                arguments.length() > 30
                                                        ? arguments.substring(0, 30) + "..."
                                                        : arguments);
                                    }

                                    // Create ToolUseBlock even with partial arguments
                                    // The ReActAgent's ToolCallAccumulator will handle accumulation
                                    ToolUseBlock toolUseBlock =
                                            ToolUseBlock.builder()
                                                    .id(toolCallId)
                                                    .name(toolName)
                                                    .input(argsMap)
                                                    .content(arguments) // Store raw arguments for
                                                    // accumulation
                                                    .build();
                                    contentBlocks.add(toolUseBlock);
                                    log.debug(
                                            "Added streaming tool call chunk: id={}, name={},"
                                                    + " args_complete={}",
                                            toolCallId,
                                            toolName,
                                            !argsMap.isEmpty());
                                } else if (!arguments.isEmpty()) {
                                    // Subsequent chunks with only argument fragments (no name/ID)
                                    // Use placeholder values for accumulation by
                                    // ToolCallAccumulator
                                    contentBlocks.add(
                                            ToolUseBlock.builder()
                                                    .id(toolCallId)
                                                    .name(FRAGMENT_PLACEHOLDER) // Placeholder
                                                    // name for
                                                    // fragments
                                                    .input(new HashMap<>())
                                                    .content(arguments) // Store raw argument
                                                    // fragment
                                                    .build());
                                    log.debug(
                                            "Added argument fragment: id={}, fragment={}",
                                            toolCallId,
                                            arguments.length() > 30
                                                    ? arguments.substring(0, 30) + "..."
                                                    : arguments);
                                }
                            } catch (Exception ex) {
                                log.warn(
                                        "Failed to parse streaming tool call: {}", ex.getMessage());
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse OpenAI chunk response: {}", e.getMessage(), e);
            // For streaming, return null to skip malformed chunks without breaking the stream
            return null;
        }

        return ChatResponse.builder().id(chunk.id()).content(contentBlocks).usage(usage).build();
    }

    @Override
    public void applyOptions(
            ChatCompletionCreateParams.Builder paramsBuilder,
            GenerateOptions options,
            GenerateOptions defaultOptions) {
        // Apply each option individually, falling back to defaultOptions if the specific field is
        // null
        Double temperature =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getTemperature);
        if (temperature != null) paramsBuilder.temperature(temperature);

        Integer maxTokens =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) paramsBuilder.maxCompletionTokens(maxTokens.longValue());

        Double topP = getOptionOrDefault(options, defaultOptions, GenerateOptions::getTopP);
        if (topP != null) paramsBuilder.topP(topP);

        Double frequencyPenalty =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getFrequencyPenalty);
        if (frequencyPenalty != null) paramsBuilder.frequencyPenalty(frequencyPenalty);

        Double presencePenalty =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getPresencePenalty);
        if (presencePenalty != null) paramsBuilder.presencePenalty(presencePenalty);
    }

    @Override
    public void applyTools(
            ChatCompletionCreateParams.Builder paramsBuilder, List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }

        try {
            for (ToolSchema toolSchema : tools) {
                // Convert ToolSchema to OpenAI ChatCompletionTool
                // Create function definition first
                com.openai.models.FunctionDefinition.Builder functionBuilder =
                        com.openai.models.FunctionDefinition.builder().name(toolSchema.getName());

                if (toolSchema.getDescription() != null) {
                    functionBuilder.description(toolSchema.getDescription());
                }

                // Convert parameters map to proper format for OpenAI
                if (toolSchema.getParameters() != null) {
                    // Convert Map<String, Object> to FunctionParameters
                    com.openai.models.FunctionParameters.Builder funcParamsBuilder =
                            com.openai.models.FunctionParameters.builder();
                    for (Map.Entry<String, Object> entry : toolSchema.getParameters().entrySet()) {
                        funcParamsBuilder.putAdditionalProperty(
                                entry.getKey(), com.openai.core.JsonValue.from(entry.getValue()));
                    }
                    functionBuilder.parameters(funcParamsBuilder.build());
                }

                // Create ChatCompletionFunctionTool
                ChatCompletionFunctionTool functionTool =
                        ChatCompletionFunctionTool.builder()
                                .function(functionBuilder.build())
                                .build();

                // Create ChatCompletionTool
                ChatCompletionTool tool = ChatCompletionTool.ofFunction(functionTool);
                paramsBuilder.addTool(tool);

                log.debug("Added tool to OpenAI request: {}", toolSchema.getName());
            }

            // Set tool choice to auto to allow the model to decide when to use tools
            paramsBuilder.toolChoice(
                    ChatCompletionToolChoiceOption.ofAuto(
                            ChatCompletionToolChoiceOption.Auto.AUTO));

        } catch (Exception e) {
            log.error("Failed to add tools to OpenAI request: {}", e.getMessage(), e);
        }
    }

    /**
     * Convert ImageBlock to OpenAI ChatCompletionContentPart.
     * Handles both local files and remote URLs.
     */
    protected ChatCompletionContentPart convertImageBlockToContentPart(ImageBlock imageBlock)
            throws Exception {
        Source source = imageBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();

            // Validate extension
            MediaUtils.validateImageExtension(url);

            // For local files, convert to data URL
            if (MediaUtils.isLocalFile(url)) {
                String dataUrl = MediaUtils.urlToBase64DataUrl(url);
                return ChatCompletionContentPart.ofImageUrl(
                        ChatCompletionContentPartImage.builder()
                                .imageUrl(
                                        ChatCompletionContentPartImage.ImageUrl.builder()
                                                .url(dataUrl)
                                                .build())
                                .build());
            } else {
                // Remote URL - use directly
                return ChatCompletionContentPart.ofImageUrl(
                        ChatCompletionContentPartImage.builder()
                                .imageUrl(
                                        ChatCompletionContentPartImage.ImageUrl.builder()
                                                .url(url)
                                                .build())
                                .build());
            }
        } else if (source instanceof Base64Source base64Source) {
            // Base64 source - construct data URL
            String mediaType = base64Source.getMediaType();
            String base64Data = base64Source.getData();
            String dataUrl = String.format("data:%s;base64,%s", mediaType, base64Data);

            return ChatCompletionContentPart.ofImageUrl(
                    ChatCompletionContentPartImage.builder()
                            .imageUrl(
                                    ChatCompletionContentPartImage.ImageUrl.builder()
                                            .url(dataUrl)
                                            .build())
                            .build());
        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    /**
     * Convert AudioBlock to OpenAI ChatCompletionContentPart.
     * OpenAI requires base64 encoding for audio.
     * Downloads remote URLs if needed (matching Python implementation).
     */
    protected ChatCompletionContentPart convertAudioBlockToContentPart(AudioBlock audioBlock)
            throws Exception {
        Source source = audioBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();

            // Validate extension
            MediaUtils.validateAudioExtension(url);

            String base64Data;
            ChatCompletionContentPartInputAudio.InputAudio.Format format;

            if (MediaUtils.isLocalFile(url)) {
                // Local file - read and convert to base64
                base64Data = MediaUtils.fileToBase64(url);
                format = MediaUtils.determineAudioFormat(url);
            } else {
                // Remote URL - download and convert to base64 (matching Python)
                log.debug("Downloading remote audio URL for OpenAI: {}", url);
                base64Data = MediaUtils.downloadUrlToBase64(url);
                format = MediaUtils.determineAudioFormat(url);
            }

            return ChatCompletionContentPart.ofInputAudio(
                    ChatCompletionContentPartInputAudio.builder()
                            .inputAudio(
                                    ChatCompletionContentPartInputAudio.InputAudio.builder()
                                            .data(base64Data)
                                            .format(format)
                                            .build())
                            .build());
        } else if (source instanceof Base64Source base64Source) {
            // Base64 source - use directly
            String base64Data = base64Source.getData();
            String mediaType = base64Source.getMediaType();

            // Infer format from media type
            ChatCompletionContentPartInputAudio.InputAudio.Format format =
                    MediaUtils.inferAudioFormatFromMediaType(mediaType);

            return ChatCompletionContentPart.ofInputAudio(
                    ChatCompletionContentPartInputAudio.builder()
                            .inputAudio(
                                    ChatCompletionContentPartInputAudio.InputAudio.builder()
                                            .data(base64Data)
                                            .format(format)
                                            .build())
                            .build());
        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    /**
     * Create an error text ContentPart for fallback error messages.
     */
    protected ChatCompletionContentPart createErrorTextPart(String text) {
        return ChatCompletionContentPart.ofText(
                ChatCompletionContentPartText.builder().text(text).build());
    }
}
