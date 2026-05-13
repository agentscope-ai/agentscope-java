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
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Event fired before summary generation when max iterations is reached.
 *
 * <p><b>Modifiable:</b> Yes - {@link #setInputMessages(List)}, {@link #setGenerateOptions(GenerateOptions)}
 *
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Agent's memory</li>
 *   <li>{@link #getModelName()} - The model name (e.g., "qwen-plus")</li>
 *   <li>{@link #getGenerateOptions()} - The generation options (temperature, etc.)</li>
 *   <li>{@link #getInputMessages()} - Messages to send to LLM for summary (modifiable)</li>
 *   <li>{@link #getMaxIterations()} - The maximum iterations configured for the agent</li>
 *   <li>{@link #getCurrentIteration()} - The current iteration count when summary triggered</li>
 * </ul>
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Inject additional context into the summary prompt</li>
 *   <li>Modify the summary system instructions</li>
 *   <li>Change generation parameters for summary</li>
 *   <li>Log summary generation input</li>
 * </ul>
 */
public final class PreSummaryEvent extends SummaryEvent {

    private List<Msg> inputMessages;
    private GenerateOptions overriddenGenerateOptions;
    private final int maxIterations;
    private final int currentIteration;

    /**
     * Constructor for PreSummaryEvent.
     *
     * @param agent The agent instance (must not be null)
     * @param modelName The model name (must not be null)
     * @param generateOptions The generation options (may be null)
     * @param inputMessages The messages to send to LLM for summary (must not be null)
     * @param maxIterations The maximum iterations configured for the agent
     * @param currentIteration The current iteration count when summary triggered
     * @throws NullPointerException if agent, modelName, or inputMessages is null
     */
    public PreSummaryEvent(
            Agent agent,
            String modelName,
            GenerateOptions generateOptions,
            List<Msg> inputMessages,
            int maxIterations,
            int currentIteration) {
        super(HookEventType.PRE_SUMMARY, agent, modelName, generateOptions);
        this.inputMessages =
                new ArrayList<>(
                        Objects.requireNonNull(inputMessages, "inputMessages cannot be null"));
        this.maxIterations = maxIterations;
        this.currentIteration = currentIteration;
    }

    /**
     * Get the messages that will be sent to LLM for summary generation.
     *
     * @return The input messages
     */
    public List<Msg> getInputMessages() {
        return inputMessages;
    }

    /**
     * Modify the messages to send to LLM for summary.
     *
     * @param inputMessages The new message list (must not be null)
     * @throws NullPointerException if inputMessages is null
     */
    public void setInputMessages(List<Msg> inputMessages) {
        this.inputMessages = Objects.requireNonNull(inputMessages, "inputMessages cannot be null");
    }

    /**
     * Get the maximum iterations configured for the agent.
     *
     * @return The maximum iterations
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * Get the current iteration count when summary was triggered.
     *
     * @return The current iteration count
     */
    public int getCurrentIteration() {
        return currentIteration;
    }

    /**
     * Get the effective generation options.
     *
     * <p>Returns the overridden options if set via {@link #setGenerateOptions(GenerateOptions)},
     * otherwise returns the original options from the parent class.
     *
     * @return The effective generation options
     */
    public GenerateOptions getEffectiveGenerateOptions() {
        return overriddenGenerateOptions != null
                ? overriddenGenerateOptions
                : super.getGenerateOptions();
    }

    /**
     * Set custom generation options for this summary call.
     *
     * <p>This allows hooks to override the default generation options.
     *
     * @param generateOptions The custom generation options
     */
    public void setGenerateOptions(GenerateOptions generateOptions) {
        this.overriddenGenerateOptions = generateOptions;
    }
}
