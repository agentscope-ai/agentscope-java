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
 * <p><b>Modifiable:</b> Yes — {@link #setInputMessages(List)} and the inherited
 * {@link HookEvent#setSystemMessage}, {@link HookEvent#appendSystemContent(String)} methods.
 *
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Agent's current (live) memory</li>
 *   <li>{@link #getInputMessages()} - Full message view: memory snapshot + this call's
 *       arguments. Hooks may append non-SYSTEM messages to the tail. Injecting
 *       {@link io.agentscope.core.message.MsgRole#SYSTEM} messages here is
 *       <strong>forbidden</strong>; use {@link HookEvent#setSystemMessage} instead.</li>
 *   <li>{@link #getSystemMessage()} - The unified system message seeded from
 *       {@code sysPrompt}; modify via the helper methods on {@link HookEvent}.</li>
 * </ul>
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Inject workspace context, skill prompts, or other static guidance into
 *       the system message via {@link HookEvent#appendSystemContent(String)}</li>
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
