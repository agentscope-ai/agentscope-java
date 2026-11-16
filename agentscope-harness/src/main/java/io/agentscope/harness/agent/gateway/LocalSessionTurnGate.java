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
package io.agentscope.harness.agent.gateway;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-local fair per-key mutual exclusion for gateway turns.
 *
 * <p>Uses a fair {@link Semaphore} per key. {@link #acquire(String)} blocks until the slot is
 * available; it never throws {@link TurnBusyException}.
 */
public final class LocalSessionTurnGate implements SessionTurnGate {

    private final ConcurrentHashMap<String, Gate> gates = new ConcurrentHashMap<>();

    @Override
    public TurnLease acquire(String key) throws InterruptedException {
        Gate gate =
                gates.compute(
                        key,
                        (k, existing) -> {
                            Gate target = existing != null ? existing : new Gate();
                            target.leases++;
                            return target;
                        });
        try {
            gate.semaphore.acquire();
        } catch (InterruptedException e) {
            retire(key, gate);
            throw e;
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                gate.semaphore.release();
                retire(key, gate);
            }
        };
    }

    @Override
    public boolean isRunning(String key) {
        Gate gate = gates.get(key);
        return gate != null && gate.semaphore.availablePermits() == 0;
    }

    /**
     * Drops one lease from {@code gate} and evicts the key once no thread holds or waits for it.
     *
     * <p>The decrement and the removal run inside {@link ConcurrentHashMap#compute}, so they are
     * atomic with respect to a concurrent {@link #acquire(String)} on the same key: an acquirer
     * either joins the existing gate or, once it is gone, builds a fresh one. It can never obtain
     * a second semaphore for a key that still has a holder. The eviction is therefore driven by
     * the lease count rather than by {@link Semaphore#getQueueLength()}, which cannot see a thread
     * that took a just-released permit without ever entering the queue.
     */
    private void retire(String key, Gate gate) {
        gates.compute(
                key,
                (k, existing) -> {
                    if (existing != gate) {
                        return existing;
                    }
                    return --existing.leases == 0 ? null : existing;
                });
    }

    /** One fair semaphore plus the number of leases currently holding or waiting on it. */
    private static final class Gate {

        private final Semaphore semaphore = new Semaphore(1, true);

        /** Holders plus waiters; mutated only while holding the map's bin lock. */
        private int leases;
    }
}
