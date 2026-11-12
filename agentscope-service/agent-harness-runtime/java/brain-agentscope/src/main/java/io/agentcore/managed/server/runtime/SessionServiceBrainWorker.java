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
package io.agentcore.managed.server.runtime;

import io.agentcore.managed.harness.runtime.ExecutionAccess;
import io.agentcore.managed.harness.runtime.SessionExecutionAccess;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedHttpExecutions;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeWriter;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedTurnExecution;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Callback admission owns local capacity; only a Service claim grants execution rights. */
public final class SessionServiceBrainWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SessionServiceBrainWorker.class);
    private final SessionExecutionAccess access;
    private final ManagedHttpExecutions executions;
    private final ExecutionAccess.Owner owner;
    private final Semaphore capacity;
    private final ExecutorService workers;
    private final ScheduledExecutorService renewals;
    private final Set<ExecutionAccess.Scope> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<ManagedTurnExecution> run;
    private final ManagedAdmissionStore admission;
    private final ManagedJournalStore journal;
    private final ManagedRuntimeWriter commits;

    public SessionServiceBrainWorker(
            SessionExecutionAccess access,
            ManagedHttpExecutions executions,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal,
            ManagedRuntimeWriter commits,
            int concurrency,
            String instanceId,
            Consumer<ManagedTurnExecution> run) {
        if (concurrency < 1 || concurrency > 256)
            throw new IllegalArgumentException("Brain concurrency must be 1..256");
        this.access = access;
        this.executions = executions;
        this.admission = admission;
        this.journal = journal;
        this.commits = commits;
        this.run = run;
        this.owner =
                new ExecutionAccess.Owner(
                        instanceId.isBlank() ? UUID.randomUUID().toString() : instanceId,
                        UUID.randomUUID().toString());
        this.capacity = new Semaphore(concurrency);
        this.workers = Executors.newFixedThreadPool(concurrency);
        this.renewals = Executors.newScheduledThreadPool(Math.min(concurrency, 8));
    }

    public boolean notify(ExecutionAccess.Candidate candidate) {
        if (closed.get() || !capacity.tryAcquire()) return false;
        if (!active.add(candidate.scope())) {
            capacity.release();
            return true;
        }
        try {
            workers.execute(() -> execute(candidate));
            return true;
        } catch (RuntimeException rejected) {
            active.remove(candidate.scope());
            capacity.release();
            return false;
        }
    }

    private void execute(ExecutionAccess.Candidate candidate) {
        try {
            if (closed.get()) return;
            var claim = access.claim(candidate, owner);
            var scope =
                    new ManagedScope(
                            claim.scope().tenant(),
                            claim.scope().namespace(),
                            claim.scope().sessionId());
            var thread = Thread.currentThread();
            try (var guard = executions.attach(claim, thread::interrupt)) {
                var renewal =
                        renewals.scheduleWithFixedDelay(
                                () -> {
                                    try {
                                        guard.renew();
                                    } catch (RuntimeException lost) {
                                        /* guard interrupts execution */
                                    }
                                },
                                10,
                                10,
                                TimeUnit.SECONDS);
                try {
                    guard.check();
                    var execution =
                            new ManagedTurnExecution(
                                    scope,
                                    claim.turnId(),
                                    Long.parseLong(claim.fence().epoch()),
                                    guard,
                                    admission,
                                    journal,
                                    commits);
                    run.accept(execution);
                    if (!execution.handedOff())
                        throw new IllegalStateException(
                                "Execution returned without durable handoff");
                } finally {
                    renewal.cancel(false);
                }
            }
        } catch (RuntimeException unavailable) {
            // Durable queue and lease expiry retain recovery; callback acceptance is never
            // completion.
            log.warn(
                    "HTTP Brain execution remains recoverable: session={}, cause={}",
                    candidate.scope().sessionId(),
                    unavailable.toString());
        } finally {
            Thread.interrupted();
            active.remove(candidate.scope());
            capacity.release();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        renewals.shutdownNow();
    }
}
