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
package io.agentscope.harness.coding.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Middleware that enforces per-thread token and model-call budgets.
 *
 * <p>Tracks cumulative model call counts per thread (using
 * {@link MessageQueueMiddleware#CURRENT_THREAD_ID}). When the limit is reached, raises an
 * exception to terminate the run.
 *
 * <p>The new per-thread budget concept.
 */
public class ThreadBudgetMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ThreadBudgetMiddleware.class);

    private final int maxModelCallsPerThread;
    private final ConcurrentHashMap<String, AtomicLong> callCounts = new ConcurrentHashMap<>();

    public ThreadBudgetMiddleware(int maxModelCallsPerThread) {
        this.maxModelCallsPerThread = maxModelCallsPerThread;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        String threadId = MessageQueueMiddleware.CURRENT_THREAD_ID.get();
        if (threadId == null || threadId.isBlank()) {
            return next.apply(input);
        }
        long count = callCounts.computeIfAbsent(threadId, k -> new AtomicLong(0)).incrementAndGet();
        if (count > maxModelCallsPerThread) {
            log.warn(
                    "[thread-budget-middleware] Thread {} exceeded model call budget ({}/{})."
                            + " Terminating run.",
                    threadId,
                    count,
                    maxModelCallsPerThread);
            return Flux.error(
                    new RuntimeException(
                            "Thread model call budget exceeded: "
                                    + count
                                    + "/"
                                    + maxModelCallsPerThread));
        }
        log.debug(
                "[thread-budget-middleware] Thread {} model call {}/{}",
                threadId,
                count,
                maxModelCallsPerThread);
        return next.apply(input);
    }

    public void resetThread(String threadId) {
        callCounts.remove(threadId);
    }
}
