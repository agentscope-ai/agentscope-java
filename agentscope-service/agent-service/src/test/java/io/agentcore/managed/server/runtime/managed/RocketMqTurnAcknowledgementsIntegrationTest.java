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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentcore.managed.server.runtime.managed.ManagedTurnPublisher.Command;
import io.agentscope.core.session.SessionEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.apache.rocketmq.client.java.impl.consumer.ManagedLitePushConsumer.Ack;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Production Push adapter + Turn/Resume/journal/ACK-outbox paths with real MQ and PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "MANAGED_MQ_TEST_ENDPOINT", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MANAGED_DATABASE_TEST_URI", matches = ".+")
@Timeout(150)
class RocketMqTurnAcknowledgementsIntegrationTest {
    @TempDir Path checkpoints;
    final ObjectMapper mapper = new ObjectMapper();
    final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

    @ParameterizedTest
    @ValueSource(strings = {"peer-ack", "owner-stopped", "expired-receipt"})
    void longSuspendRetainsFifoAndAnotherReplicaReleasesAfterDurableTerminal(String scenario)
            throws Exception {
        assumeTrue(
                System.getenv("MANAGED_TURN_ACK_SCENARIO") == null
                        || scenario.equals(System.getenv("MANAGED_TURN_ACK_SCENARIO")));
        var source =
                new DriverManagerDataSource(
                        System.getenv("MANAGED_DATABASE_TEST_URI"),
                        "managed",
                        "managed-local-test");
        ManagedAdmissionStore.initialize(source);
        var admission = ProjectionTestSupport.admission(source, mapper);
        var journal =
                ProjectionTestSupport.journal(
                        source, mapper, new ManagedCheckpointObjects(mapper, checkpoints));
        var scope = new ManagedScope("ack-it", "test", UUID.randomUUID().toString());
        admission.createSession(scope, "{}");
        String t1 = admission.acceptTurn(scope, "user", "t1", "{}").id();
        String t2 = admission.acceptTurn(scope, "user", "t2", "{}").id();
        String task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), t1, "call");
        var leases = new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1000000);
        var timer = Executors.newScheduledThreadPool(2);
        var closing = new ArrayList<AutoCloseable>();
        var firstOwner = new AtomicInteger(-1);
        var firstSeen = new AtomicLong();
        var t2Seen = new AtomicLong();
        var originalCalls = new AtomicInteger();
        var receiptCount = new AtomicInteger();
        var oldReceipt = new AtomicReference<RocketMqTurnReceipt>();
        var order = new ConcurrentLinkedQueue<String>();
        var buses = List.of(connect(), connect());
        var outboxes = new ArrayList<ManagedTurnAcknowledgements>();
        try {
            for (int i = 0; i < 2; i++) {
                int replica = i;
                var bus = buses.get(i);
                var outbox =
                        new ManagedTurnAcknowledgements(
                                new SessionTurnAckLedger(source, mapper),
                                bus.turnTopic(),
                                bus.turnConsumerGroup(),
                                bus::acknowledgeTurn);
                outboxes.add(outbox);
                bus.turnAcknowledgements(
                        (view, delay) -> {
                            try {
                                var body = view.getBody().duplicate();
                                byte[] bytes = new byte[body.remaining()];
                                body.get(bytes);
                                var receipt = RocketMqTurnReceipt.capture((MessageViewImpl) view);
                                outbox.suspended(
                                        mapper.readValue(bytes, Command.class), receipt, delay);
                                oldReceipt.compareAndSet(null, receipt);
                                receiptCount.incrementAndGet();
                            } catch (Exception error) {
                                errors.add(error);
                            }
                        });
                var runtime =
                        new ManagedHarnessRuntimeTurns(
                                bus,
                                leases,
                                admission,
                                journal,
                                new ManagedRuntimeWriter(
                                        journal, new SessionCommitTransactions(journal)),
                                mapper,
                                timer,
                                Duration.ofSeconds(60),
                                Duration.ofSeconds(15),
                                1);
                closing.add(runtime);
                for (var channel : List.of(Channel.TURNS, Channel.TURN_RESUMES)) {
                    bus.subscribe(
                            channel,
                            4,
                            delivery -> {
                                // These parent topics belong exclusively to this fixture.
                                // Settle remnants of an interrupted previous invocation separately.
                                if (!scope.messageGroup().equals(delivery.message().messageGroup()))
                                    return;
                                if (channel == Channel.TURNS
                                        && delivery.message().id().equals(t1)) {
                                    originalCalls.incrementAndGet();
                                    firstOwner.compareAndSet(-1, replica);
                                    firstSeen.compareAndSet(0, System.nanoTime());
                                }
                                runtime.consume(
                                        delivery,
                                        execution -> {
                                            order.add(execution.turnId() + ":" + execution.epoch());
                                            if (execution.turnId().equals(t1)
                                                    && execution.epoch() == 1) {
                                                checkpoint(
                                                        execution,
                                                        List.of(
                                                                new ToolIntent(
                                                                        "call",
                                                                        "sandbox.exec",
                                                                        "{}")));
                                                execution.suspend(task);
                                            } else {
                                                if (execution.turnId().equals(t2)) {
                                                    assertEquals(
                                                            "succeeded",
                                                            admission.turn(scope, t1).status());
                                                    t2Seen.set(System.nanoTime());
                                                }
                                                checkpoint(execution, List.of());
                                                if (execution.turnId().equals(t1))
                                                    execution.toolCompleted(task);
                                                execution.closeInputs();
                                                execution.finish("succeeded");
                                            }
                                        });
                            },
                            (delivery, error) -> errors.add(error));
                }
            }
            var publisher =
                    new ManagedTurnPublisher(
                            admission, buses.get(0), leases, mapper, Duration.ofSeconds(60));
            assertTrue(publisher.publish(scope, t1));
            assertTrue(publisher.publish(scope, t2));
            await(
                    () ->
                            "waiting_tool".equals(admission.turn(scope, t1).status())
                                    && receiptCount.get() >= 1,
                    20);
            int owner = firstOwner.get(), peer = 1 - owner;
            if (scenario.equals("owner-stopped")) buses.get(owner).close();
            long completeAfter = scenario.equals("expired-receipt") ? 65000 : 5000;
            await(() -> (System.nanoTime() - firstSeen.get()) / 1000000 >= completeAfter, 70);
            assertEquals(0, t2Seen.get(), "MQ must retain T2 throughout the suspended T1");
            if (scenario.equals("expired-receipt")) {
                assertTrue(
                        receiptCount.get() >= 2, "60-second timeout must capture a fresh receipt");
                assertEquals(Ack.EXPIRED, buses.get(peer).acknowledgeTurn(oldReceipt.get()));
            } else if (scenario.equals("peer-ack"))
                assertEquals(1, originalCalls.get(), "No 500ms periodic TURNS deliveries");
            journal.claimTool(scope, task, "worker");
            ManagedPushTestSupport.commitToolResult(
                    journal, new ManagedToolResult(scope, task, task, "succeeded", "{}"));
            new ManagedResumePublisher(journal.suspensions(), buses.get(peer), leases, mapper)
                    .retry(16);
            await(() -> "succeeded".equals(admission.turn(scope, t1).status()), 10);
            long terminal = System.nanoTime();
            var acked = new AtomicInteger();
            await(
                    () -> {
                        acked.addAndGet(outboxes.get(peer).retry(16));
                        return "succeeded".equals(admission.turn(scope, t2).status());
                    },
                    5);
            assertTrue(
                    acked.get() >= 1, "Peer must proactively ACK the original durable T1 receipt");
            assertEquals(List.of(t1 + ":1", t1 + ":2", t2 + ":3"), List.copyOf(order));
            long millis = (t2Seen.get() - terminal) / 1000000;
            assertTrue(millis < 5000, "T2 must not wait for the 60-second Suspend");
            assertTrue(errors.isEmpty(), errors::toString);
            System.out.println(
                    "TURN_ACK_IT scenario="
                            + scenario
                            + " owner="
                            + owner
                            + " ack_replica="
                            + peer
                            + " original_callbacks="
                            + originalCalls
                            + " terminal_to_t2_ms="
                            + millis
                            + " order="
                            + order);
        } finally {
            for (var close : closing) close.close();
            CompletableFuture.allOf(
                            buses.stream()
                                    .map(bus -> CompletableFuture.runAsync(bus::close))
                                    .toArray(CompletableFuture[]::new))
                    .get(45, TimeUnit.SECONDS);
            timer.shutdownNow();
            new JdbcTemplate(source)
                    .update(
                            "DELETE FROM ma.turn_ack_receipt WHERE scope_key=?",
                            scope.messageGroup());
        }
    }

    private void checkpoint(ManagedTurnExecution execution, List<ToolIntent> tools) {
        long seq = execution.head().sequence() + 1;
        var event =
                new SessionEvent(
                        1,
                        "state_" + UUID.randomUUID(),
                        seq,
                        1,
                        "state/checkpoint",
                        "run",
                        execution.turnId(),
                        true,
                        "{\"stateJson\":\"{\\\"context\\\":[]}\",\"reason\":\"test\"}");
        execution.boundary("state_" + UUID.randomUUID(), List.of(event), tools, List.of(), null);
    }

    private void await(BooleanSupplier condition, int seconds) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until && errors.isEmpty())
            Thread.sleep(20);
        assertTrue(errors.isEmpty(), errors::toString);
        assertTrue(condition.getAsBoolean(), "Condition did not become true");
    }

    private RocketMqManagedMessageBus connect() {
        return RocketMqManagedMessageBus.connect(
                new RocketMqManagedMessageBus.Settings(
                        System.getenv("MANAGED_MQ_TEST_ENDPOINT"),
                        "ma_turns_v3",
                        "ma_turn_resumes_v3",
                        "ma_tool_events_v3",
                        "ma_tool_execute_v1",
                        "ma_tool_control_v1",
                        Set.of(Channel.TURNS, Channel.TURN_RESUMES),
                        Map.of(
                                Channel.TURNS,
                                "ma_brain_v3",
                                Channel.TURN_RESUMES,
                                "ma_brain_resumes_v3"),
                        32,
                        1048576,
                        false,
                        null));
    }
}
