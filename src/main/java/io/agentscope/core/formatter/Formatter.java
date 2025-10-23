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

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import java.time.Instant;
import java.util.List;

/**
 * Formatter interface for converting between AgentScope and provider-specific formats.
 * This is an internal interface used by Model implementations.
 *
 * <p>Formatters are responsible for:
 * 1. Converting Msg objects to provider-specific request format
 * 2. Converting provider-specific responses back to AgentScope ChatResponse
 * 3. Applying generation options to provider-specific request builders
 *
 * <p>Each formatter is type-safe and handles the exact types expected by the provider's SDK.
 *
 * @param <TReq> Provider-specific request message type (e.g., com.alibaba.dashscope.common.Message
 *               for DashScope, or ChatCompletionMessageParam for OpenAI)
 * @param <TResp> Provider-specific response type (e.g., GenerationResult for DashScope,
 *                or ChatCompletion/ChatCompletionChunk for OpenAI)
 * @param <TParams> Provider-specific request parameters builder type (e.g.,
 *                  GenerationParam for DashScope, or ChatCompletionCreateParams.Builder for OpenAI)
 */
public interface Formatter<TReq, TResp, TParams> {

    /**
     * Format AgentScope messages to provider-specific request format.
     *
     * @param msgs List of AgentScope messages
     * @return List of provider-specific request messages
     */
    List<TReq> format(List<Msg> msgs);

    /**
     * Parse provider-specific response to AgentScope ChatResponse.
     *
     * @param response Provider-specific response object
     * @param startTime Request start time for calculating duration
     * @return AgentScope ChatResponse
     */
    ChatResponse parseResponse(TResp response, Instant startTime);

    /**
     * Apply generation options to provider-specific request parameters.
     *
     * @param paramsBuilder Provider-specific request parameters builder
     * @param options Generation options to apply
     * @param defaultOptions Default options to use if options parameter is null
     */
    void applyOptions(
            TParams paramsBuilder, GenerateOptions options, GenerateOptions defaultOptions);

    /**
     * Get formatter capabilities.
     *
     * @return FormatterCapabilities describing what this formatter supports
     */
    FormatterCapabilities getCapabilities();
}
