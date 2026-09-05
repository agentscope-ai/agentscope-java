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
package io.agentscope.core.tool.circuitbreaker;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Middleware that applies a {@link ToolCircuitBreaker} to an agent, so a tool that keeps failing
 * stops being offered to the model until it is worth trying again.
 *
 * <p>It occupies two interception points, which together close the state machine:
 *
 * <ul>
 *   <li>{@link #onReasoning} withholds tripped tools from the schema list for that turn. This is the
 *       enforcing half: a tool the model cannot see is a tool it cannot call.
 *   <li>{@link #onActing} watches {@link ToolResultEndEvent} to count outcomes. This is the
 *       observing half: it decides when a circuit trips or recovers.
 * </ul>
 *
 * <p>Filtering happens per turn on a copy of the schema list. Nothing registered on the {@link
 * io.agentscope.core.tool.Toolkit} is mutated, so a circuit tripped while serving one session cannot
 * remove a tool from a concurrent session, and the tool registrations the application declared stay
 * authoritative. Recovery needs no repair step for the same reason: once the breaker stops
 * withholding a tool, the unfiltered list is already correct.
 *
 * <p><b>Usage</b>
 *
 * <pre>{@code
 * ToolCircuitBreakerConfig config = ToolCircuitBreakerConfig.builder()
 *         .monitorTools("query_weather", "query_destination_news")
 *         .failureThreshold(3)
 *         .initialCooldown(Duration.ofSeconds(60))
 *         .maxCooldown(Duration.ofSeconds(600))
 *         .build();
 *
 * ReActAgent agent = ReActAgent.builder()
 *         .model(model)
 *         .toolkit(toolkit)
 *         .middleware(new ToolCircuitBreakerMiddleware(new ToolCircuitBreaker(config)))
 *         .build();
 * }</pre>
 *
 * <p>Share one breaker (and therefore one store) across the agents that call the same dependency, so
 * they learn from each other's failures instead of each discovering the outage separately.
 */
public class ToolCircuitBreakerMiddleware implements MiddlewareBase {

    private static final Logger logger =
            LoggerFactory.getLogger(ToolCircuitBreakerMiddleware.class);

    private final ToolCircuitBreaker breaker;

    /**
     * Wrap a breaker as middleware.
     *
     * @param breaker the breaker holding policy and state
     */
    public ToolCircuitBreakerMiddleware(ToolCircuitBreaker breaker) {
        this.breaker = Objects.requireNonNull(breaker, "breaker must not be null");
    }

    /**
     * Convenience constructor building a breaker with the default in-process store.
     *
     * @param config supervision and backoff policy
     */
    public ToolCircuitBreakerMiddleware(ToolCircuitBreakerConfig config) {
        this(new ToolCircuitBreaker(config));
    }

    /**
     * The breaker being applied, exposed so callers can inspect circuit state or reset a tool.
     *
     * @return the underlying breaker
     */
    public ToolCircuitBreaker getBreaker() {
        return breaker;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        List<ToolSchema> tools = input.tools();
        if (tools == null || tools.isEmpty()) {
            return next.apply(input);
        }
        List<ToolSchema> visible = new ArrayList<>(tools.size());
        List<String> withheld = null;
        for (ToolSchema tool : tools) {
            if (tool != null && breaker.isWithheld(tool.getName())) {
                if (withheld == null) {
                    withheld = new ArrayList<>(2);
                }
                withheld.add(tool.getName());
                continue;
            }
            visible.add(tool);
        }
        if (withheld == null) {
            return next.apply(input);
        }
        logger.debug(
                "Withholding tripped tools from this reasoning turn: {} of {} tools hidden,"
                        + " hidden={}",
                withheld.size(),
                tools.size(),
                withheld);
        return next.apply(new ReasoningInput(input.messages(), visible, input.options()));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnNext(this::recordOutcome);
    }

    /**
     * Feed one tool result into the breaker.
     *
     * <p>Only {@link ToolResultState#ERROR} counts as a failure. {@code DENIED} is a policy refusal
     * and {@code INTERRUPTED} a cancellation — neither is evidence about the dependency, and
     * counting them would let a user who declines a confirmation prompt trip the circuit. {@code
     * RUNNING} marks a suspended call whose outcome is not known yet.
     */
    private void recordOutcome(AgentEvent event) {
        if (!(event instanceof ToolResultEndEvent result)) {
            return;
        }
        String toolName = result.getToolCallName();
        ToolResultState state = result.getState();
        if (toolName == null || state == null) {
            return;
        }
        if (state == ToolResultState.ERROR) {
            breaker.recordFailure(toolName);
        } else if (state == ToolResultState.SUCCESS) {
            breaker.recordSuccess(toolName);
        }
    }
}
