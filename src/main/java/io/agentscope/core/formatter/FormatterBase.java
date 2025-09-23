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
import io.agentscope.core.model.FormattedMessageList;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Base abstract class for all formatters in AgentScope.
 *
 * Formatters are responsible for converting Msg objects to the format
 * required by specific LLM APIs. This follows the Python agentscope
 * formatter architecture pattern.
 *
 * Key responsibilities:
 * - Convert Msg objects to API-specific message format
 * - Handle different content block types (text, images, tools)
 * - Maintain conversation structure and context
 * - Support multimodal content where applicable
 */
public abstract class FormatterBase {

    /**
     * Format a list of Msg objects to API-specific format.
     *
     * @param msgs List of messages to format
     * @return Mono containing formatted message list
     */
    public abstract Mono<FormattedMessageList> format(List<Msg> msgs);

    /**
     * Format a list of Msg objects to API-specific format with additional parameters.
     *
     * @param msgs List of messages to format
     * @param options Additional formatting parameters
     * @return Mono containing formatted message list
     */
    public Mono<FormattedMessageList> format(List<Msg> msgs, FormatterOptions options) {
        return format(msgs);
    }

    /**
     * Legacy method for backward compatibility.
     * Format a list of Msg objects to API-specific format as raw maps.
     *
     * @param msgs List of messages to format
     * @return Mono containing list of formatted messages as maps
     * @deprecated Use {@link #format(List)} instead
     */
    @Deprecated
    public Mono<List<java.util.Map<String, Object>>> formatAsRawMaps(List<Msg> msgs) {
        return format(msgs).map(FormattedMessageList::asMaps);
    }

    /**
     * Validate that the input is a list of Msg objects.
     *
     * @param msgs List to validate
     * @throws IllegalArgumentException if validation fails
     */
    protected static void assertListOfMsgs(List<Msg> msgs) {
        if (msgs == null) {
            throw new IllegalArgumentException("Messages list cannot be null");
        }

        for (Object obj : msgs) {
            if (!(obj instanceof Msg)) {
                throw new IllegalArgumentException("All items in the list must be Msg objects");
            }
        }
    }

    /**
     * Get the formatter capabilities.
     *
     * @return FormatterCapabilities describing what this formatter supports
     */
    public abstract FormatterCapabilities getCapabilities();

    /**
     * Convert tool results to string representation for non-multimodal APIs.
     *
     * @param toolResults List of tool result objects
     * @return String representation of tool results
     */
    protected String convertToolResultToString(List<Object> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toolResults.size(); i++) {
            Object result = toolResults.get(i);
            if (i > 0) {
                sb.append("\n");
            }
            sb.append("Tool Result ").append(i + 1).append(": ");
            sb.append(result != null ? result.toString() : "null");
        }
        return sb.toString();
    }

    /**
     * Get the name of this formatter (for debugging and logging).
     *
     * @return Formatter name
     */
    public String getName() {
        return this.getClass().getSimpleName();
    }
}
