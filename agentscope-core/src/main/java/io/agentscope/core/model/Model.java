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
package io.agentscope.core.model;

import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;

public interface Model {

    /**
     * Stream chat completion responses.
     * The model internally handles message formatting using its configured formatter.
     *
     * @param messages AgentScope messages to send to the model
     * @param tools Optional list of tool schemas (null or empty if no tools)
     * @param options Optional generation options (null to use defaults)
     * @return Flux stream of chat responses
     */
    Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options);

    /**
     * Get model name for logging and identification.
     *
     * @return model name
     */
    String getModelName();

    /**
     * Whether this model supports native structured output ({@code response_format} with
     * {@code json_schema}). When {@code true}, the agent can pass the output schema directly
     * to the model via {@link GenerateOptions#getResponseFormat()} instead of injecting a
     * synthetic {@code generate_response} tool.
     *
     * @return {@code true} if the model supports native structured output
     * @see #supportsNativeStructuredOutputWithTools()
     */
    default boolean supportsNativeStructuredOutput() {
        return false;
    }

    /**
     * Whether this model correctly handles native structured output ({@code response_format}
     * with {@code json_schema}) when tools are also present in the request.
     *
     * <p>Some OpenAI-compatible providers prioritise the {@code response_format} constraint
     * over tool invocations, causing the model to produce the structured JSON directly and
     * skip tool calling entirely. When this method returns {@code false} and tools are present,
     * the agent falls back to the synthetic {@code generate_response} tool approach, which is
     * fully compatible with the ReAct tool-calling loop.
     *
     * <p>Defaults to {@link #supportsNativeStructuredOutput()}.
     *
     * @return {@code true} if the model handles {@code response_format} alongside tools correctly
     */
    default boolean supportsNativeStructuredOutputWithTools() {
        return supportsNativeStructuredOutput();
    }

    /**
     * Returns the model's context window size in tokens, or {@code 0} if unknown.
     *
     * <p>Used by the compaction middleware to dynamically compute when to trigger
     * conversation summarization. Implementations should return the total context
     * window (input + output) for the configured model.
     *
     * @return context window size in tokens, or {@code 0} if not available
     */
    default int getContextWindowSize() {
        return 0;
    }
}
