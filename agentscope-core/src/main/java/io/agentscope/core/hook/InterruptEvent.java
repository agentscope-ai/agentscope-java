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

/**
 * Event fired when agent execution is interrupted (by user HITL or system shutdown).
 *
 * <p>This event is fired <b>after</b> {@code handleInterrupt()} completes (whether
 * successfully or with an error), ensuring that state-saving and recovery logic
 * finishes before hooks perform cleanup (e.g. unregistering active requests).
 *
 * <p><b>Modifiable:</b> No (notification-only)
 *
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance being interrupted</li>
 *   <li>{@link #getMemory()} - Agent's memory at the time of interruption</li>
 * </ul>
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Unregister active requests during graceful shutdown</li>
 *   <li>Log interrupt events</li>
 *   <li>Collect interrupt metrics</li>
 * </ul>
 */
public final class InterruptEvent extends HookEvent {

    /**
     * Constructor for InterruptEvent.
     *
     * @param agent The agent instance being interrupted (must not be null)
     * @throws NullPointerException if agent is null
     */
    public InterruptEvent(Agent agent) {
        super(HookEventType.INTERRUPT, agent);
    }
}
