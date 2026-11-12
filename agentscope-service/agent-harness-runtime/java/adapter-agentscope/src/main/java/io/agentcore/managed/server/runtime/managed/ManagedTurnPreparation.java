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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Discovery waits survive MQ Suspend without holding a callback thread or execution permit. */
public final class ManagedTurnPreparation implements AutoCloseable {
    private record Entry(CompletableFuture<Void> future, long touched) {}

    private final LinkedHashMap<String, Entry> pending = new LinkedHashMap<>(16, .75f, true);
    private boolean closed;

    public synchronized void require(String key, Supplier<CompletionStage<Void>> prepare) {
        if (closed) throw new ManagedTransportException("Turn preparation closed");
        long now = System.nanoTime();
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next().getValue();
            if (now - entry.touched() < Duration.ofMinutes(5).toNanos()) break;
            entry.future().cancel(true);
            iterator.remove();
        }
        var previous = pending.get(key);
        var future = previous == null ? prepare.get().toCompletableFuture() : previous.future();
        pending.put(key, new Entry(future, now));
        if (!future.isDone()) throw new ManagedDeliverySuspended(Duration.ofMillis(500));
        try {
            // isDone above guarantees this never blocks the MQ callback.
            future.join();
        } catch (RuntimeException failure) {
            pending.remove(key);
            throw failure;
        }
    }

    public synchronized void forget(String key) {
        var entry = pending.remove(key);
        if (entry != null) entry.future().cancel(true);
    }

    @Override
    public synchronized void close() {
        closed = true;
        pending.values().forEach(entry -> entry.future().cancel(true));
        pending.clear();
    }
}
