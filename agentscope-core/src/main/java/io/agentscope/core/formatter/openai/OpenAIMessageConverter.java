/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.formatter.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.VideoBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts AgentScope Msg objects to OpenAI SDK ChatCompletionMessageParam types.
 *
 * <p>This class handles all message role conversions including system, user, assistant, and tool
 * messages. It supports multimodal content (text, images, audio) and tool calling functionality.
 */
public class OpenAIMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(OpenAIMessageConverter.class);

    private final OpenAIMediaConverter mediaConverter;
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
        this.mediaConverter = new OpenAIMediaConverter();
        this.objectMapper = new ObjectMapper();
        this.textExtractor = textExtractor;
        this.toolResultConverter = toolResultConverter;
    }

    /**
     * Convert single Msg to OpenAI ChatCompletionMessageParam.
     *
     * @param msg The message to convert
     * @param hasMediaContent Whether the message contains media (images/audio)
     * @return ChatCompletionMessageParam for OpenAI API
     */
    public ChatCompletionMessageParam convertToParam(Msg msg, boolean hasMediaContent) {
        // Check if SYSTEM message contains tool result - treat as TOOL role
        if (msg.getRole() == io.agentscope.core.message.MsgRole.SYSTEM
                && msg.hasContentBlocks(ToolResultBlock.class)) {
            return ChatCompletionMessageParam.ofTool(convertToolMessage(msg));
        }

        return switch (msg.getRole()) {
            case SYSTEM -> ChatCompletionMessageParam.ofSystem(convertSystemMessage(msg));
            case USER ->
                    ChatCompletionMessageParam.ofUser(convertUserMessage(msg, hasMediaContent));
            case ASSISTANT -> ChatCompletionMessageParam.ofAssistant(convertAssistantMessage(msg));
            case TOOL -> ChatCompletionMessageParam.ofTool(convertToolMessage(msg));
        };
    }

    /**
     * Convert system message.
     *
     * @param msg The system message
     * @return ChatCompletionSystemMessageParam
     */
    private ChatCompletionSystemMessageParam convertSystemMessage(Msg msg) {
        return ChatCompletionSystemMessageParam.builder().content(textExtractor.apply(msg)).build();
    }

    /**
     * Convert user message with support for multimodal content.
     *
     * @param msg The user message
     * @param hasMediaContent Whether the message contains media
     * @return ChatCompletionUserMessageParam
     */
    private ChatCompletionUserMessageParam convertUserMessage(Msg msg, boolean hasMediaContent) {
        ChatCompletionUserMessageParam.Builder builder = ChatCompletionUserMessageParam.builder();

        if (msg.getName() != null) {
            builder.name(msg.getName());
        }

        List<ContentBlock> blocks = msg.getContent();

        // Optimization: pure text fast path
        if (!hasMediaContent && blocks.size() == 1 && blocks.get(0) instanceof TextBlock) {
            builder.content(((TextBlock) blocks.get(0)).getText());
            return builder.build();
        }

        // Multi-modal path: build ContentPart list
        List<ChatCompletionContentPart> contentParts = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                contentParts.add(
                        ChatCompletionContentPart.ofText(
                                ChatCompletionContentPartText.builder()
                                        .text(tb.getText())
                                        .build()));
            } else if (block instanceof ImageBlock ib) {
                try {
                    contentParts.add(mediaConverter.convertImageBlockToContentPart(ib));
                } catch (Exception e) {
                    log.warn("Failed to process ImageBlock: {}", e.getMessage());
                    contentParts.add(
                            mediaConverter.createErrorTextPart(
                                    "[Image - processing failed: " + e.getMessage() + "]"));
                }
            } else if (block instanceof AudioBlock ab) {
                try {
                    contentParts.add(mediaConverter.convertAudioBlockToContentPart(ab));
                } catch (Exception e) {
                    log.warn("Failed to process AudioBlock: {}", e.getMessage());
                    contentParts.add(
                            mediaConverter.createErrorTextPart(
                                    "[Audio - processing failed: " + e.getMessage() + "]"));
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
            builder.contentOfArrayOfContentParts(contentParts);
        }

        return builder.build();
    }

    /**
     * Convert assistant message with support for tool calls.
     *
     * @param msg The assistant message
     * @return ChatCompletionAssistantMessageParam
     */
    private ChatCompletionAssistantMessageParam convertAssistantMessage(Msg msg) {
        ChatCompletionAssistantMessageParam.Builder builder =
                ChatCompletionAssistantMessageParam.builder();

        String textContent = textExtractor.apply(msg);
        if (!textContent.isEmpty()) {
            builder.content(textContent);
        }

        if (msg.getName() != null) {
            builder.name(msg.getName());
        }

        // Handle tool calls
        List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
        if (!toolBlocks.isEmpty()) {
            for (ToolUseBlock toolUse : toolBlocks) {
                String argsJson;
                try {
                    argsJson = objectMapper.writeValueAsString(toolUse.getInput());
                } catch (Exception e) {
                    log.warn("Failed to serialize tool call arguments: {}", e.getMessage());
                    argsJson = "{}";
                }

                var toolCallParam =
                        ChatCompletionMessageFunctionToolCall.builder()
                                .id(toolUse.getId())
                                .function(
                                        ChatCompletionMessageFunctionToolCall.Function.builder()
                                                .name(toolUse.getName())
                                                .arguments(argsJson)
                                                .build())
                                .build();

                builder.addToolCall(toolCallParam);
                log.debug(
                        "Formatted assistant tool call: id={}, name={}",
                        toolUse.getId(),
                        toolUse.getName());
            }
        }

        return builder.build();
    }

    /**
     * Convert tool result message.
     *
     * @param msg The tool result message
     * @return ChatCompletionToolMessageParam
     */
    private ChatCompletionToolMessageParam convertToolMessage(Msg msg) {
        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        String toolCallId =
                result != null ? result.getId() : "tool_call_" + System.currentTimeMillis();

        // Use provided converter to handle multimodal content in tool results
        String content =
                result != null
                        ? toolResultConverter.apply(result.getOutput())
                        : textExtractor.apply(msg);

        return ChatCompletionToolMessageParam.builder()
                .content(content)
                .toolCallId(toolCallId)
                .build();
    }
}
