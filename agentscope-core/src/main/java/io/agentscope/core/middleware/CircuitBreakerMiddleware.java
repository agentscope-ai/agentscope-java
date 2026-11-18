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
package io.agentscope.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.tool.CircuitBreakerTool;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Hides unavailable circuit-breaking tools from each reasoning request without mutating Toolkit.
 *
 * <p>Register the same decorator instances with the Toolkit and this middleware. Probe admission
 * happens in the decorator at execution time: advertising a recovered tool does not reserve it or
 * leave a lease behind when the model chooses a different tool. Concurrent requests can see the
 * recovered schema, but only one probe can execute.
 */
public final class CircuitBreakerMiddleware implements MiddlewareBase {
    private final Map<String, CircuitBreakerTool> tools;

    /**
     * @param tools the exact decorated tools registered with the agent's toolkit
     * @throws IllegalArgumentException if tool names are duplicated
     */
    public CircuitBreakerMiddleware(List<CircuitBreakerTool> tools) {
        Map<String, CircuitBreakerTool> byName = new HashMap<>();
        for (CircuitBreakerTool tool : tools) {
            if (byName.putIfAbsent(tool.getName(), tool) != null) {
                throw new IllegalArgumentException(
                        "Duplicate circuit breaker tool: " + tool.getName());
            }
        }
        this.tools = Map.copyOf(byName);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    if (input.tools() == null) {
                        return next.apply(input);
                    }
                    return next.apply(
                            new ReasoningInput(
                                    input.messages(),
                                    input.tools().stream()
                                            .filter(
                                                    schema ->
                                                            !tools.containsKey(schema.getName())
                                                                    || tools.get(schema.getName())
                                                                            .isAvailable())
                                            .toList(),
                                    input.options()));
                });
    }
}
