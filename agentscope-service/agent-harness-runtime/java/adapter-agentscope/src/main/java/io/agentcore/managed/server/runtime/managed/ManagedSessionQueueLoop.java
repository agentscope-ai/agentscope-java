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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

/** Durable dispatch scan is the recovery path even after MQ ACK or notification loss. */
public final class ManagedSessionQueueLoop implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedSessionQueueLoop.class);
    private final ManagedSessionQueue queue;
    private final ManagedHarnessRuntimeTurns.Handler handler;
    private final ThreadPoolExecutor workers;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService scanner;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> preparing =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ManagedSessionQueueLoop(
            ManagedSessionQueue queue,
            ManagedHarnessRuntimeTurns.Handler handler,
            int concurrency) {
        this.queue = queue;
        this.handler = handler;
        workers =
                new ThreadPoolExecutor(
                        concurrency,
                        concurrency,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(concurrency));
    }

    public void scan() {
        if (closed.get()) return;
        for (var scope : queue.due(128)) {
            if (!active.add(scope.messageGroup())) continue;
            try {
                workers.execute(() -> prepare(scope));
            } catch (RuntimeException busy) {
                active.remove(scope.messageGroup());
                break;
            }
        }
    }

    private void prepare(ManagedScope scope) {
        ManagedSessionQueue.Dispatch dispatch = null;
        try {
            if (closed.get()) {
                active.remove(scope.messageGroup());
                return;
            }
            dispatch = queue.acquire(scope);
            if (dispatch == null) {
                active.remove(scope.messageGroup());
                return;
            }
            String turn = queue.candidate(scope);
            if (turn == null) {
                release(dispatch);
                return;
            }
            var owned = dispatch;
            var pending = handler.prepare(scope, turn).toCompletableFuture();
            preparing.put(scope.messageGroup(), pending);
            pending.whenComplete(
                    (ignored, failure) -> {
                        preparing.remove(scope.messageGroup(), pending);
                        try {
                            workers.execute(
                                    () -> {
                                        try {
                                            if (failure != null)
                                                log.warn(
                                                        "Session discovery remains pending:"
                                                                + " session={}, cause={}",
                                                        scope.sessionId(),
                                                        failure.toString());
                                            else if (!closed.get()) handler.advance(scope, turn);
                                        } catch (Exception unavailable) {
                                            log.warn(
                                                    "Session queue remains pending: session={},"
                                                            + " cause={}",
                                                    scope.sessionId(),
                                                    unavailable.toString());
                                        } finally {
                                            release(owned);
                                        }
                                    });
                        } catch (RuntimeException busy) {
                            // No JDBC on a transport callback. The durable scanner will retry
                            // execution.
                            Schedulers.boundedElastic().schedule(() -> release(owned));
                        }
                    });
            if (closed.get()) pending.cancel(true);
        } catch (Exception failure) {
            try {
                log.warn(
                        "Session queue remains pending: session={}, cause={}",
                        scope.sessionId(),
                        failure.toString());
                if (dispatch != null) release(dispatch);
            } finally {
                active.remove(scope.messageGroup());
            }
        }
    }

    private void release(ManagedSessionQueue.Dispatch dispatch) {
        try {
            queue.release(dispatch);
        } catch (RuntimeException pending) {
            log.warn("Session dispatch release pending: {}", pending.toString());
        } finally {
            active.remove(dispatch.scope().messageGroup());
        }
    }

    public synchronized void start() {
        if (scanner != null || closed.get()) return;
        scanner = Executors.newSingleThreadScheduledExecutor();
        scanner.scheduleWithFixedDelay(
                () -> {
                    try {
                        scan();
                    } catch (RuntimeException pending) {
                        log.warn("Session dispatch scan pending: {}", pending.toString());
                    }
                },
                0,
                250,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        closed.set(true);
        if (scanner != null) scanner.shutdownNow();
        preparing.values().forEach(pending -> pending.cancel(true));
        workers.shutdownNow();
    }
}
