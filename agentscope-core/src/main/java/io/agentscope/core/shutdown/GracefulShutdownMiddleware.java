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
package io.agentscope.core.shutdown;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * System middleware that integrates graceful shutdown into the agent lifecycle.
 *
 * <p>Request registration and unregistration are handled by {@code AgentBase.call()}
 * via {@code Mono.using} (setup registers, cleanup unregisters), guaranteeing that
 * every registered request is always unregistered regardless of success, error, or cancel.
 *
 * <p>This middleware is responsible for:
 * <ul>
 *   <li>{@code onAgent} — deduplicate input if resuming from a shutdown-interrupted session</li>
 *   <li>{@code onReasoning} (doOnComplete) — checkpoint after reasoning</li>
 *   <li>{@code onActing} (doOnComplete) — checkpoint after acting</li>
 * </ul>
 *
 * <p>Shutdown checkpoints — when the system is in SHUTTING_DOWN state, the agent is
 * interrupted at these safe points (after the current phase has fully completed), so
 * reasoning/acting are allowed to complete before the interrupt is issued — output tokens
 * are not wasted. Only when the global shutdown timeout is reached will the agent be
 * force-interrupted mid-phase (handled by GracefulShutdownManager#enforceTimeoutAndInterrupt).
 */
public final class GracefulShutdownMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownMiddleware.class);

    private final GracefulShutdownManager manager;

    public GracefulShutdownMiddleware(GracefulShutdownManager manager) {
        this.manager = manager;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnComplete(() -> manager.interruptIfShuttingDown(agent));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnComplete(() -> manager.interruptIfShuttingDown(agent));
    }
}
