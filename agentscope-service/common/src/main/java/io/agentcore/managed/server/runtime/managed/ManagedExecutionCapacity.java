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

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Optional execution limit. Zero disables admission limits, but still tracks active fragments. */
final class ManagedExecutionCapacity {
    private final Semaphore permits;
    private final int limit;
    private final AtomicInteger active = new AtomicInteger();

    private ManagedExecutionCapacity(Semaphore permits) {
        this.permits = permits;
        this.limit = permits == null ? 0 : permits.availablePermits();
    }

    static ManagedExecutionCapacity of(int limit) {
        if (limit < 0) throw new IllegalArgumentException("Execution limit cannot be negative");
        return new ManagedExecutionCapacity(limit == 0 ? null : new Semaphore(limit));
    }

    static ManagedExecutionCapacity bounded(Semaphore permits) {
        return new ManagedExecutionCapacity(Objects.requireNonNull(permits));
    }

    boolean tryAcquire() {
        if (permits != null && !permits.tryAcquire()) return false;
        active.incrementAndGet();
        return true;
    }

    void release() {
        active.decrementAndGet();
        if (permits != null) permits.release();
    }

    int availablePermits() {
        return permits == null ? -1 : permits.availablePermits();
    }

    boolean limited() {
        return permits != null;
    }

    int limit() {
        return limit;
    }

    int active() {
        return active.get();
    }
}
