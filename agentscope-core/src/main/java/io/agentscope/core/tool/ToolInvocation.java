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
package io.agentscope.core.tool;

import io.agentscope.core.agent.RuntimeContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot of a single upcoming tool call, captured after schema validation and preset merging,
 * right before real execution.
 *
 * <p>Interceptors ({@link ToolCallInterceptor}) observe this snapshot and decide the input the
 * tool actually receives via {@link #withInput(Map)}. The {@code input} map is an immutable view
 * that tolerates {@code null} values (valid JSON arguments such as {@code {"options": null}}) and
 * preserves the model's JSON key order. The copy is shallow: nested maps/lists are shared with the
 * caller, so interceptors must not mutate them in place.
 *
 * <p>{@code runtimeContext} is {@code null} when the call carries no runtime context and the
 * toolkit configures no default context; implementations must null-check it.
 *
 * <p>For per-agent dispatch and audit the snapshot carries the calling agent's identity ({@code
 * agentId} / {@code agentName}), both {@code null} on direct {@link Toolkit#callTool} calls
 * without an agent. Deliberately identity only: the {@code Agent} handle is not exposed, keeping
 * interceptors free of agent-lifecycle operations (re-entering the same session's agent deadlocks
 * on its call gate) and consistent with their Toolkit-layer scope; agent-loop concerns belong in
 * middleware.
 *
 * @param toolName      the tool name being invoked
 * @param tool          the resolved tool instance (metadata access, e.g. {@link AgentTool#isReadOnly()})
 * @param input         the final merged input (LLM arguments plus preset parameters)
 * @param runtimeContext the caller identity context, or {@code null} when unavailable
 * @param agentId       the calling agent's unique id, or {@code null} on agentless direct calls
 * @param agentName     the calling agent's business name, or {@code null} on agentless direct calls
 * @author shanhongyu
 */
public record ToolInvocation(
        String toolName,
        AgentTool tool,
        Map<String, Object> input,
        RuntimeContext runtimeContext,
        String agentId,
        String agentName) {

    public ToolInvocation {
        input = copyInput(input);
    }

    /**
     * Returns a copy of this invocation with the input replaced.
     *
     * @param newInput the replacement input map (may be {@code null} or contain {@code null}
     *     values)
     * @return a new invocation with all other components unchanged
     */
    public ToolInvocation withInput(Map<String, Object> newInput) {
        return new ToolInvocation(toolName, tool, newInput, runtimeContext, agentId, agentName);
    }

    /**
     * Makes a null-tolerant, order-preserving unmodifiable copy of the given input.
     *
     * @param input the raw input map, may be {@code null}
     * @return an unmodifiable copy, or an empty map when the input is {@code null} or empty
     */
    private static Map<String, Object> copyInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        // Not Map.copyOf: it rejects null values and does not guarantee iteration order.
        // LinkedHashMap keeps null-tolerant JSON key order, matching the defensive-copy trust
        // level of ToolUseBlock / ToolCallParam.
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }
}
