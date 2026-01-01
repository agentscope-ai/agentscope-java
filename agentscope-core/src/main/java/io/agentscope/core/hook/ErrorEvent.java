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
import java.util.Objects;

/**
 * Event fired when an error occurs during agent execution.
 *
 * <p><b>Modifiable:</b> No (notification-only)
 *
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Agent's memory</li>
 *   <li>{@link #getError()} - The error that occurred</li>
 * </ul>
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Log errors with context</li>
 *   <li>Send error notifications</li>
 *   <li>Collect error metrics</li>
 *   <li>Implement custom error handling</li>
 * </ul>
 */
public final class ErrorEvent extends HookEvent {

    private final Throwable error;

    /**
     * Constructor for ErrorEvent.
     *
     * @param agent The agent instance (must not be null)
     * @param error The error that occurred (must not be null)
     * @throws NullPointerException if agent or error is null
     */
    public ErrorEvent(Agent agent, Throwable error) {
        super(HookEventType.ERROR, agent);
        this.error = Objects.requireNonNull(error, "error cannot be null");
    }

    /**
     * Get the error that occurred.
     *
     * @return The error
     */
    public Throwable getError() {
        return error;
    }
}
