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
import java.util.List;

/**
 * Event fired before agent starts processing.
 *
 * <p><b>Modifiable:</b> No (notification-only)
 *
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Agent's memory (includes input messages already added)</li>
 * </ul>
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Log the start of agent execution</li>
 *   <li>Initialize execution-specific resources</li>
 *   <li>Track agent invocation metrics</li>
 * </ul>
 */
public final class PreCallEvent extends HookEvent {

    private List<Msg> inputMessages;

    /**
     * Constructor for PreCallEvent.
     *
     * @param agent The agent instance (must not be null)
     * @throws NullPointerException if agent is null
     */
    public PreCallEvent(Agent agent, List<Msg> inputMessages) {
        super(HookEventType.PRE_CALL, agent);
        this.inputMessages = inputMessages;
    }

    public List<Msg> getInputMessages() {
        return inputMessages;
    }

    public void setInputMessages(List<Msg> inputMessages) {
        this.inputMessages = inputMessages;
    }
}
