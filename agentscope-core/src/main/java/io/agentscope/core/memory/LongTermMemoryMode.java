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
package io.agentscope.core.memory;

/**
 * Defines how long-term memory is integrated with agent behavior.
 *
 * <p>This enum controls whether memory management is handled automatically by the framework,
 * actively by the agent through tool calls, or both. The mode affects:
 * <ul>
 *   <li>When memory recording and retrieval occur
 *   <li>Whether memory management tools are registered in the agent's toolkit
 *   <li>How much control the agent has over its own memory
 * </ul>
 *
 * <p><b>Choosing the Right Mode:</b>
 * <ul>
 *   <li><b>AGENT_CONTROL:</b> Use when you want the agent to have full autonomy over memory
 *       decisions. Good for advanced agents that understand when information is important.</li>
 *   <li><b>STATIC_CONTROL:</b> Use when you want automatic, framework-managed memory without
 *       agent involvement. Good for simpler agents or when memory should be comprehensive.</li>
 *   <li><b>BOTH:</b> Recommended default. Combines automatic background memory with agent
 *       control, providing the best of both approaches.</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .longTermMemory(memory)
 *     .longTermMemoryMode(LongTermMemoryMode.BOTH)  // Recommended
 *     .build();
 * }</pre>
 *
 * @see LongTermMemory
 * @see io.agentscope.core.ReActAgent
 */
public enum LongTermMemoryMode {

    /**
     * Agent actively controls memory through tool calls.
     */
    AGENT_CONTROL,

    /**
     * Framework automatically manages memory without agent involvement.
     */
    STATIC_CONTROL,

    /**
     * Combines both agent control and automatic framework management.
     */
    BOTH
}
