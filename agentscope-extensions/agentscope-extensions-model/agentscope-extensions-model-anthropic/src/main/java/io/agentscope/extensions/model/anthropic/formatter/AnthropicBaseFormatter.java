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
package io.agentscope.extensions.model.anthropic.formatter;

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base formatter for Anthropic API with shared logic for handling Anthropic-specific
 * requirements.
 *
 * <p>This class handles:
 *
 * <ul>
 *   <li>System message extraction and application (Anthropic requires system via system parameter)
 *   <li>Tool choice configuration with GenerateOptions
 * </ul>
 */
public abstract class AnthropicBaseFormatter
        extends AbstractBaseFormatter<MessageParam, Object, MessageCreateParams.Builder> {

    protected final AnthropicMessageConverter messageConverter;

    /** Thread-local storage for generation options (passed from applyOptions to applyTools). */
    private final ThreadLocal<GenerateOptions> currentOptions = new ThreadLocal<>();

    protected AnthropicBaseFormatter() {
        this.messageConverter = new AnthropicMessageConverter(this::convertToolResultToString);
    }

    /**
     * Apply generation options to Anthropic request parameters.
     *
     * @param paramsBuilder Anthropic request parameters builder
     * @param options Generation options to apply
     * @param defaultOptions Default options to use if options parameter is null
     */
    @Override
    public void applyOptions(
            MessageCreateParams.Builder paramsBuilder,
            GenerateOptions options,
            GenerateOptions defaultOptions) {
        // Save effective options for applyTools, including model-level defaults.
        currentOptions.set(GenerateOptions.mergeOptions(options, defaultOptions));

        // Apply other options
        AnthropicToolsHelper.applyOptions(paramsBuilder, options, defaultOptions);
    }

    /**
     * Apply tool schemas to Anthropic request parameters. This method uses the options saved from
     * applyOptions to apply tool choice configuration.
     *
     * @param paramsBuilder Anthropic request parameters builder
     * @param tools List of tool schemas to apply (may be null or empty)
     */
    @Override
    public void applyTools(MessageCreateParams.Builder paramsBuilder, List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            currentOptions.remove();
            return;
        }

        // Use saved options to apply tools with tool choice
        GenerateOptions options = currentOptions.get();
        AnthropicToolsHelper.applyTools(paramsBuilder, tools, options);

        // Clean up thread-local storage
        currentOptions.remove();
    }

    /**
     * Extract and apply system message if present. Anthropic API requires system message to be set
     * via the system parameter, not as a message.
     *
     * <p>This method is called by Model to extract the first system message from the messages list
     * and apply it to the system parameter.
     *
     * @param paramsBuilder Anthropic request parameters builder
     * @param messages All messages including potential system message
     */
    public void applySystemMessage(MessageCreateParams.Builder paramsBuilder, List<Msg> messages) {
        applySystemMessage(paramsBuilder, messages, false);
    }

    /**
     * Extract and apply system message if present, optionally marking it for prompt caching.
     *
     * <p>When {@code cacheControlEnabled} is true, the system prompt is sent as a text block
     * carrying <code>cache_control: {"type": "ephemeral"}</code> so long system prompts can be
     * cached by Anthropic.
     *
     * @param paramsBuilder Anthropic request parameters builder
     * @param messages All messages including potential system message
     * @param cacheControlEnabled whether prompt caching is enabled
     */
    public void applySystemMessage(
            MessageCreateParams.Builder paramsBuilder,
            List<Msg> messages,
            boolean cacheControlEnabled) {
        String systemMessage = messageConverter.extractSystemMessage(messages);
        if (systemMessage == null || systemMessage.isEmpty()) {
            return;
        }

        if (cacheControlEnabled) {
            paramsBuilder.systemOfTextBlockParams(
                    List.of(
                            TextBlockParam.builder()
                                    .text(systemMessage)
                                    .cacheControl(EPHEMERAL_CACHE_CONTROL)
                                    .build()));
        } else {
            paramsBuilder.system(systemMessage);
        }
    }

    /** Shared ephemeral cache control marker for prompt caching. */
    static final CacheControlEphemeral EPHEMERAL_CACHE_CONTROL =
            CacheControlEphemeral.builder().build();

    /**
     * Apply the automatic cache control strategy to formatted messages.
     *
     * <p>Adds <code>cache_control: {"type": "ephemeral"}</code> to the last cacheable content
     * block of the last message. Anthropic caches everything up to and including the marked
     * block, so this caches the entire conversation prefix across turns.
     *
     * @param formattedMessages the formatted Anthropic messages
     * @return the messages with cache control applied
     */
    public List<MessageParam> applyCacheControl(List<MessageParam> formattedMessages) {
        if (formattedMessages == null || formattedMessages.isEmpty()) {
            return formattedMessages;
        }

        int lastIdx = formattedMessages.size() - 1;
        MessageParam last = formattedMessages.get(lastIdx);
        MessageParam marked = markLastCacheableBlock(last);
        if (marked == last) {
            return formattedMessages;
        }

        List<MessageParam> result = new ArrayList<>(formattedMessages);
        result.set(lastIdx, marked);
        return result;
    }

    /**
     * Return a copy of the message whose last cacheable content block carries ephemeral
     * cache_control. String content is converted to a single text block so cache_control can be
     * attached. Returns the original instance when nothing can be marked.
     */
    private static MessageParam markLastCacheableBlock(MessageParam message) {
        MessageParam.Content content = message.content();

        if (content.isString()) {
            TextBlockParam text =
                    TextBlockParam.builder()
                            .text(content.asString())
                            .cacheControl(EPHEMERAL_CACHE_CONTROL)
                            .build();
            return message.toBuilder()
                    .content(
                            MessageParam.Content.ofBlockParams(
                                    List.of(ContentBlockParam.ofText(text))))
                    .build();
        }

        if (content.isBlockParams()) {
            List<ContentBlockParam> blocks = new ArrayList<>(content.asBlockParams());
            for (int i = blocks.size() - 1; i >= 0; i--) {
                ContentBlockParam marked = withCacheControl(blocks.get(i));
                if (marked == null) {
                    continue;
                }
                blocks.set(i, marked);
                return message.toBuilder()
                        .content(MessageParam.Content.ofBlockParams(blocks))
                        .build();
            }
        }

        return message;
    }

    /**
     * Return a copy of the block with ephemeral cache_control attached, or {@code null} when the
     * block type does not support cache_control (e.g. thinking blocks).
     */
    private static ContentBlockParam withCacheControl(ContentBlockParam block) {
        if (block.isText()) {
            return ContentBlockParam.ofText(
                    block.asText().toBuilder().cacheControl(EPHEMERAL_CACHE_CONTROL).build());
        }
        if (block.isImage()) {
            return ContentBlockParam.ofImage(
                    block.asImage().toBuilder().cacheControl(EPHEMERAL_CACHE_CONTROL).build());
        }
        if (block.isToolUse()) {
            return ContentBlockParam.ofToolUse(
                    block.asToolUse().toBuilder().cacheControl(EPHEMERAL_CACHE_CONTROL).build());
        }
        if (block.isToolResult()) {
            return ContentBlockParam.ofToolResult(
                    block.asToolResult().toBuilder().cacheControl(EPHEMERAL_CACHE_CONTROL).build());
        }
        return null;
    }
}
