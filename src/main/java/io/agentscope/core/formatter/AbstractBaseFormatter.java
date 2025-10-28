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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.GenerateOptions;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base formatter providing common functionality for all formatter implementations.
 *
 * <p>This class contains shared logic across all formatters including:
 * <ul>
 *   <li>Text content extraction (with ThinkingBlock filtering)
 *   <li>Media content detection
 *   <li>Role label formatting
 *   <li>Shared ObjectMapper instance
 * </ul>
 *
 * @param <TReq> Provider-specific request message type
 * @param <TResp> Provider-specific response type
 * @param <TParams> Provider-specific request parameters builder type
 */
public abstract class AbstractBaseFormatter<TReq, TResp, TParams>
        implements Formatter<TReq, TResp, TParams> {

    private static final Logger log = LoggerFactory.getLogger(AbstractBaseFormatter.class);

    /** Shared ObjectMapper instance for JSON serialization/deserialization. */
    protected final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Extract text content from a message, filtering out ThinkingBlock.
     *
     * <p><b>Important:</b> ThinkingBlock is NOT sent back to LLM APIs
     * (matching Python implementation behavior). ThinkingBlock is stored in memory
     * but skipped when formatting messages.
     *
     * @param msg The message to extract text from
     * @return Concatenated text content
     */
    protected String extractTextContent(Msg msg) {
        return msg.getContent().stream()
                .flatMap(
                        block -> {
                            if (block instanceof TextBlock tb) {
                                return Stream.of(tb.getText());
                            } else if (block instanceof ThinkingBlock) {
                                // IMPORTANT: ThinkingBlock is NOT sent back to LLM APIs
                                // (matching Python implementation behavior)
                                // ThinkingBlock is stored in memory but skipped when formatting
                                // messages
                                log.debug(
                                        "Skipping ThinkingBlock when formatting message for LLM"
                                                + " API");
                                return Stream.empty();
                            } else if (block instanceof ToolResultBlock toolResult) {
                                // Extract text from tool result output
                                return toolResult.getOutput().stream()
                                        .filter(output -> output instanceof TextBlock)
                                        .map(output -> ((TextBlock) output).getText());
                            }
                            return Stream.empty();
                        })
                .collect(Collectors.joining("\n"));
    }

    /**
     * Extract text content from a single ContentBlock.
     *
     * @param block The content block
     * @return Text content or empty string
     */
    protected String extractTextContent(ContentBlock block) {
        if (block instanceof TextBlock tb) {
            return tb.getText();
        }
        return "";
    }

    /**
     * Check if a message contains multimodal content (images, audio, video).
     *
     * @param msg The message to check
     * @return true if message contains media content
     */
    protected boolean hasMediaContent(Msg msg) {
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ImageBlock
                    || block instanceof AudioBlock
                    || block instanceof VideoBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * Format role label for conversation history.
     *
     * @param role The message role
     * @return Formatted role label (e.g., "User", "Assistant")
     */
    protected String formatRoleLabel(MsgRole role) {
        return switch (role) {
            case USER -> "User";
            case ASSISTANT -> "Assistant";
            case SYSTEM -> "System";
            case TOOL -> "Tool";
        };
    }

    /**
     * Get an option value from options or fall back to defaultOptions.
     *
     * <p>This helper method reduces boilerplate when applying generation options.
     * It first checks if the value is present in {@code options}, then falls back
     * to {@code defaultOptions}, and finally returns null if neither has a value.
     *
     * @param <T> The type of the option value
     * @param options The primary options object
     * @param defaultOptions The fallback options object
     * @param getter Function to extract the value from GenerateOptions
     * @return The option value or null if not found in either options object
     */
    protected <T> T getOptionOrDefault(
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Function<GenerateOptions, T> getter) {
        T value = options != null ? getter.apply(options) : null;
        return value != null
                ? value
                : (defaultOptions != null ? getter.apply(defaultOptions) : null);
    }
}
