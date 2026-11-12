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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Reserves both receive/ACK and execution budgets before issuing a Receive RPC. */
public final class ManagedReceiveCapacity {
    private static final class Lane {
        int executions;
        int waiting;
    }

    private static final class Budget {
        final ManagedExecutionCapacity executions;
        final Semaphore handoffs;
        final int executionLimit;
        final int handoffLimit;
        final AtomicInteger receivers = new AtomicInteger();
        final Map<String, Lane> lanes = new LinkedHashMap<>();
        final ArrayDeque<ReceiveRequest> waiting = new ArrayDeque<>();

        Budget(ManagedExecutionCapacity executions, int handoffs) {
            this.executions = executions;
            this.handoffs = new Semaphore(handoffs);
            this.executionLimit = executions.limit();
            this.handoffLimit = handoffs;
        }
    }

    private final Budget budget;
    private final Lane lane;

    public ManagedReceiveCapacity(Semaphore executions, int handoffs) {
        this(ManagedExecutionCapacity.bounded(executions), handoffs);
    }

    ManagedReceiveCapacity(ManagedExecutionCapacity executions, int handoffs) {
        if (handoffs < 1) throw new IllegalArgumentException("Handoff capacity must be positive");
        this.budget = new Budget(executions, handoffs);
        this.lane = null;
    }

    private ManagedReceiveCapacity(Budget budget, Lane lane) {
        this.budget = budget;
        this.lane = lane;
    }

    /** Views share the same total budget. Idle lanes lend all their unused execution capacity. */
    public ManagedReceiveCapacity lane(String name) {
        synchronized (budget) {
            return new ManagedReceiveCapacity(
                    budget, budget.lanes.computeIfAbsent(name, ignored -> new Lane()));
        }
    }

    /** Includes pending and in-flight polls across every consumer sharing this execution budget. */
    public Runnable openReceiver() {
        budget.receivers.incrementAndGet();
        var closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) budget.receivers.decrementAndGet();
        };
    }

    /** One request waits fairly until admitted; a broker long-poll no longer counts as waiting. */
    public ReceiveRequest openReceive() {
        return new ReceiveRequest();
    }

    public final class ReceiveRequest implements AutoCloseable {
        private final Runnable closeReceiver = openReceiver();
        private boolean waiting;
        private boolean closed;

        public List<ManagedDeliveryHandoff> reserve(int maximum) {
            synchronized (budget) {
                if (closed || maximum < 1) return List.of();
                if (!waiting) {
                    waiting = true;
                    budget.waiting.add(this);
                    if (lane != null) lane.waiting++;
                }
                // Skip an over-share lane while its peer is waiting. FIFO among eligible polls
                // prevents a busy receiver from barging again, including a one-credit budget.
                var next = budget.waiting.stream().filter(ReceiveRequest::eligible).findFirst();
                if (next.isEmpty() || next.get() != this) return List.of();
                var reserved = reserveReceive(maximum);
                if (!reserved.isEmpty()) stopWaiting();
                return reserved;
            }
        }

        private boolean eligible() {
            return !closed && mayReserve();
        }

        private void stopWaiting() {
            if (!waiting) return;
            waiting = false;
            budget.waiting.remove(this);
            if (lane != null) lane.waiting--;
        }

        /** A transport backoff/uncertain receipt must not block eligible polls from other lanes. */
        public void pause() {
            synchronized (budget) {
                stopWaiting();
            }
        }

        @Override
        public void close() {
            synchronized (budget) {
                if (closed) return;
                closed = true;
                stopWaiting();
                closeReceiver.run();
            }
        }
    }

    private boolean mayReserve() {
        if (!budget.executions.limited() || lane == null || budget.lanes.size() < 2) return true;
        boolean competing =
                budget.lanes.values().stream().anyMatch(peer -> peer != lane && peer.waiting > 0);
        int share = (budget.executionLimit + budget.lanes.size() - 1) / budget.lanes.size();
        return !competing || lane.executions < share;
    }

    public int activeReceivers() {
        return budget.receivers.get();
    }

    public int laneExecutions() {
        synchronized (budget) {
            return lane == null ? 0 : lane.executions;
        }
    }

    public int waitingReceives() {
        synchronized (budget) {
            return lane == null ? budget.waiting.size() : lane.waiting;
        }
    }

    public List<ManagedDeliveryHandoff> reserveReceive(int maximum) {
        return reserve(
                Math.min(
                        maximum,
                        Math.max(1, budget.handoffLimit / Math.max(1, activeReceivers()))));
    }

    public List<ManagedDeliveryHandoff> reserve(int maximum) {
        synchronized (budget) {
            var reserved = new ArrayList<ManagedDeliveryHandoff>();
            for (int i = 0; i < maximum && mayReserve() && budget.handoffs.tryAcquire(); i++) {
                if (!budget.executions.tryAcquire()) {
                    budget.handoffs.release();
                    break;
                }
                if (lane != null) lane.executions++;
                reserved.add(
                        new ManagedDeliveryHandoff(
                                this::releaseExecution, budget.handoffs::release));
            }
            return reserved;
        }
    }

    private void releaseExecution() {
        synchronized (budget) {
            if (lane != null) lane.executions--;
            budget.executions.release();
        }
    }

    public int executionLimit() {
        return budget.executionLimit;
    }

    public int activeExecutions() {
        return budget.executions.active();
    }

    /** -1 means execution admission is unlimited; the receive/ACK budget remains bounded. */
    public int availableExecutions() {
        return budget.executions.availablePermits();
    }

    public int availableHandoffs() {
        return budget.handoffs.availablePermits();
    }
}
