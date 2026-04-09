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
package io.agentscope.core.hook;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.GenerateOptions;
import java.util.Objects;

/**
 * Base class for summary-related events.
 *
 * <p>This sealed class provides common context for all summary events that occur
 * when a ReActAgent reaches its maximum iterations and generates a summary:
 * <ul>
 *   <li>{@link #getModelName()} - The model name (e.g., "qwen-plus", "gpt-4")</li>
 *   <li>{@link #getGenerateOptions()} - The generation options (temperature, etc.)</li>
 * </ul>
 *
 * <p>Subclasses represent different stages of the summary process:
 * <ul>
 *   <li>{@link PreSummaryEvent} - Before summary generation</li>
 *   <li>{@link SummaryChunkEvent} - During streaming</li>
 *   <li>{@link PostSummaryEvent} - After summary generation completes</li>
 * </ul>
 *
 * @see PreSummaryEvent
 * @see SummaryChunkEvent
 * @see PostSummaryEvent
 */
public abstract sealed class SummaryEvent extends HookEvent
        permits PreSummaryEvent, SummaryChunkEvent, PostSummaryEvent {

    private final String modelName;
    private final GenerateOptions generateOptions;

    /**
     * Constructor for SummaryEvent.
     *
     * @param type The event type (must not be null)
     * @param agent The agent instance (must not be null)
     * @param modelName The model name (must not be null)
     * @param generateOptions The generation options (may be null if using model defaults)
     * @throws NullPointerException if type, agent, or modelName is null
     */
    protected SummaryEvent(
            HookEventType type, Agent agent, String modelName, GenerateOptions generateOptions) {
        super(type, agent);
        this.modelName = Objects.requireNonNull(modelName, "modelName cannot be null");
        this.generateOptions = generateOptions;
    }

    /**
     * Get the model name.
     *
     * @return The model name (e.g., "qwen-plus", "gpt-4")
     */
    public final String getModelName() {
        return modelName;
    }

    /**
     * Get the generation options.
     *
     * @return The generation options (temperature, maxTokens, etc.), or null if using model
     *     defaults
     */
    public final GenerateOptions getGenerateOptions() {
        return generateOptions;
    }
}
