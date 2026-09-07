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
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>{@link #onReasoning} withholds tripped tools from the schema list for that turn, and claims
 *       the single recovery probe when a cooldown has elapsed. This is the enforcing half: a tool the
 *       model cannot see is a tool it cannot call.
 *   <li>{@link #onActing} watches {@link ToolResultEndEvent} to count outcomes and to complete or
 *       release the probe. This is the observing half: it decides when a circuit trips or recovers.
 * </ul>
 *
 * <p>Filtering happens per turn on a copy of the schema list. Nothing registered on the {@link
 * io.agentscope.core.tool.Toolkit} is mutated, so a circuit tripped while serving one session cannot
 * remove a tool from a concurrent session, and the tool registrations the application declared stay
 * authoritative. Recovery needs no repair step for the same reason: once the breaker stops
 * withholding a tool, the unfiltered list is already correct.
 *
 * <h2>Recovery probes</h2>
 *
 * <p>When a cooldown elapses the tool is advertised again, but only to the one turn that wins the
 * probe claim; concurrent turns keep withholding it. The claim is carried on the per-call {@link
 * RuntimeContext} and bound to the tool-call id the model chooses, so the matching result completes
 * exactly that probe. Three paths hand the claim back early rather than waiting for its lease to
 * expire: the model never calling the advertised tool, a call refused by permission rules, and a
 * cancelled call — none of them tested the dependency. A suspended call keeps the claim, because its
 * outcome is still pending.
 *
 * <p>A null {@code RuntimeContext} (possible when the middleware is driven directly rather than by an
 * agent) disables the binding: outcomes are then recorded without a token, and any probe claim is
 * released when the reasoning stream terminates.
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
    private final String probeAttributeKey;

    /**
     * Wrap a breaker as middleware.
     *
     * @param breaker the breaker holding policy and state
     */
    public ToolCircuitBreakerMiddleware(ToolCircuitBreaker breaker) {
        this.breaker = Objects.requireNonNull(breaker, "breaker must not be null");
        // Scope the context attribute to this instance so two breakers on one agent cannot claim
        // each other's probe bindings.
        this.probeAttributeKey =
                ToolCircuitBreakerMiddleware.class.getName()
                        + ".probes#"
                        + Integer.toHexString(System.identityHashCode(breaker));
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
        Map<String, String> claimed = null;
        for (ToolSchema tool : tools) {
            String name = tool == null ? null : tool.getName();
            ToolCircuitState state = name == null ? ToolCircuitState.CLOSED : breaker.state(name);
            if (state == ToolCircuitState.CLOSED) {
                visible.add(tool);
                continue;
            }
            Optional<String> probe =
                    state == ToolCircuitState.HALF_OPEN
                            ? breaker.tryAcquireProbe(name)
                            : Optional.empty();
            if (probe.isPresent()) {
                if (claimed == null) {
                    claimed = new LinkedHashMap<>(2);
                }
                claimed.put(name, probe.get());
                visible.add(tool);
                continue;
            }
            if (withheld == null) {
                withheld = new ArrayList<>(2);
            }
            withheld.add(name);
        }
        if (withheld == null && claimed == null) {
            return next.apply(input);
        }
        if (withheld != null) {
            logger.debug(
                    "Withholding tripped tools from this reasoning turn: {} of {} tools hidden,"
                            + " hidden={}",
                    withheld.size(),
                    tools.size(),
                    withheld);
        }
        ReasoningInput forwarded =
                withheld == null
                        ? input
                        : new ReasoningInput(input.messages(), visible, input.options());
        if (claimed == null) {
            return next.apply(forwarded);
        }
        Map<String, String> probes = claimed;
        Map<String, Boolean> selected = new ConcurrentHashMap<>();
        // Resolve the binding map up front: RuntimeContext offers no atomic putIfAbsent, and here
        // we are still single-threaded, before the returned stream is subscribed.
        Map<String, ProbeBinding> bindings = ctx == null ? null : probeBindings(ctx);
        return next.apply(forwarded)
                .doOnNext(event -> bindProbe(bindings, event, probes, selected))
                .doFinally(signal -> releaseUnusedProbes(probes, selected));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnNext(event -> recordOutcome(ctx, event));
    }

    /**
     * Remember which tool call carries a claimed probe, so the matching result can complete it.
     *
     * <p>Marking the tool as selected also stops {@link #releaseUnusedProbes} from handing the claim
     * back when the reasoning stream ends: the call is in flight and its outcome still pending.
     */
    private void bindProbe(
            Map<String, ProbeBinding> bindings,
            AgentEvent event,
            Map<String, String> probes,
            Map<String, Boolean> selected) {
        if (!(event instanceof ToolCallStartEvent toolCall)) {
            return;
        }
        String name = toolCall.getToolCallName();
        String token = name == null ? null : probes.get(name);
        if (token == null) {
            return;
        }
        selected.put(name, Boolean.TRUE);
        String callId = toolCall.getToolCallId();
        if (bindings == null || callId == null) {
            // Without a context there is nowhere to bind the token; the lease bounds the fallout.
            return;
        }
        bindings.put(callId, new ProbeBinding(name, token));
    }

    /** Hand back claims for tools the model never called, so the next turn can retry at once. */
    private void releaseUnusedProbes(Map<String, String> probes, Map<String, Boolean> selected) {
        probes.forEach(
                (name, token) -> {
                    if (!selected.containsKey(name)) {
                        breaker.releaseProbe(name, token);
                    }
                });
    }

    /**
     * Feed one tool result into the breaker.
     *
     * <p>Only {@link ToolResultState#ERROR} counts as a failure. {@code DENIED} is a policy refusal
     * and {@code INTERRUPTED} a cancellation — neither is evidence about the dependency, and counting
     * them would let a user who declines a confirmation prompt trip the circuit; both hand a probe
     * claim back instead. {@code RUNNING} marks a suspended call whose outcome is not known yet, so
     * its claim is left in place to be completed later or to expire.
     */
    private void recordOutcome(RuntimeContext ctx, AgentEvent event) {
        if (!(event instanceof ToolResultEndEvent result)) {
            return;
        }
        String toolName = result.getToolCallName();
        ToolResultState state = result.getState();
        if (toolName == null || state == null) {
            return;
        }
        if (state == ToolResultState.RUNNING) {
            return;
        }
        String token = consumeProbeToken(ctx, result.getToolCallId(), toolName);
        switch (state) {
            case ERROR -> breaker.recordFailure(toolName, token);
            case SUCCESS -> breaker.recordSuccess(toolName, token);
            case DENIED, INTERRUPTED -> breaker.releaseProbe(toolName, token);
            default -> {
                // RUNNING handled above; no other states exist.
            }
        }
    }

    private String consumeProbeToken(RuntimeContext ctx, String callId, String toolName) {
        if (ctx == null || callId == null) {
            return null;
        }
        Map<String, ProbeBinding> bindings = ctx.get(probeAttributeKey);
        if (bindings == null) {
            return null;
        }
        ProbeBinding binding = bindings.remove(callId);
        return binding != null && toolName.equals(binding.toolName()) ? binding.token() : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, ProbeBinding> probeBindings(RuntimeContext ctx) {
        Map<String, ProbeBinding> bindings = ctx.get(probeAttributeKey);
        if (bindings == null) {
            bindings = new ConcurrentHashMap<>();
            ctx.put(probeAttributeKey, bindings);
        }
        return bindings;
    }

    /** A recovery-probe claim awaiting the result of one tool call. */
    private record ProbeBinding(String toolName, String token) {}
}
