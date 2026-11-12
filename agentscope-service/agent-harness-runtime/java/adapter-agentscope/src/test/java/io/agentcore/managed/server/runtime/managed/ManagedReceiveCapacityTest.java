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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ManagedReceiveCapacityTest {
    @Test
    void unlimitedExecutionExceedsOldLimitButUnacknowledgedReceiptsRemainBounded() {
        var capacity = new ManagedReceiveCapacity(ManagedExecutionCapacity.of(0), 64);
        var initial = capacity.lane("initial");
        var resume = capacity.lane("resume");
        var releases = new ArrayList<Runnable>();
        try {
            for (int cycle = 0; cycle < 33; cycle++) {
                var lane = cycle % 2 == 0 ? initial : resume;
                try (var request = lane.openReceive()) {
                    var batch = request.reserve(64);
                    assertEquals(64, batch.size());
                    assertTrue(
                            resume.reserve(1).isEmpty(),
                            "Unconfirmed receipts still exhaust handoff credits");
                    for (var handoff : batch) {
                        releases.add(handoff.takeExecution());
                        handoff.confirm();
                        handoff.close();
                    }
                }
            }
            assertEquals(2112, capacity.activeExecutions());
            assertEquals(1088, initial.laneExecutions());
            assertEquals(1024, resume.laneExecutions());
            assertEquals(0, capacity.executionLimit());
            assertEquals(-1, capacity.availableExecutions());
            assertEquals(64, capacity.availableHandoffs());
        } finally {
            releases.forEach(Runnable::run);
            releases.forEach(Runnable::run);
        }
        assertEquals(0, capacity.activeExecutions());
        assertEquals(0, initial.laneExecutions());
        assertEquals(0, resume.laneExecutions());
        assertEquals(64, capacity.availableHandoffs());
        assertEquals(0, capacity.activeReceivers());
    }

    @Test
    void unlimitedWorkersStartNewFragmentsWhileEarlierFragmentsWait() throws Exception {
        var workers = ManagedResumeExecutions.executionWorkers(0);
        var started = new CountDownLatch(32);
        var release = new CountDownLatch(1);
        var interrupted = new AtomicInteger();
        try {
            for (int i = 0; i < 32; i++)
                workers.execute(
                        () -> {
                            started.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException closed) {
                                interrupted.incrementAndGet();
                                Thread.currentThread().interrupt();
                            }
                        });
            assertTrue(
                    started.await(5, TimeUnit.SECONDS),
                    "Fragments must not queue behind a fixed worker pool");
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(32, interrupted.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
        assertThrows(IllegalArgumentException.class, () -> ManagedExecutionCapacity.of(-1));
    }

    @Test
    void waitingResumeReclaimsBorrowedShareWithoutIncreasingTotalCapacity() {
        var capacity = new ManagedReceiveCapacity(new Semaphore(4), 8);
        var initial = capacity.lane("initial");
        var resume = capacity.lane("resume");
        var borrowed = initial.reserve(4);
        assertEquals(4, borrowed.size(), "An idle peer must not strand half the capacity");
        try (var pendingResume = resume.openReceive();
                var pendingInitial = initial.openReceive()) {
            assertTrue(pendingResume.reserve(4).isEmpty());
            assertTrue(pendingInitial.reserve(4).isEmpty());
            borrowed.get(0).close();
            borrowed.get(1).close();
            assertTrue(
                    pendingInitial.reserve(4).isEmpty(),
                    "Busy initial lane must yield its borrowed share");
            var resumed = pendingResume.reserve(4);
            assertEquals(2, resumed.size());
            assertEquals(2, initial.laneExecutions());
            assertEquals(2, resume.laneExecutions());
            assertEquals(0, capacity.availableExecutions());
            resumed.forEach(ManagedDeliveryHandoff::close);
            var next = pendingInitial.reserve(4);
            assertEquals(2, next.size(), "Unused resume capacity remains borrowable");
            next.forEach(ManagedDeliveryHandoff::close);
        } finally {
            borrowed.forEach(ManagedDeliveryHandoff::close);
        }
        assertEquals(4, capacity.availableExecutions());
        assertEquals(8, capacity.availableHandoffs());
        assertEquals(0, capacity.activeReceivers());
    }

    @Test
    void fifoAdmissionPreventsBargingWithOneExecutionCreditAndCancelledWaitersLeave() {
        var capacity = new ManagedReceiveCapacity(new Semaphore(1), 4);
        var initial = capacity.lane("initial");
        var resume = capacity.lane("resume");
        var held = initial.reserve(1).get(0);
        try (var first = resume.openReceive();
                var next = initial.openReceive()) {
            assertTrue(first.reserve(1).isEmpty());
            assertTrue(next.reserve(1).isEmpty());
            held.close();
            assertTrue(
                    next.reserve(1).isEmpty(), "Polling first must not overtake an older waiter");
            first.pause();
            assertEquals(0, resume.waitingReceives());
            first.close();
            assertEquals(0, resume.waitingReceives());
            var replacement = next.reserve(1);
            assertEquals(1, replacement.size());
            var execution = replacement.get(0).takeExecution();
            replacement.get(0).confirm();
            assertEquals(0, capacity.availableExecutions(), "ACK still retains execution capacity");
            replacement.get(0).close();
            execution.run();
            execution.run();
        } finally {
            held.close();
        }
        assertEquals(1, capacity.availableExecutions());
        assertEquals(4, capacity.availableHandoffs());
    }

    @Test
    void inflightEmptyPollDoesNotReserveAnEntireLaneShare() {
        var capacity = new ManagedReceiveCapacity(new Semaphore(8), 8);
        var initial = capacity.lane("initial");
        var resume = capacity.lane("resume");
        try (var poll = resume.openReceive();
                var work = initial.openReceive()) {
            var cold = poll.reserve(1);
            assertEquals(0, resume.waitingReceives());
            var busy = work.reserve(8);
            assertEquals(
                    4, busy.size()); // Per-receiver handoff batch limit, not a static lane cap.
            var remainder = initial.reserve(8);
            assertEquals(3, remainder.size());
            assertEquals(7, initial.laneExecutions());
            cold.forEach(ManagedDeliveryHandoff::close);
            busy.forEach(ManagedDeliveryHandoff::close);
            remainder.forEach(ManagedDeliveryHandoff::close);
        }
        assertEquals(8, capacity.availableExecutions());
    }

    @Test
    void warmPollsShareHandoffBudgetAcrossConsumersAndCloseRegistrationOnce() {
        var capacity = new ManagedReceiveCapacity(new Semaphore(1024), 64);
        var registrations = new ArrayList<Runnable>();
        var held = new ArrayList<ManagedDeliveryHandoff>();
        for (int i = 0; i < 8; i++) registrations.add(capacity.openReceiver());
        try {
            for (int i = 0; i < 8; i++) {
                var batch = capacity.reserveReceive(32);
                assertEquals(8, batch.size());
                held.addAll(batch);
            }
            assertEquals(0, capacity.availableHandoffs());
        } finally {
            held.forEach(ManagedDeliveryHandoff::close);
            registrations.forEach(Runnable::run);
            registrations.forEach(Runnable::run);
        }
        assertEquals(0, capacity.activeReceivers());
        assertEquals(64, capacity.availableHandoffs());
        assertEquals(1024, capacity.availableExecutions());
    }

    @Test
    void reservationsCoverOutstandingReceivesAndAckDoesNotReturnExecutionCredit() throws Exception {
        var executions = new Semaphore(3);
        var capacity = new ManagedReceiveCapacity(executions, 2);
        var batch = capacity.reserve(32);
        assertEquals(2, batch.size());
        assertTrue(capacity.reserve(32).isEmpty());
        var work = batch.get(0).takeExecution();
        assertThrows(TimeoutException.class, () -> batch.get(0).await(Duration.ofMillis(1)));
        batch.get(0).confirm();
        batch.get(0).await(Duration.ofMillis(1));
        assertEquals(1, executions.availablePermits());
        assertEquals(1, capacity.availableHandoffs());
        batch.get(1).close(); // unused tail / failed Receive
        assertEquals(2, executions.availablePermits());
        work.run();
        work.run();
        batch.forEach(ManagedDeliveryHandoff::close);
        assertEquals(3, executions.availablePermits());
        assertEquals(2, capacity.availableHandoffs());
    }

    @Test
    void closingUnconfirmedReceiptDoesNotStartPreparationOrReleaseTransferredExecution() {
        var semaphore = new Semaphore(1);
        var capacity = new ManagedReceiveCapacity(semaphore, 1);
        var gate = capacity.reserve(1).get(0);
        var release = gate.takeExecution();
        var calls = new AtomicInteger();
        gate.afterAcknowledged(calls::incrementAndGet);
        gate.close();
        gate.confirm();
        assertEquals(0, calls.get());
        assertEquals(0, semaphore.availablePermits());
        release.run();
        assertEquals(1, semaphore.availablePermits());
        assertThrows(ExecutionException.class, () -> gate.await(Duration.ofMillis(1)));
    }

    @Test
    void concurrentReceiveBatchesCannotOverbookSharedInitialTurnCapacity() throws Exception {
        var executions = new Semaphore(7);
        assertTrue(executions.tryAcquire()); // Initial Session queue shares this capacity.
        var capacity = new ManagedReceiveCapacity(executions, 20);
        var threads = Executors.newFixedThreadPool(8);
        var held = new ConcurrentLinkedQueue<ManagedDeliveryHandoff>();
        try {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 32; i++)
                jobs.add(threads.submit(() -> held.addAll(capacity.reserve(32))));
            for (var job : jobs) job.get(2, TimeUnit.SECONDS);
            assertEquals(6, held.size());
            assertEquals(0, executions.availablePermits());
            held.forEach(ManagedDeliveryHandoff::close);
            assertEquals(6, executions.availablePermits());
            assertEquals(20, capacity.availableHandoffs());
        } finally {
            threads.shutdownNow();
            executions.release();
        }
    }

    @Test
    void inMemoryTransportAlsoReservesBeforeDispatchAndKeepsCreditAfterAck() throws Exception {
        var capacity = new ManagedReceiveCapacity(new Semaphore(1), 1);
        var deliveries = new java.util.concurrent.LinkedBlockingQueue<ManagedPushDelivery>();
        var releases = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        try (var bus = new InMemoryManagedMessageBus(System::currentTimeMillis, 3)) {
            var listener =
                    new ManagedMessageBus.Listener() {
                        public ManagedReceiveCapacity receiveCapacity(
                                ManagedMessageBus.Channel channel) {
                            return capacity;
                        }

                        public void consume(ManagedPushDelivery delivery) {
                            releases.add(delivery.handoff().takeExecution());
                            deliveries.add(delivery);
                        }
                    };
            bus.subscribe(
                    ManagedMessageBus.Channel.TURN_RESUMES,
                    4,
                    listener,
                    (d, e) -> {
                        throw new AssertionError(e);
                    });
            bus.publish(
                    ManagedMessageBus.Channel.TURN_RESUMES,
                    new ManagedMessageBus.Message("a", "one", new byte[0]));
            bus.publish(
                    ManagedMessageBus.Channel.TURN_RESUMES,
                    new ManagedMessageBus.Message("b", "two", new byte[0]));
            var first = deliveries.poll(2, TimeUnit.SECONDS);
            assertNotNull(first);
            first.handoff().await(Duration.ofSeconds(2));
            assertNull(deliveries.poll(100, TimeUnit.MILLISECONDS));
            assertEquals(0, capacity.availableExecutions());
            releases.take().run();
            var second = deliveries.poll(2, TimeUnit.SECONDS);
            assertNotNull(second);
            second.handoff().await(Duration.ofSeconds(2));
            releases.take().run();
            assertEquals(1, capacity.availableExecutions());
        }
    }
}
