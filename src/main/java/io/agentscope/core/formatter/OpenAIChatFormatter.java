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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formatter for OpenAI Chat Completion API.
 * Converts between AgentScope Msg objects and OpenAI SDK types.
 *
 * <p>Note: OpenAI has two response types (ChatCompletion for non-streaming and ChatCompletionChunk
 * for streaming), so this formatter provides specific methods for each type.
 */
public class OpenAIChatFormatter extends AbstractOpenAIFormatter {

    private static final Logger log = LoggerFactory.getLogger(OpenAIChatFormatter.class);

    @Override
    public List<ChatCompletionMessageParam> format(List<Msg> msgs) {
        List<ChatCompletionMessageParam> result = new ArrayList<>();
        for (Msg msg : msgs) {
            ChatCompletionMessageParam param = convertToParam(msg);
            if (param != null) {
                result.add(param);
            }
        }
        return result;
    }

    private ChatCompletionMessageParam convertToParam(Msg msg) {
        return switch (msg.getRole()) {
            case SYSTEM -> ChatCompletionMessageParam.ofSystem(convertSystemMessage(msg));
            case USER -> ChatCompletionMessageParam.ofUser(convertUserMessage(msg));
            case ASSISTANT -> ChatCompletionMessageParam.ofAssistant(convertAssistantMessage(msg));
            case TOOL -> ChatCompletionMessageParam.ofTool(convertToolMessage(msg));
        };
    }

    private ChatCompletionSystemMessageParam convertSystemMessage(Msg msg) {
        return ChatCompletionSystemMessageParam.builder().content(extractTextContent(msg)).build();
    }

    private ChatCompletionUserMessageParam convertUserMessage(Msg msg) {
        ChatCompletionUserMessageParam.Builder builder = ChatCompletionUserMessageParam.builder();

        if (msg.getName() != null) {
            builder.name(msg.getName());
        }

        List<ContentBlock> blocks = msg.getContent();
        boolean hasMedia = hasMediaContent(msg);

        // Optimization: pure text fast path
        if (!hasMedia && blocks.size() == 1 && blocks.get(0) instanceof TextBlock) {
            builder.content(((TextBlock) blocks.get(0)).getText());
            return builder.build();
        }

        // Multi-modal path: build ContentPart list
        List<ChatCompletionContentPart> contentParts = new ArrayList<>();

        for (ContentBlock block : blocks) {
            switch (block.getType()) {
                case TEXT -> {
                    TextBlock tb = (TextBlock) block;
                    contentParts.add(
                            ChatCompletionContentPart.ofText(
                                    ChatCompletionContentPartText.builder()
                                            .text(tb.getText())
                                            .build()));
                }
                case IMAGE -> {
                    try {
                        ImageBlock ib = (ImageBlock) block;
                        contentParts.add(convertImageBlockToContentPart(ib));
                    } catch (Exception e) {
                        log.warn("Failed to process ImageBlock: {}", e.getMessage());
                        contentParts.add(
                                createErrorTextPart(
                                        "[Image - processing failed: " + e.getMessage() + "]"));
                    }
                }
                case AUDIO -> {
                    try {
                        AudioBlock ab = (AudioBlock) block;
                        contentParts.add(convertAudioBlockToContentPart(ab));
                    } catch (Exception e) {
                        log.warn("Failed to process AudioBlock: {}", e.getMessage());
                        contentParts.add(
                                createErrorTextPart(
                                        "[Audio - processing failed: " + e.getMessage() + "]"));
                    }
                }
                case THINKING -> {
                    log.debug("Skipping ThinkingBlock when formatting for OpenAI");
                }
                default -> {
                    log.warn("Unsupported block type: {}", block.getType());
                }
            }
        }

        if (!contentParts.isEmpty()) {
            builder.contentOfArrayOfContentParts(contentParts);
        }

        return builder.build();
    }

    private ChatCompletionAssistantMessageParam convertAssistantMessage(Msg msg) {
        ChatCompletionAssistantMessageParam.Builder builder =
                ChatCompletionAssistantMessageParam.builder();

        String textContent = extractTextContent(msg);
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

    private ChatCompletionToolMessageParam convertToolMessage(Msg msg) {
        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        String toolCallId =
                result != null ? result.getId() : "unknown_" + System.currentTimeMillis();

        // Use convertToolResultToString to handle multimodal content
        String content =
                result != null
                        ? convertToolResultToString(result.getOutput())
                        : extractTextContent(msg);

        return ChatCompletionToolMessageParam.builder()
                .content(content)
                .toolCallId(toolCallId)
                .build();
    }

    @Override
    public FormatterCapabilities getCapabilities() {
        return FormatterCapabilities.builder()
                .providerName("OpenAI")
                .supportToolsApi(true)
                .supportMultiAgent(false)
                .supportVision(true)
                .supportedBlocks(
                        Set.of(
                                TextBlock.class,
                                ToolUseBlock.class,
                                ToolResultBlock.class,
                                ThinkingBlock.class,
                                ImageBlock.class,
                                AudioBlock.class))
                .build();
    }
}
