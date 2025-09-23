/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.FormattedMessageList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Abstract base class for formatters that support token counting and message truncation.
 *
 * This class extends FormatterBase with additional capabilities for:
 * - Token counting and management
 * - Message truncation when exceeding token limits
 * - Structured message formatting patterns
 *
 * Follows the Python agentscope TruncatedFormatterBase pattern.
 */
public abstract class TruncatedFormatterBase extends FormatterBase {

    protected final TokenCounter tokenCounter;
    protected final Integer maxTokens;

    /**
     * Constructor with optional token counter and max tokens limit.
     *
     * @param tokenCounter Token counter implementation (null to disable counting)
     * @param maxTokens Maximum tokens allowed (null for no limit)
     */
    public TruncatedFormatterBase(TokenCounter tokenCounter, Integer maxTokens) {
        this.tokenCounter = tokenCounter;
        this.maxTokens = maxTokens;
    }

    /**
     * Constructor without token limits.
     */
    public TruncatedFormatterBase() {
        this(null, null);
    }

    @Override
    public Mono<FormattedMessageList> format(List<Msg> msgs) {
        return format(msgs, new FormatterOptions());
    }

    @Override
    public Mono<FormattedMessageList> format(List<Msg> msgs, FormatterOptions options) {
        assertListOfMsgs(msgs);
        return Mono.fromSupplier(
                () -> {
                    List<Msg> messagesToFormat = new ArrayList<>(msgs);
                    if (tokenCounter != null && maxTokens != null) {
                        messagesToFormat = applyTokenTruncation(messagesToFormat);
                    }
                    List<Map<String, Object>> rawMaps = formatInternal(messagesToFormat, options);
                    return new FormattedMessageList(rawMaps);
                });
    }

    /**
     * Internal formatting method to be implemented by concrete formatters.
     *
     * @param msgs List of messages to format (potentially truncated)
     * @param options Additional formatting parameters
     * @return List of formatted messages as maps
     */
    protected abstract List<java.util.Map<String, Object>> formatInternal(
            List<Msg> msgs, FormatterOptions options);

    /**
     * Format a system message.
     *
     * @param msg System message to format
     * @return Formatted system message
     */
    protected abstract Map<String, Object> formatSystemMessage(Msg msg);

    /**
     * Format a sequence of tool-related messages (tool use + tool results).
     *
     * @param msgs List of tool-related messages
     * @return List of formatted tool messages
     */
    protected abstract List<Map<String, Object>> formatToolSequence(List<Msg> msgs);

    /**
     * Format agent messages (user or assistant messages).
     *
     * @param msgs List of agent messages
     * @param isFirst Whether this is the first agent message group
     * @return List of formatted agent messages
     */
    protected abstract List<Map<String, Object>> formatAgentMessage(
            List<Msg> msgs, boolean isFirst);

    /**
     * Apply token truncation to messages if token limits are exceeded.
     *
     * @param msgs Original list of messages
     * @return Truncated list of messages within token limits
     */
    protected List<Msg> applyTokenTruncation(List<Msg> msgs) {
        List<Msg> result = new ArrayList<>(msgs);

        while (!result.isEmpty()) {
            // Format messages to count tokens
            List<java.util.Map<String, Object>> formatted =
                    formatInternal(result, new FormatterOptions());
            int tokenCount = tokenCounter.countTokens(formatted);

            if (tokenCount <= maxTokens) {
                break; // Within limit
            }

            // Remove oldest non-system message
            result = truncateMessages(result);
        }

        return result;
    }

    /**
     * Truncate messages by removing the oldest non-system messages.
     *
     * @param msgs List of messages to truncate
     * @return Truncated list of messages
     */
    protected List<Msg> truncateMessages(List<Msg> msgs) {
        List<Msg> result = new ArrayList<>();

        // Always keep system messages
        List<Msg> systemMessages =
                msgs.stream().filter(msg -> msg.getRole() == MsgRole.SYSTEM).toList();
        result.addAll(systemMessages);

        // Keep only the most recent non-system messages
        List<Msg> nonSystemMessages =
                msgs.stream().filter(msg -> msg.getRole() != MsgRole.SYSTEM).toList();

        if (nonSystemMessages.size() > 1) {
            // Remove the oldest non-system message
            result.addAll(nonSystemMessages.subList(1, nonSystemMessages.size()));
        }

        return result;
    }

    /**
     * Get the maximum token limit.
     *
     * @return Maximum tokens (null if no limit)
     */
    public Integer getMaxTokens() {
        return maxTokens;
    }

    /**
     * Check if this formatter has token counting enabled.
     *
     * @return true if token counting is enabled
     */
    public boolean hasTokenCounting() {
        return tokenCounter != null;
    }
}
