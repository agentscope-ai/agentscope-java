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
package io.agentscope.harness.agent.hook;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.legacy.hook.ErrorEvent;
import io.agentscope.core.legacy.hook.Hook;
import io.agentscope.core.legacy.hook.HookEvent;
import io.agentscope.core.legacy.hook.PostCallEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that persists agent state after each call via {@link io.agentscope.core.storage.StorageBase}.
 *
 * <p>Only agents that are {@link ReActAgent} instances with a configured {@code StorageBase} are
 * persisted. Agents without storage are skipped with a debug log.
 *
 * <p>Priority is 900 (low) so this hook runs after other hooks like {@link MemoryFlushHook}.
 */
public class StatePersistenceHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(StatePersistenceHook.class);

    @Override
    public int priority() {
        return 900;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostCallEvent || event instanceof ErrorEvent) {
            return autoSave(event.getAgent()).thenReturn(event);
        }
        return Mono.just(event);
    }

    private Mono<Void> autoSave(Agent agent) {
        if (agent instanceof ReActAgent ra) {
            if (ra.getStorage() == null) {
                log.debug(
                        "StatePersistenceHook skipped for '{}': no StorageBase configured",
                        agent.getName());
                return Mono.empty();
            }
            return ra.saveStateToStorage()
                    .onErrorResume(
                            e -> {
                                log.warn(
                                        "Auto-save via StorageBase failed for '{}': {}",
                                        agent.getName(),
                                        e.getMessage());
                                return Mono.empty();
                            });
        }
        return Mono.empty();
    }
}
