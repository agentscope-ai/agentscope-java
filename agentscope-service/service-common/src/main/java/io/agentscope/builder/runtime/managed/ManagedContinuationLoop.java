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
package io.agentscope.builder.runtime.managed;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded direct continuation executor; committed intents survive notification loss and Runtime death. */
public final class ManagedContinuationLoop implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedContinuationLoop.class);
    private final ManagedSuspensions store;
    private final ManagedBrainTurns.Handler handler;
    private final ThreadPoolExecutor workers;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService scanner;

    public ManagedContinuationLoop(
            ManagedSuspensions store, ManagedBrainTurns.Handler handler, int concurrency) {
        this.store = store;
        this.handler = handler;
        this.workers =
                new ThreadPoolExecutor(
                        concurrency,
                        concurrency,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(concurrency));
    }

    public void wakeup() {
        for (var resume : store.direct(128)) {
            String key = resume.scope().messageGroup() + ":" + resume.resumeId();
            if (!active.add(key)) continue;
            try {
                store.attempted(resume);
                workers.execute(
                        () -> {
                            try {
                                handler.resume(resume);
                            } catch (Exception pending) {
                                log.warn(
                                        "Direct continuation remains pending: resume={}, cause={}",
                                        resume.resumeId(),
                                        pending.toString());
                            } finally {
                                active.remove(key);
                            }
                        });
            } catch (RuntimeException busy) {
                active.remove(key);
                break;
            }
        }
    }

    public synchronized void start() {
        if (scanner != null) return;
        scanner = Executors.newSingleThreadScheduledExecutor();
        scanner.scheduleWithFixedDelay(
                () -> {
                    try {
                        wakeup();
                    } catch (RuntimeException pending) {
                        log.warn("Direct continuation scan pending: {}", pending.toString());
                    }
                },
                0,
                500,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (scanner != null) scanner.shutdownNow();
        workers.shutdownNow();
    }
}
