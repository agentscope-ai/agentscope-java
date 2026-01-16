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

/**
 * Event fired after summary generation completes.
 *
 * <p><b>Modifiable:</b> Yes - {@link #setSummaryMessage(Msg)}
 *
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Agent's memory</li>
 *   <li>{@link #getModelName()} - The model name</li>
 *   <li>{@link #getGenerateOptions()} - The generation options</li>
 *   <li>{@link #getSummaryMessage()} - The summary result (modifiable)</li>
 * </ul>
 *
 * <p><b>Note:</b> This event is fired after the summary has been generated, allowing hooks
 * to modify the final summary message before it's returned.
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Filter or modify the summary content</li>
 *   <li>Add metadata to the summary message</li>
 *   <li>Log the summary result</li>
 *   <li>Request to stop the agent via {@link #stopAgent()}</li>
 * </ul>
 */
public final class PostSummaryEvent extends SummaryEvent {

    private Msg summaryMessage;
    private boolean stopRequested = false;

    /**
     * Constructor for PostSummaryEvent.
     *
     * @param agent The agent instance (must not be null)
     * @param modelName The model name (must not be null)
     * @param generateOptions The generation options (may be null)
     * @param summaryMessage The summary result message (may be null)
     */
    public PostSummaryEvent(
            Agent agent, String modelName, GenerateOptions generateOptions, Msg summaryMessage) {
        super(HookEventType.POST_SUMMARY, agent, modelName, generateOptions);
        this.summaryMessage = summaryMessage;
    }

    /**
     * Get the summary result message.
     *
     * @return The summary message, may be null
     */
    public Msg getSummaryMessage() {
        return summaryMessage;
    }

    /**
     * Modify the summary result message.
     *
     * @param summaryMessage The new summary message
     */
    public void setSummaryMessage(Msg summaryMessage) {
        this.summaryMessage = summaryMessage;
    }

    /**
     * Request to stop the agent after this summary phase.
     *
     * <p>When called, the agent will return the summary message as the final result.
     * This is primarily for consistency with other event types; since summary is typically
     * the last phase, this mainly serves as a signal for logging or metrics purposes.
     */
    public void stopAgent() {
        this.stopRequested = true;
    }

    /**
     * Check if a stop has been requested.
     *
     * @return true if {@link #stopAgent()} has been called, false otherwise
     */
    public boolean isStopRequested() {
        return stopRequested;
    }
}
