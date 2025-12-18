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
package io.agentscope.core.formatter.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.formatter.openai.dto.OpenAIContentPart;
import io.agentscope.core.formatter.openai.dto.OpenAIFunction;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIToolCall;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts AgentScope Msg objects to OpenAI DTO types (for HTTP API).
 *
 * <p>This class handles all message role conversions including system, user, assistant, and tool
 * messages. It supports multimodal content (text, images, audio) and tool calling functionality.
 */
public class OpenAIMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(OpenAIMessageConverter.class);

    private final ObjectMapper objectMapper;
    private final Function<Msg, String> textExtractor;
    private final Function<List<ContentBlock>, String> toolResultConverter;

    /**
     * Create an OpenAIMessageConverter with required dependency functions.
     *
     * @param textExtractor Function to extract text content from Msg
     * @param toolResultConverter Function to convert tool result blocks to strings
     */
    public OpenAIMessageConverter(
            Function<Msg, String> textExtractor,
            Function<List<ContentBlock>, String> toolResultConverter) {
        this.objectMapper = new ObjectMapper();
        this.textExtractor = textExtractor;
        this.toolResultConverter = toolResultConverter;
    }

    /**
     * Convert single Msg to OpenAI DTO Message.
     *
     * @param msg The message to convert
     * @param hasMediaContent Whether the message contains media (images/audio)
     * @return OpenAIMessage for OpenAI API
     */
    public OpenAIMessage convertToMessage(Msg msg, boolean hasMediaContent) {
        // Check if SYSTEM message contains tool result - treat as TOOL role
        if (msg.getRole() == MsgRole.SYSTEM && msg.hasContentBlocks(ToolResultBlock.class)) {
            return convertToolMessage(msg);
        }

        return switch (msg.getRole()) {
            case SYSTEM -> convertSystemMessage(msg);
            case USER -> convertUserMessage(msg, hasMediaContent);
            case ASSISTANT -> convertAssistantMessage(msg);
            case TOOL -> convertToolMessage(msg);
        };
    }

    /**
     * Convert system message.
     *
     * @param msg The system message
     * @return OpenAIMessage
     */
    private OpenAIMessage convertSystemMessage(Msg msg) {
        String content = textExtractor.apply(msg);
        return OpenAIMessage.builder()
                .role("system")
                .content(content != null ? content : "")
                .build();
    }

    /**
     * Convert user message with support for multimodal content.
     *
     * @param msg The user message
     * @param hasMediaContent Whether the message contains media
     * @return OpenAIMessage
     */
    private OpenAIMessage convertUserMessage(Msg msg, boolean hasMediaContent) {
        OpenAIMessage.Builder builder = OpenAIMessage.builder().role("user");

        if (msg.getName() != null) {
            builder.name(msg.getName());
        }

        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null) {
            blocks = new ArrayList<>();
        }

        // Optimization: pure text fast path
        if (!hasMediaContent
                && !blocks.isEmpty()
                && blocks.size() == 1
                && blocks.get(0) instanceof TextBlock) {
            builder.content(((TextBlock) blocks.get(0)).getText());
            return builder.build();
        }

        // Multi-modal path: build ContentPart list
        List<OpenAIContentPart> contentParts = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                contentParts.add(OpenAIContentPart.text(tb.getText()));
            } else if (block instanceof ImageBlock ib) {
                try {
                    Source source = ib.getSource();
                    if (source == null) {
                        log.warn("ImageBlock has null source, skipping");
                        continue;
                    }
                    String imageUrl = convertImageSourceToUrl(source);
                    contentParts.add(OpenAIContentPart.imageUrl(imageUrl));
                } catch (Exception e) {
                    String errorMsg =
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Failed to process ImageBlock: {}", errorMsg);
                    contentParts.add(
                            OpenAIContentPart.text(
                                    "[Image - processing failed: " + errorMsg + "]"));
                }
            } else if (block instanceof AudioBlock ab) {
                try {
                    // OpenAI expects base64 audio in input_audio format
                    Source source = ab.getSource();
                    if (source == null) {
                        log.warn("AudioBlock has null source, using placeholder");
                        contentParts.add(OpenAIContentPart.text("[Audio - source missing]"));
                        continue;
                    }
                    if (source instanceof Base64Source b64) {
                        String audioData = b64.getData();
                        if (audioData == null || audioData.isEmpty()) {
                            log.warn("Base64Source has null or empty data, using placeholder");
                            contentParts.add(OpenAIContentPart.text("[Audio - data missing]"));
                            continue;
                        }
                        String mediaType = b64.getMediaType();
                        String format = mediaType != null ? detectAudioFormat(mediaType) : "wav";
                        if (format == null) {
                            log.debug("Audio format detection returned null, defaulting to wav");
                            format = "wav";
                        }
                        contentParts.add(OpenAIContentPart.inputAudio(audioData, format));
                    } else if (source instanceof URLSource urlSource) {
                        // For URL-based audio, we need to add as text since OpenAI
                        // input_audio requires base64
                        String url = urlSource.getUrl();
                        if (url == null || url.isEmpty()) {
                            log.warn("URLSource has null or empty URL, using placeholder");
                            contentParts.add(OpenAIContentPart.text("[Audio URL - missing]"));
                            continue;
                        }
                        log.warn("URL-based audio not directly supported, using text reference");
                        contentParts.add(OpenAIContentPart.text("[Audio URL: " + url + "]"));
                    } else {
                        log.warn(
                                "Unknown audio source type: {}", source.getClass().getSimpleName());
                        contentParts.add(
                                OpenAIContentPart.text("[Audio - unsupported source type]"));
                    }
                } catch (Exception e) {
                    String errorMsg =
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Failed to process AudioBlock: {}", errorMsg, e);
                    contentParts.add(
                            OpenAIContentPart.text(
                                    "[Audio - processing failed: " + errorMsg + "]"));
                }
            } else if (block instanceof ThinkingBlock) {
                log.debug("Skipping ThinkingBlock when formatting for OpenAI");
            } else if (block instanceof VideoBlock) {
                log.warn("VideoBlock is not supported by OpenAI ChatCompletion API");
            } else if (block instanceof ToolUseBlock) {
                log.warn("ToolUseBlock is not supported in ChatCompletion user messages");
            } else if (block instanceof ToolResultBlock) {
                log.warn("ToolResultBlock is not supported in ChatCompletion user messages");
            }
        }

        if (!contentParts.isEmpty()) {
            builder.content(contentParts);
        } else {
            // 如果所有content blocks都被跳过，至少添加一个空文本内容
            // 避免发送null content给OpenAI API
            builder.content("");
        }

        return builder.build();
    }

    /**
     * Convert assistant message with support for tool calls.
     *
     * @param msg The assistant message
     * @return OpenAIMessage
     */
    private OpenAIMessage convertAssistantMessage(Msg msg) {
        OpenAIMessage.Builder builder = OpenAIMessage.builder().role("assistant");

        String textContent = textExtractor.apply(msg);
        if (textContent != null && !textContent.isEmpty()) {
            builder.content(textContent);
        }

        if (msg.getName() != null) {
            builder.name(msg.getName());
        }

        // Handle tool calls
        List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
        if (!toolBlocks.isEmpty()) {
            List<OpenAIToolCall> toolCalls = new ArrayList<>();
            for (ToolUseBlock toolUse : toolBlocks) {
                String toolId = toolUse.getId();
                String toolName = toolUse.getName();
                if (toolId == null || toolName == null) {
                    log.warn("ToolUseBlock has null id or name, skipping");
                    continue;
                }

                String argsJson;
                try {
                    argsJson = objectMapper.writeValueAsString(toolUse.getInput());
                } catch (Exception e) {
                    String errorMsg =
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Failed to serialize tool call arguments: {}", errorMsg);
                    argsJson = "{}";
                }

                OpenAIToolCall toolCall =
                        OpenAIToolCall.builder()
                                .id(toolId)
                                .type("function")
                                .function(OpenAIFunction.of(toolName, argsJson))
                                .build();
                toolCalls.add(toolCall);

                log.debug("Formatted assistant tool call: id={}, name={}", toolId, toolName);
            }
            builder.toolCalls(toolCalls);
        }

        return builder.build();
    }

    /**
     * Convert tool result message.
     *
     * @param msg The tool result message
     * @return OpenAIMessage
     */
    private OpenAIMessage convertToolMessage(Msg msg) {
        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        String toolCallId =
                result != null && result.getId() != null
                        ? result.getId()
                        : "tool_call_" + System.currentTimeMillis();

        // Use provided converter to handle multimodal content in tool results
        String content;
        if (result != null) {
            content = toolResultConverter.apply(result.getOutput());
        } else {
            content = textExtractor.apply(msg);
        }
        if (content == null) {
            content = "";
        }

        return OpenAIMessage.builder().role("tool").content(content).toolCallId(toolCallId).build();
    }

    /**
     * Convert image Source to URL string for OpenAI API.
     *
     * @param source The Source to convert
     * @return URL string (either a URL or base64 data URI)
     * @throws IllegalArgumentException if source is null or of unknown type
     */
    private String convertImageSourceToUrl(Source source) {
        return OpenAIConverterUtils.convertImageSourceToUrl(source);
    }

    /**
     * Detect audio format from media type.
     *
     * @param mediaType The media type (e.g., "audio/wav")
     * @return The format string (e.g., "wav")
     */
    private String detectAudioFormat(String mediaType) {
        return OpenAIConverterUtils.detectAudioFormat(mediaType);
    }
}
