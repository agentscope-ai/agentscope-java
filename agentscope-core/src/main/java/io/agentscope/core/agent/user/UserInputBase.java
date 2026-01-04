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
package io.agentscope.core.agent.user;

import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Strategy interface for handling user input from different sources.
 * Enables pluggable input implementations such as terminal console, web UI, or programmatic
 * sources. Implementations convert raw user input into UserInputData containing both content
 * blocks and optional structured data, maintaining consistency across different input channels.
 */
public interface UserInputBase {

    /**
     * Handle user input and return the input data.
     *
     * @param agentId The agent identifier
     * @param agentName The agent name
     * @param contextMessages Optional messages to display before prompting (e.g., assistant
     *     response)
     * @param structuredModel Optional class for structured input format
     * @return Mono containing the user input data
     */
    Mono<UserInputData> handleInput(
            String agentId, String agentName, List<Msg> contextMessages, Class<?> structuredModel);
}
