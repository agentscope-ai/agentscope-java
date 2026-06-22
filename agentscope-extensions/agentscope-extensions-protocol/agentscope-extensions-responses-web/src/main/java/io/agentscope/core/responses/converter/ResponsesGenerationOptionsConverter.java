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
package io.agentscope.core.responses.converter;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.responses.model.ResponsesRequest;

/** Converts per-request Responses generation options to AgentScope GenerateOptions. */
public class ResponsesGenerationOptionsConverter {

    private final ResponsesToolConverter toolConverter;

    /**
     * Constructs a converter for request-level generation options.
     *
     * @param toolConverter Converter used for {@code tool_choice}
     */
    public ResponsesGenerationOptionsConverter(ResponsesToolConverter toolConverter) {
        this.toolConverter = toolConverter;
    }

    /**
     * Convert supported Responses generation fields into AgentScope {@link GenerateOptions}.
     *
     * <p>Returns {@code null} when the request contains no per-call generation overrides, allowing
     * the agent's configured defaults to remain unchanged.
     *
     * @param request Original Responses request
     * @return AgentScope generation options, or {@code null} if no options were requested
     */
    public GenerateOptions convert(ResponsesRequest request) {
        if (request == null) {
            return null;
        }
        GenerateOptions.Builder builder = GenerateOptions.builder();
        boolean hasOptions = false;

        if (request.getModel() != null && !request.getModel().isBlank()) {
            builder.modelName(request.getModel());
            hasOptions = true;
        }
        if (request.getStream() != null) {
            builder.stream(request.getStream());
            hasOptions = true;
        }
        if (request.getTemperature() != null) {
            builder.temperature(request.getTemperature());
            hasOptions = true;
        }
        if (request.getTopP() != null) {
            builder.topP(request.getTopP());
            hasOptions = true;
        }
        if (request.getMaxOutputTokens() != null) {
            builder.maxTokens(request.getMaxOutputTokens());
            hasOptions = true;
        }
        if (request.getReasoning() != null && request.getReasoning().getEffort() != null) {
            builder.reasoningEffort(request.getReasoning().getEffort());
            hasOptions = true;
        }
        ToolChoice toolChoice = toolConverter.convertToolChoice(request.getToolChoice());
        if (toolChoice != null) {
            builder.toolChoice(toolChoice);
            hasOptions = true;
        }

        return hasOptions ? builder.build() : null;
    }
}
