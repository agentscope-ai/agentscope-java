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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/** Event-independent bounded tool work. A short durable pending scan repairs missed wakeups. */
public final class ManagedToolLoop implements AutoCloseable {
    private final ManagedJournalStore journal;
    private final ManagedToolExecutions executions;
    private final BiFunction<ManagedScope, String, ManagedToolExecutor> providers;
    private final BiConsumer<ManagedJournalStore.ToolReference, RuntimeException> errors;
    private final ExecutorService workers;
    private final ScheduledExecutorService dispatch = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore capacity;
    private final boolean childCalls;
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public ManagedToolLoop(
            ManagedJournalStore journal,
            ManagedToolExecutions executions,
            int concurrency,
            boolean childCalls,
            BiFunction<ManagedScope, String, ManagedToolExecutor> providers,
            BiConsumer<ManagedJournalStore.ToolReference, RuntimeException> errors) {
        if (concurrency < 1)
            throw new IllegalArgumentException("Tool concurrency must be positive");
        this.journal = journal;
        this.executions = executions;
        this.providers = providers;
        this.childCalls = childCalls;
        this.errors = errors;
        workers = Executors.newFixedThreadPool(concurrency);
        capacity = new Semaphore(concurrency);
    }

    public void start() {
        dispatch.scheduleWithFixedDelay(this::dispatch, 0, 500, TimeUnit.MILLISECONDS);
    }

    private void dispatch() {
        try {
            for (var ref : journal.pendingTools(256, childCalls)) {
                if (!capacity.tryAcquire()) return;
                String key = ref.scope().messageGroup() + ":" + ref.taskId();
                if (!active.add(key)) {
                    capacity.release();
                    continue;
                }
                try {
                    workers.submit(
                            () -> {
                                try {
                                    var provider = providers.apply(ref.scope(), ref.taskId());
                                    if (provider == null
                                            || !executions.execute(
                                                    ref.scope(), ref.taskId(), provider))
                                        journal.deferToolDispatch(ref.scope(), ref.taskId());
                                } catch (RuntimeException failure) {
                                    errors.accept(ref, failure);
                                } finally {
                                    active.remove(key);
                                    capacity.release();
                                }
                            });
                } catch (RuntimeException stopped) {
                    active.remove(key);
                    capacity.release();
                    throw stopped;
                }
            }
        } catch (RuntimeException unavailable) {
            errors.accept(null, unavailable);
        }
    }

    public void close() {
        dispatch.shutdownNow();
        workers.shutdownNow();
    }
}
