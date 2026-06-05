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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.harness.agent.store.BaseStore;
import io.agentscope.harness.agent.store.StoreItem;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Middleware that injects queued messages into the system prompt before each LLM reasoning step.
 *
 * <ol>
 *   <li>Reads the current thread ID from {@link #CURRENT_THREAD_ID} (set by the dispatcher)
 *   <li>Checks {@code SqliteBaseStore} namespace {@code ["queue", thread_id]}
 *   <li>Appends all queued messages to the system prompt via {@link #onSystemPrompt}
 *   <li>Clears the queue after injection
 * </ol>
 *
 * <p>The caller (webhook handler / CLI dispatcher) must set {@link #CURRENT_THREAD_ID} before
 * calling {@code gateway.run()} and remove it after:
 *
 * <pre>{@code
 * MessageQueueMiddleware.CURRENT_THREAD_ID.set(threadId);
 * try {
 *     gateway.run(ctx, messages, address).block();
 * } finally {
 *     MessageQueueMiddleware.CURRENT_THREAD_ID.remove();
 * }
 * }</pre>
 */
public class MessageQueueMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(MessageQueueMiddleware.class);

    /**
     * Thread-local carrying the current thread ID for the in-flight agent run. The dispatcher or
     * webhook handler must set this before calling {@code gateway.run()}.
     */
    public static final ThreadLocal<String> CURRENT_THREAD_ID = new ThreadLocal<>();

    private final BaseStore store;

    public MessageQueueMiddleware(BaseStore store) {
        this.store = store;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String threadId = CURRENT_THREAD_ID.get();
        if (threadId == null || threadId.isBlank()) {
            return Mono.just(currentPrompt);
        }
        return Mono.fromCallable(() -> drainQueue(currentPrompt, threadId))
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "[message-queue-middleware] Error draining queue for thread"
                                            + " {}: {}",
                                    threadId,
                                    e.getMessage());
                            return Mono.just(currentPrompt);
                        });
    }

    private String drainQueue(String currentPrompt, String threadId) {
        List<String> ns = List.of("queue", threadId);
        List<StoreItem> items = store.search(ns, 100, 0);
        if (items.isEmpty()) {
            return currentPrompt;
        }
        StringBuilder injected = new StringBuilder(currentPrompt != null ? currentPrompt : "");
        injected.append("\n\n[Message Queue — injected before this reasoning step]\n");
        for (StoreItem item : items) {
            Object payload = item.value().get("payload");
            if (payload != null) {
                injected.append("- ").append(payload).append("\n");
            }
            store.delete(ns, item.key());
        }
        log.debug(
                "[message-queue-middleware] Injected {} queued messages for thread {}",
                items.size(),
                threadId);
        return injected.toString();
    }
}
