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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

/** Checks that each subscription owns only the turn lease it acquired. */
class HarnessGatewayTurnGateTest {

    @ParameterizedTest
    @CsvSource({
        "false,complete",
        "true,complete",
        "false,error",
        "true,error",
        "false,supplier",
        "true,supplier",
        "false,cancel",
        "true,cancel"
    })
    void releasesOnlyAcquiredLeaseForEveryTerminalPath(boolean streaming, String terminal)
            throws Exception {
        LocalSessionTurnGate gate = new LocalSessionTurnGate();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        HarnessGateway gateway = HarnessGateway.create();
        gateway.setSessionTurnGate(
                new SessionTurnGate() {
                    @Override
                    public TurnLease acquire(String key) throws InterruptedException {
                        TurnLease lease = gate.acquire(key);
                        return () -> {
                            lease.close();
                            closes.incrementAndGet();
                            closed.countDown();
                        };
                    }

                    @Override
                    public boolean isRunning(String key) {
                        return gate.isRunning(key);
                    }
                });
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getName()).thenReturn("terminal-test");
        IllegalStateException failure = new IllegalStateException("fixture failure");
        if (terminal.equals("supplier")) {
            when(agent.call(anyList(), any(RuntimeContext.class))).thenThrow(failure);
            when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenThrow(failure);
        } else {
            Mono<Msg> call =
                    switch (terminal) {
                        case "complete" -> Mono.just(Msg.builder().textContent("ok").build());
                        case "error" -> Mono.error(failure);
                        default -> Mono.never();
                    };
            Flux<AgentEvent> stream =
                    switch (terminal) {
                        case "complete" -> Flux.empty();
                        case "error" -> Flux.error(failure);
                        default -> Flux.never();
                    };
            when(agent.call(anyList(), any(RuntimeContext.class)))
                    .thenReturn(call.doOnSubscribe(ignored -> entered.countDown()));
            when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                    .thenReturn(stream.doOnSubscribe(ignored -> entered.countDown()));
        }
        gateway.bindMainAgent(agent);
        MsgContext context = new MsgContext("chatui", null, "terminal", null, null, Map.of());
        Flux<?> publisher =
                streaming
                        ? gateway.runStream(context, List.of(), null)
                        : gateway.run(context, List.of()).flux();
        if (terminal.equals("cancel")) {
            Disposable subscription = publisher.subscribe();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
            } finally {
                subscription.dispose();
            }
        } else {
            Signal<?> result = publisher.materialize().blockLast(Duration.ofSeconds(5));
            assertNotNull(result);
            assertEquals(terminal.equals("complete"), result.isOnComplete());
            assertEquals(!terminal.equals("complete"), result.isOnError());
        }
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertEquals(1, closes.get());
        assertFalse(gate.isRunning(context.canonicalKey()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellingQueuedSubscriptionDoesNotReleaseActiveTurn(boolean streaming) throws Exception {
        LocalSessionTurnGate localGate = new LocalSessionTurnGate();
        AtomicInteger acquisitions = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch leaseClosed = new CountDownLatch(1);
        HarnessGateway gateway = HarnessGateway.create();
        gateway.setSessionTurnGate(
                new SessionTurnGate() {
                    @Override
                    public TurnLease acquire(String key) throws InterruptedException {
                        boolean second = acquisitions.incrementAndGet() == 2;
                        if (second) {
                            secondStarted.countDown();
                        }
                        try {
                            TurnLease lease = localGate.acquire(key);
                            return () -> {
                                closes.incrementAndGet();
                                leaseClosed.countDown();
                                lease.close();
                            };
                        } finally {
                            if (second) {
                                secondFinished.countDown();
                            }
                        }
                    }

                    @Override
                    public boolean isRunning(String key) {
                        return localGate.isRunning(key);
                    }
                });
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getName()).thenReturn("gate-test");
        when(agent.call(anyList(), any(RuntimeContext.class)))
                .thenReturn(Mono.<Msg>never().doOnSubscribe(ignored -> firstEntered.countDown()));
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.<AgentEvent>never()
                                .doOnSubscribe(ignored -> firstEntered.countDown()));
        gateway.bindMainAgent(agent);
        MsgContext context =
                new MsgContext("chatui", null, "shared-publisher", null, null, Map.of());
        Flux<?> shared =
                streaming
                        ? gateway.runStream(context, List.of(), null)
                        : gateway.run(context, List.of()).flux();
        Disposable first = shared.subscribe();
        Disposable second = null;
        try {
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            second = shared.subscribe();
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            second.dispose();
            assertTrue(secondFinished.await(5, TimeUnit.SECONDS));
            assertFalse(leaseClosed.await(1, TimeUnit.SECONDS), "the first turn is still active");
            assertEquals(
                    0, closes.get(), "a cancelled waiter must not close the active turn's lease");
            assertTrue(localGate.isRunning(context.canonicalKey()));
        } finally {
            if (second != null) {
                second.dispose();
            }
            first.dispose();
        }
    }
}
