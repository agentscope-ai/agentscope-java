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
package io.agentscope.harness.coding.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.HookEventType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that enforces per-thread token and model-call budgets.
 *
 * <p>Tracks cumulative model call counts per thread (using {@link MessageQueueHook#CURRENT_THREAD_ID}).
 * When the limit is reached, raises an exception to terminate the run.
 *
 * <p>Mirrors open-swe's {@code ModelCallLimitMiddleware} + the new per-thread budget concept.
 */
public class ThreadBudgetHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ThreadBudgetHook.class);

    private final int maxModelCallsPerThread;
    private final ConcurrentHashMap<String, AtomicLong> callCounts = new ConcurrentHashMap<>();

    public ThreadBudgetHook(int maxModelCallsPerThread) {
        this.maxModelCallsPerThread = maxModelCallsPerThread;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event.getType() != HookEventType.PRE_REASONING) {
            return Mono.just(event);
        }
        String threadId = MessageQueueHook.CURRENT_THREAD_ID.get();
        if (threadId == null || threadId.isBlank()) {
            return Mono.just(event);
        }
        long count = callCounts.computeIfAbsent(threadId, k -> new AtomicLong(0)).incrementAndGet();
        if (count > maxModelCallsPerThread) {
            log.warn(
                    "[thread-budget-hook] Thread {} exceeded model call budget ({}/{}). Terminating"
                            + " run.",
                    threadId,
                    count,
                    maxModelCallsPerThread);
            return Mono.error(
                    new RuntimeException(
                            "Thread model call budget exceeded: "
                                    + count
                                    + "/"
                                    + maxModelCallsPerThread));
        }
        log.debug(
                "[thread-budget-hook] Thread {} model call {}/{}",
                threadId,
                count,
                maxModelCallsPerThread);
        return Mono.just(event);
    }

    public void resetThread(String threadId) {
        callCounts.remove(threadId);
    }
}
