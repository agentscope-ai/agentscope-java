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
import io.agentscope.core.event.AgentEvent;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Builds an onion-style middleware chain for a given interception point.
 *
 * <p>The chain is constructed back-to-front: the last middleware wraps
 * the core logic, and the first middleware is the outermost wrapper.
 */
public final class MiddlewareChain {

    private MiddlewareChain() {}

    /**
     * Build a middleware chain that produces {@code Flux<AgentEvent>}.
     *
     * @param middlewares ordered list of middlewares (first = outermost)
     * @param agent      the agent instance passed to each middleware
     * @param method     reference to the middleware hook method
     * @param core       the innermost logic to execute when all middlewares delegate
     * @param <I>        the input type for the interception point
     * @return a function that, when applied to an input, runs the full chain
     */
    public static <I> Function<I, Flux<AgentEvent>> build(
            List<Middleware> middlewares,
            Agent agent,
            MiddlewareMethod<I> method,
            Function<I, Flux<AgentEvent>> core) {
        if (middlewares == null || middlewares.isEmpty()) {
            return core;
        }
        Function<I, Flux<AgentEvent>> chain = core;
        for (int i = middlewares.size() - 1; i >= 0; i--) {
            Middleware mw = middlewares.get(i);
            Function<I, Flux<AgentEvent>> next = chain;
            chain = input -> method.apply(mw, agent, input, next);
        }
        return chain;
    }

    /**
     * Functional interface representing one of the onion-pattern middleware hooks.
     *
     * @param <I> the input type
     */
    @FunctionalInterface
    public interface MiddlewareMethod<I> {
        Flux<AgentEvent> apply(
                Middleware mw, Agent agent, I input, Function<I, Flux<AgentEvent>> next);
    }
}
