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
package io.agentscope.core.agent.user;

import reactor.core.publisher.Mono;

/**
 * Base interface for handling user input from different sources.
 * This corresponds to the Python UserInputBase class.
 */
public interface UserInputBase {

    /**
     * Handle user input and return the input data.
     *
     * @param agentId The agent identifier
     * @param agentName The agent name
     * @param structuredModel Optional class for structured input format
     * @return Mono containing the user input data
     */
    Mono<UserInputData> handleInput(String agentId, String agentName, Class<?> structuredModel);
}
