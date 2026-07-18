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

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
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
     * Format messages with request and default options, applying Anthropic-specific conversion
     * settings such as citation mode.
     *
     * <p>Creates a request-scoped {@link AnthropicMessageConverter} when citations are enabled.
     * The converter is a local variable — the formatter's {@link #messageConverter} field is never
     * mutated, so concurrent requests are safe.
     *
     * @param messages messages to format
     * @param options request-specific generation options
     * @param defaultOptions model default generation options
     * @return formatted Anthropic messages
     */
    public List<MessageParam> format(
            List<Msg> messages, GenerateOptions options, GenerateOptions defaultOptions) {
        boolean enabled =
                Boolean.TRUE.equals(
                        getOptionOrDefault(
                                options, defaultOptions, GenerateOptions::getCitationsEnabled));
        if (!enabled) {
            return format(messages);
        }

        AnthropicMessageConverter requestConverter =
                new AnthropicMessageConverter(
                        this::convertToolResultToString, CitationMode.ENABLED);

        return formatWithTracing(messages, () -> doFormat(messages, requestConverter));
    }

    /**
     * Format messages using a request-scoped message converter.
     *
     * <p>This non-abstract hook allows built-in formatters to receive the request-scoped converter
     * (which may have citations enabled) without exposing mutable state. External subclasses that
     * only override {@link #doFormat(List)} continue to work unchanged.
     *
     * <p>The default implementation delegates to {@link #doFormat(List)}, ignoring the converter.
     *
     * @param messages messages to format
     * @param requestConverter converter configured for this request's options
     * @return formatted Anthropic messages
     */
    protected List<MessageParam> doFormat(
            List<Msg> messages, AnthropicMessageConverter requestConverter) {
        return doFormat(messages);
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
        // Save options for applyTools
        currentOptions.set(options);

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
}
