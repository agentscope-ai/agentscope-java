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
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/** Hands-owned factory for leased asynchronous executions and their ACK handoff. */
public final class ManagedAsyncToolExecutions {
    private final ManagedLeaseStore leases;
    private final ManagedToolWorkStore journal;
    private final ManagedToolResultPublisher results;
    private final ScheduledExecutorService renewals;
    private final Duration ttl;
    private final Duration renewalPeriod;
    private final Duration poll;

    public ManagedAsyncToolExecutions(
            ManagedLeaseStore leases,
            ManagedToolWorkStore journal,
            ManagedToolResultPublisher results,
            ScheduledExecutorService renewals,
            Duration ttl,
            Duration renewalPeriod,
            Duration poll) {
        ManagedMessageBus.positiveMillis(ttl);
        ManagedMessageBus.positiveMillis(renewalPeriod);
        ManagedMessageBus.positiveMillis(poll);
        if (renewalPeriod.compareTo(ttl.dividedBy(3)) > 0)
            throw new IllegalArgumentException("Tool renewal period leaves insufficient margin");
        this.leases = leases;
        this.journal = journal;
        this.results = results;
        this.renewals = renewals;
        this.ttl = ttl;
        this.renewalPeriod = renewalPeriod;
        this.poll = poll;
    }

    ManagedAsyncToolExecution asynchronous(
            ManagedScope scope,
            String taskId,
            Supplier<ManagedToolExecutor> provider,
            Executor workers,
            ManagedDeliveryHandoff handoff) {
        return new ManagedAsyncToolExecution(
                scope,
                taskId,
                provider,
                handoff,
                leases,
                journal,
                results,
                renewals,
                workers,
                ttl,
                renewalPeriod,
                poll);
    }
}
