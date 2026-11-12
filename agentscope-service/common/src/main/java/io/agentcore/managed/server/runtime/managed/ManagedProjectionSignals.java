/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local post-commit wakeup; the outbox, not delivery of this hint, guarantees publication. */
final class ManagedProjectionSignals {
    private static final ConcurrentHashMap<DataSource, Set<Runnable>> LISTENERS =
            new ConcurrentHashMap<>();

    static AutoCloseable subscribe(DataSource source, Runnable callback) {
        LISTENERS.computeIfAbsent(source, key -> ConcurrentHashMap.newKeySet()).add(callback);
        return () ->
                LISTENERS.computeIfPresent(
                        source,
                        (key, set) -> {
                            set.remove(callback);
                            return set.isEmpty() ? null : set;
                        });
    }

    static void committed(DataSource source) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        var callbacks = LISTENERS.get(source);
                        if (callbacks != null)
                            for (var callback : callbacks) {
                                try {
                                    callback.run();
                                } catch (RuntimeException ignored) {
                                    /* Indexed repair retains ownership. */
                                }
                            }
                    }
                });
    }
}
