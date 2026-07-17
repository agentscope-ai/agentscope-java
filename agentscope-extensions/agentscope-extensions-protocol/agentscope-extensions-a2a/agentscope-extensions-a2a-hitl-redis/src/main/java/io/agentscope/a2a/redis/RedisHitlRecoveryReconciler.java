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
package io.agentscope.a2a.redis;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reconciles abandoned CLAIMED handoffs through the coordinator's claimed-only index. */
public final class RedisHitlRecoveryReconciler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisHitlRecoveryReconciler.class);

    private final RedisHitlResumeCoordinator coordinator;
    private final Duration claimTimeout;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public RedisHitlRecoveryReconciler(
            RedisHitlResumeCoordinator coordinator, Duration claimTimeout, Duration interval) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.claimTimeout =
                RedisTaskStore.requirePositiveMillis(claimTimeout, "claimRecoveryTimeout");
        this.interval = RedisTaskStore.requirePositiveMillis(interval, "reconcilerInterval");
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "a2a-hitl-recovery-reconciler");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Redis HITL reconciler is closed");
        }
        if (started.compareAndSet(false, true)) {
            scheduler.scheduleWithFixedDelay(
                    this::reconcileSafely,
                    interval.toMillis(),
                    interval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    public int reconcileOnce() {
        return coordinator.reconcileClaimed(claimTimeout);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
        }
    }

    private void reconcileSafely() {
        try {
            int recovered = reconcileOnce();
            if (recovered > 0) {
                log.warn("Marked {} abandoned A2A HITL handoff(s) RECOVERY_REQUIRED", recovered);
            }
        } catch (RuntimeException exception) {
            log.error("Failed to reconcile abandoned A2A HITL handoffs", exception);
        }
    }
}
