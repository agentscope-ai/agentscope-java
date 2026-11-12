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

import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import io.micrometer.core.instrument.Metrics;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Best-effort cross-replica SSE wakeup; visible query state and cursor repair remain authoritative. */
public final class ManagedProjectionWakeups implements AutoCloseable {
    private static final String KEY = "projection-visible-v3";
    private final RedisManagedInfrastructure redis;
    private final SessionEventNotifier notifier;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor sender =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(256),
                    r -> {
                        var t = new Thread(r, "projection-visible-signal");
                        t.setDaemon(true);
                        return t;
                    });
    private AutoCloseable listener;

    public ManagedProjectionWakeups(
            RedisManagedInfrastructure redis, SessionEventNotifier notifier) {
        this.redis = redis;
        this.notifier = notifier;
    }

    public void start() {
        listener = redis.busListen(KEY, notifier::refresh);
    }

    public void visible(ManagedScope scope) {
        String key = scope.messageGroup();
        notifier.refresh(key);
        if (!pending.add(key)) return;
        try {
            sender.execute(
                    () -> {
                        try {
                            redis.busPublish(KEY, key);
                        } catch (RuntimeException unavailable) {
                            Metrics.counter("managed.agents.projection.wakeup.dropped").increment();
                        } finally {
                            pending.remove(key);
                        }
                    });
        } catch (RuntimeException full) {
            pending.remove(key);
            Metrics.counter("managed.agents.projection.wakeup.dropped").increment();
        }
    }

    @Override
    public void close() {
        sender.shutdownNow();
        pending.clear();
        if (listener != null)
            try {
                listener.close();
            } catch (Exception ignored) {
            }
    }
}
