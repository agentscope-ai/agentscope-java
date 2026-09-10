/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openaiofficial;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.util.JsonUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses a non-streaming Responses API {@link Response} into a single {@link ChatResponse}.
 *
 * <p>Traverses {@code response.output()} once, bucketing items by type (reasoning, message,
 * function_call, unknown). Assembles content blocks in fixed order: ThinkingBlock
 * -> TextBlock -> ToolUseBlock. Refusal content is checked first and short-circuits to a
 * non-retryable exception.
 */
final class ResponsesResponseParser {

    private ResponsesResponseParser() {}

    private static final Logger log = LoggerFactory.getLogger(ResponsesResponseParser.class);

    static ChatResponse parse(Response response, String modelName, Instant startTime) {
        // ── Extraction: traverse output once, bucket by type ──
        StringBuilder summaryBuilder = new StringBuilder();
        String encryptedContent = null;
        StringBuilder reasoningTextBuilder = new StringBuilder();
        StringBuilder textBuilder = new StringBuilder();
        List<ToolUseBlock> toolUseBlocks = new ArrayList<>();

        List<ResponseOutputItem> output = response.output();
        for (ResponseOutputItem item : output) {
            if (item.isReasoning()) {
                ResponseReasoningItem reasoning = item.asReasoning();
                extractReasoning(reasoning, summaryBuilder, reasoningTextBuilder);
                if (encryptedContent == null) {
                    Optional<String> ec = reasoning.encryptedContent();
                    if (ec.isPresent() && !ec.get().isEmpty()) {
                        encryptedContent = ec.get();
                    }
                }
            } else if (item.isMessage()) {
                extractMessage(item.asMessage(), textBuilder, modelName);
            } else if (item.isFunctionCall()) {
                toolUseBlocks.add(extractFunctionCall(item.asFunctionCall()));
            }
            // Unknown output item types are silently ignored (forward compatibility)
        }

        // ── Assembly: ThinkingBlock -> TextBlock -> ToolUseBlock ──
        List<ContentBlock> contentBlocks = new ArrayList<>();

        // ThinkingBlock (when summary text is present)
        String summaryText = summaryBuilder.toString();
        if (!summaryText.isEmpty()) {
            contentBlocks.add(ThinkingBlock.builder().thinking(summaryText).build());
        }

        // TextBlock
        String text = textBuilder.toString();
        if (!text.isEmpty()) {
            contentBlocks.add(TextBlock.builder().text(text).build());
        }

        // ToolUseBlocks
        contentBlocks.addAll(toolUseBlocks);

        // ── Metadata + usage + finishReason ──
        String responseId = response.id();
        Map<String, Object> metadata = ResponsesHelper.extractResponseMetadata(response);
        String finishReason = (String) metadata.get(OpenAIOfficialConstants.MD_RESPONSE_STATUS);
        ChatUsage usage = ResponsesHelper.extractUsage(response, startTime, metadata);

        // Reasoning metadata
        if (encryptedContent != null) {
            metadata.put(OpenAIOfficialConstants.MD_REASONING_ENCRYPTED_CONTENT, encryptedContent);
        }
        if (!summaryText.isEmpty()) {
            metadata.put(OpenAIOfficialConstants.MD_REASONING_SUMMARY, summaryText);
        }
        String reasoningText = reasoningTextBuilder.toString();
        if (!reasoningText.isEmpty()) {
            metadata.put(OpenAIOfficialConstants.MD_REASONING_TEXT, reasoningText);
        }

        return ChatResponse.builder()
                .id(responseId)
                .content(contentBlocks)
                .usage(usage)
                .metadata(metadata)
                .finishReason(finishReason)
                .build();
    }

    private static void extractReasoning(
            ResponseReasoningItem reasoning,
            StringBuilder summaryBuilder,
            StringBuilder reasoningTextBuilder) {
        // Summary parts: concatenate all .text() for each summary item
        for (ResponseReasoningItem.Summary summary : reasoning.summary()) {
            summaryBuilder.append(summary.text());
        }
        // Reasoning text content: concatenate all .text() for each content item
        Optional<List<ResponseReasoningItem.Content>> contentOpt = reasoning.content();
        if (contentOpt.isPresent()) {
            for (ResponseReasoningItem.Content content : contentOpt.get()) {
                reasoningTextBuilder.append(content.text());
            }
        }
    }

    private static void extractMessage(
            ResponseOutputMessage message, StringBuilder textBuilder, String modelName) {
        for (ResponseOutputMessage.Content content : message.content()) {
            if (content.isOutputText()) {
                textBuilder.append(content.asOutputText().text());
            } else if (content.isRefusal()) {
                throw new OpenAIOfficialModelException(
                        "Model response was refused: " + content.asRefusal().refusal(),
                        null,
                        modelName);
            }
            // Unknown content part types are silently ignored
        }
    }

    private static ToolUseBlock extractFunctionCall(ResponseFunctionToolCall call) {
        String callId = call.callId();
        String name = call.name();
        String arguments = call.arguments();

        Map<String, Object> input;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JsonUtils.getJsonCodec().fromJson(arguments, Map.class);
            input = parsed != null ? parsed : new HashMap<>();
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to parse tool call arguments as JSON; preserving raw arguments:"
                            + " callId={}, name={}, error={}",
                    callId,
                    name,
                    e.getMessage());
            input = new HashMap<>();
        }

        return ToolUseBlock.builder().id(callId).name(name).input(input).content(arguments).build();
    }
}
