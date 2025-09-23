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
package io.agentscope.core.pipeline;

import io.agentscope.core.message.Msg;
import reactor.core.publisher.Mono;

/**
 * Base interface for pipeline execution in AgentScope.
 *
 * Pipelines provide orchestration of agents and operations in various patterns
 * such as sequential, parallel (fanout), or custom flows. This follows the
 * Python agentscope pipeline architecture pattern.
 *
 * @param <T> Type of the pipeline result
 */
public interface Pipeline<T> {

    /**
     * Execute the pipeline with the given input message.
     *
     * @param input Input message to process through the pipeline
     * @return Mono containing the pipeline result
     */
    Mono<T> execute(Msg input);

    /**
     * Execute the pipeline with no input (for pipelines that don't need input).
     *
     * @return Mono containing the pipeline result
     */
    default Mono<T> execute() {
        return execute(null);
    }

    /**
     * Get a description of this pipeline.
     *
     * @return Human-readable description of the pipeline
     */
    default String getDescription() {
        return getClass().getSimpleName();
    }
}
