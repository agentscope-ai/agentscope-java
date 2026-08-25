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

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        String systemMessage = messageConverter.extractSystemMessage(messages);
        if (systemMessage != null && !systemMessage.isEmpty()) {
            paramsBuilder.system(systemMessage);
        }
    }

    /**
     * Encode explicit and automatic prompt-cache breakpoints into an Anthropic request.
     * Explicit message metadata always remains effective. Automatic selection uses Anthropic's
     * provider-native top-level cache control, which moves the final breakpoint as a conversation
     * grows.
     */
    public PromptCachePlan applyPromptCache(
            MessageCreateParams.Builder paramsBuilder,
            List<Msg> originalMessages,
            List<MessageParam> formattedMessages,
            boolean automatic) {
        List<MessageParam> plannedMessages = new ArrayList<>(formattedMessages);
        Msg system =
                originalMessages != null
                                && !originalMessages.isEmpty()
                                && originalMessages.get(0).getRole() == MsgRole.SYSTEM
                        ? originalMessages.get(0)
                        : null;

        boolean explicitSystem =
                system != null
                        && system.getMetadata() != null
                        && Boolean.TRUE.equals(
                                system.getMetadata().get(MessageMetadataKeys.CACHE_CONTROL));
        int explicitCount = explicitSystem ? 1 : 0;
        explicitCount +=
                (int)
                        plannedMessages.stream()
                                .filter(AnthropicPromptCacheSupport::hasCacheControl)
                                .count();
        if (explicitCount > MAX_PROMPT_CACHE_BREAKPOINTS) {
            throw new IllegalArgumentException(
                    "Prompt cache supports at most "
                            + MAX_PROMPT_CACHE_BREAKPOINTS
                            + " explicit breakpoints, but got "
                            + explicitCount);
        }
        if (automatic && explicitCount == MAX_PROMPT_CACHE_BREAKPOINTS) {
            throw new IllegalArgumentException(
                    "Anthropic automatic prompt caching requires one of the four breakpoint slots");
        }
        if (automatic) {
            paramsBuilder.putAdditionalBodyProperty(
                    "cache_control", JsonValue.from(Map.of("type", "ephemeral")));
        }

        if (system != null) {
            String systemText = messageConverter.extractSystemMessage(originalMessages);
            if (systemText != null && !systemText.isEmpty()) {
                TextBlockParam.Builder block = TextBlockParam.builder().text(systemText);
                if (explicitSystem) {
                    block.cacheControl(AnthropicPromptCacheSupport.ephemeral());
                }
                paramsBuilder.systemOfTextBlockParams(List.of(block.build()));
            } else if (explicitSystem) {
                throw new IllegalArgumentException(
                        "Explicit Anthropic system cache breakpoint has no text content");
            }
        }

        return new PromptCachePlan(List.copyOf(plannedMessages));
    }

    /** Provider-ready messages and tool cache decision for one request. */
    public record PromptCachePlan(List<MessageParam> messages) {}
}
