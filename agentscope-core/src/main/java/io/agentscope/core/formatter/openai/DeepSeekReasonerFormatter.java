/*
 * Copyright 2024-2026 the original author or authors.
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

import io.agentscope.core.formatter.openai.dto.OpenAIContentPart;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formatter for DeepSeek Reasoner models (deepseek-reasoner, deepseek-r1).
 *
 * <p>DeepSeek R1 is a reasoning model with fixed sampling parameters.
 * This formatter extends {@link DeepSeekFormatter} and:
 * <ul>
 *   <li>Does NOT apply temperature, top_p, frequency_penalty, presence_penalty</li>
 *   <li>Applies max_tokens with a safe default (4000)</li>
 *   <li>Removes reasoning_content from request messages</li>
 *   <li>Removes name field from messages</li>
 *   <li>Converts system messages to user messages</li>
 *   <li>Does NOT support strict parameter in tool definitions</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new DeepSeekReasonerFormatter())
 *     .modelName("deepseek-reasoner")
 *     .baseUrl("https://api.deepseek.com/v1")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 */
public class DeepSeekReasonerFormatter extends DeepSeekFormatter {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekReasonerFormatter.class);

    /** Default max tokens for DeepSeek R1 (limit is 4096) */
    private static final int DEFAULT_MAX_TOKENS = 4000;

    public DeepSeekReasonerFormatter() {
        super();
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        // First, use parent's formatting (includes DeepSeek fixes)
        List<OpenAIMessage> messages = super.doFormat(msgs);

        // Then remove reasoning_content
        return removeReasoningContent(messages);
    }

    /**
     * Remove reasoning_content from messages.
     *
     * <p>DeepSeek R1 API does not accept reasoning_content in request messages.
     */
    private List<OpenAIMessage> removeReasoningContent(List<OpenAIMessage> messages) {
        boolean hasReasoningContent = false;
        for (OpenAIMessage msg : messages) {
            if (msg.getReasoningContent() != null) {
                hasReasoningContent = true;
                break;
            }
        }

        if (!hasReasoningContent) {
            return messages;
        }

        log.debug("DeepSeek Reasoner: removing reasoning_content from messages");
        List<OpenAIMessage> adjustedMessages = new ArrayList<>();

        for (OpenAIMessage msg : messages) {
            // Build new message without reasoning_content
            OpenAIMessage.Builder builder = OpenAIMessage.builder().role(msg.getRole());

            // Handle content (could be String or List)
            Object content = msg.getContent();
            if (content instanceof String) {
                builder.content((String) content);
            } else if (content instanceof List) {
                @SuppressWarnings("unchecked")
                List<OpenAIContentPart> contentParts = (List<OpenAIContentPart>) content;
                builder.content(contentParts);
            }

            // Copy other fields but not reasoning_content
            if (msg.getToolCalls() != null) {
                builder.toolCalls(msg.getToolCalls());
            }
            if (msg.getToolCallId() != null) {
                builder.toolCallId(msg.getToolCallId());
            }

            adjustedMessages.add(builder.build());
        }

        return adjustedMessages;
    }

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {

        // DeepSeek R1 does NOT support temperature, top_p, frequency_penalty, presence_penalty
        // Only apply max_tokens and seed

        // Apply max tokens with default
        Integer maxTokens =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) {
            request.setMaxTokens(maxTokens);
        } else {
            // Reasoning models require max_tokens to be set
            request.setMaxTokens(DEFAULT_MAX_TOKENS);
        }

        // Apply seed
        Long seed = getOptionOrDefault(options, defaultOptions, GenerateOptions::getSeed);
        if (seed != null) {
            if (seed < Integer.MIN_VALUE || seed > Integer.MAX_VALUE) {
                log.warn("Seed value {} is out of Integer range, will be truncated", seed);
            }
            request.setSeed(seed.intValue());
        }

        // Apply additional body params (must be last to allow overriding)
        applyAdditionalBodyParams(request, defaultOptions);
        applyAdditionalBodyParams(request, options);
    }
}
