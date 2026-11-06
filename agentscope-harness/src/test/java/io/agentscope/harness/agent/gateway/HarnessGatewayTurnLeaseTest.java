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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * A gateway turn publisher may be subscribed more than once (a client that re-subscribes after a
 * disconnect, or a caller that reuses the returned {@code Mono}). Each subscription acquires its
 * own turn lease, so a lease must be released by exactly the subscription that acquired it -
 * otherwise one subscriber's cancellation ends another's serialization, and the abandoned lease
 * leaves the session stuck busy.
 */
@DisplayName("HarnessGateway turn leases are per-subscription")
class HarnessGatewayTurnLeaseTest {

    @Test
    @DisplayName("a completed turn closes its own lease, not the queued one")
    void completedTurnReleasesOnlyItsOwnLease() {
        RecordingGate gate = new RecordingGate();
        HarnessGateway gw = HarnessGateway.create();
        gw.setSessionTurnGate(gate);

        List<Sinks.One<io.agentscope.core.message.Msg>> turns = new CopyOnWriteArrayList<>();
        Mono<io.agentscope.core.message.Msg> publisher =
                gw.withGatedTurn(
                        "chatui|g:team|r:room-1",
                        () -> {
                            Sinks.One<io.agentscope.core.message.Msg> turn = Sinks.one();
                            turns.add(turn);
                            return turn.asMono();
                        });

        Disposable first = publisher.subscribe();
        awaitUntil("first subscription to acquire", () -> turns.size() == 1);
        Disposable second = publisher.subscribe();
        awaitUntil("second subscription to acquire", () -> turns.size() == 2);
        assertEquals(2, gate.leases.size());

        // The first turn finishes while the second is still pending.
        turns.get(0).tryEmitEmpty();
        awaitUntil("the first lease to be released", () -> gate.leases.get(0).closes == 1);

        // The second subscription still holds its own slot; nothing above may have released it.
        assertEquals(
                0,
                gate.leases.get(1).closes,
                "the queued subscription's lease was closed by another subscriber");
        assertTrue(gate.isRunning("chatui|g:team|r:room-1"));

        second.dispose();
        awaitUntil("the second lease to be released", () -> gate.leases.get(1).closes == 1);
        assertEquals(1, gate.leases.get(0).closes, "lease must close exactly once");
        assertEquals(1, gate.leases.get(1).closes, "lease must close exactly once");
        assertFalse(gate.isRunning("chatui|g:team|r:room-1"));

        first.dispose();
    }

    @Test
    @DisplayName("a stream subscription releases its own lease on cancellation")
    void cancelledStreamReleasesOnlyItsOwnLease() {
        RecordingGate gate = new RecordingGate();
        HarnessGateway gw = HarnessGateway.create();
        gw.setSessionTurnGate(gate);

        List<Sinks.Many<io.agentscope.core.event.AgentEvent>> streams =
                new CopyOnWriteArrayList<>();
        Flux<io.agentscope.core.event.AgentEvent> publisher =
                gw.withGatedStream(
                        "chatui|g:team|r:room-1",
                        () -> {
                            Sinks.Many<io.agentscope.core.event.AgentEvent> stream =
                                    Sinks.many().unicast().onBackpressureBuffer();
                            streams.add(stream);
                            return stream.asFlux();
                        });

        Disposable first = publisher.subscribe();
        awaitUntil("first stream subscription", () -> streams.size() == 1);
        Disposable second = publisher.subscribe();
        awaitUntil("second stream subscription", () -> streams.size() == 2);

        second.dispose();
        awaitUntil(
                "the cancelled stream lease to be released", () -> gate.leases.get(1).closes == 1);
        assertEquals(
                0,
                gate.leases.get(0).closes,
                "cancelling the second stream subscriber closed the in-flight turn's lease");

        streams.get(0).tryEmitComplete();
        awaitUntil("the first stream lease to be released", () -> gate.leases.get(0).closes == 1);
        first.dispose();
    }

    /** Records every lease handed out and how many times each was closed. */
    private static final class RecordingGate implements SessionTurnGate {

        private final List<RecordingLease> leases = new CopyOnWriteArrayList<>();

        @Override
        public TurnLease acquire(String key) {
            RecordingLease lease = new RecordingLease();
            leases.add(lease);
            return lease;
        }

        @Override
        public boolean isRunning(String key) {
            return leases.stream().anyMatch(lease -> lease.closes == 0);
        }
    }

    private static final class RecordingLease implements TurnLease {

        private volatile int closes = 0;

        @Override
        public void close() {
            closes++;
        }
    }

    private static void awaitUntil(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        fail("Timed out waiting for " + what);
    }
}
