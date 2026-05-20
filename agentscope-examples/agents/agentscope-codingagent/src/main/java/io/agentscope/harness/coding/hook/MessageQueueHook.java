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
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.harness.agent.store.BaseStore;
import io.agentscope.harness.agent.store.StoreItem;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that injects queued messages before each LLM reasoning step.
 *
 * <p>Mirrors open-swe's {@code check_message_queue_before_model} middleware. On each
 * {@link PreReasoningEvent}:
 *
 * <ol>
 *   <li>Reads the current thread ID from {@link #CURRENT_THREAD_ID} (set by the dispatcher)
 *   <li>Checks {@code SqliteBaseStore} namespace {@code ["queue", thread_id]}
 *   <li>Appends all queued messages to the system content via {@link
 *       HookEvent#appendSystemContent(String)}
 *   <li>Clears the queue after injection
 * </ol>
 *
 * <p>The caller (webhook handler / CLI dispatcher) must set {@link #CURRENT_THREAD_ID} before
 * calling {@code gateway.run()} and remove it after:
 *
 * <pre>{@code
 * MessageQueueHook.CURRENT_THREAD_ID.set(threadId);
 * try {
 *     gateway.run(ctx, messages, address).block();
 * } finally {
 *     MessageQueueHook.CURRENT_THREAD_ID.remove();
 * }
 * }</pre>
 */
public class MessageQueueHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(MessageQueueHook.class);

    /**
     * Thread-local carrying the current thread ID for the in-flight agent run. The dispatcher or
     * webhook handler must set this before calling {@code gateway.run()}.
     */
    public static final ThreadLocal<String> CURRENT_THREAD_ID = new ThreadLocal<>();

    private final BaseStore store;

    public MessageQueueHook(BaseStore store) {
        this.store = store;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreReasoningEvent)) {
            return Mono.just(event);
        }
        String threadId = CURRENT_THREAD_ID.get();
        if (threadId == null || threadId.isBlank()) {
            return Mono.just(event);
        }
        return Mono.fromCallable(
                        () -> {
                            drainQueue(event, threadId);
                            return event;
                        })
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "[message-queue-hook] Error draining queue for thread {}: {}",
                                    threadId,
                                    e.getMessage());
                            return Mono.just(event);
                        });
    }

    private void drainQueue(HookEvent event, String threadId) {
        List<String> ns = List.of("queue", threadId);
        List<StoreItem> items = store.search(ns, 100, 0);
        if (items.isEmpty()) {
            return;
        }
        StringBuilder injected = new StringBuilder();
        injected.append("\n\n[Message Queue — injected before this reasoning step]\n");
        for (StoreItem item : items) {
            Object payload = item.value().get("payload");
            if (payload != null) {
                injected.append("- ").append(payload).append("\n");
            }
            store.delete(ns, item.key());
        }
        event.appendSystemContent(injected.toString());
        log.debug(
                "[message-queue-hook] Injected {} queued messages for thread {}",
                items.size(),
                threadId);
    }
}
