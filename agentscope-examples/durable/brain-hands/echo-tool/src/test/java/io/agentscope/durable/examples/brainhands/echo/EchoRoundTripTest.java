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
package io.agentscope.durable.examples.brainhands.echo;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTurn;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.ExecutionMessages.Command;
import io.agentscope.durable.ExecutionMessages.Event;
import io.agentscope.durable.ExecutionMessages.Kind;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.worker.QueuedExecutor;
import io.agentscope.durable.worker.TransactionalExecutionStore;
import io.agentscope.durable.worker.WorkerRuntime;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Production Brain and Hands with public queue APIs, no RocketMQ or external model connection. */
@Timeout(60)
class EchoRoundTripTest {
    @TempDir Path directory;

    @Test
    void roundTripDuplicatesAndNextTurnKeepIndependentCallAndExecutionIdentities()
            throws Exception {
        try (var fixture = new Fixture(directory)) {
            var first = fixture.submit("request-1");
            await(() -> first.status().status().terminal(), fixture::pump);
            assertSuccess(first);
            var firstCommand = fixture.execution(first.turnId());
            assertThat(fixture.events)
                    .anyMatch(
                            event ->
                                    event.result() != null
                                            && event.result().state() == ExecutionState.SUCCEEDED
                                            && "echo: {}".equals(event.result().result().asText()));
            int commandCount = fixture.commands.size();
            var duplicate = fixture.submit("request-1");
            assertThat(duplicate.turnId()).isEqualTo(first.turnId());
            for (int i = 0; i < 3; i++) fixture.pump();
            assertSuccess(duplicate);
            assertThat(fixture.commands).hasSize(commandCount);

            var second = fixture.submit("request-2");
            await(() -> second.status().status().terminal(), fixture::pump);
            assertSuccess(second);
            var secondCommand = fixture.execution(second.turnId());
            assertThat(second.turnId()).isNotEqualTo(first.turnId());
            assertThat(secondCommand.request().call().callId())
                    .isEqualTo("echo-" + second.turnId())
                    .isNotEqualTo(firstCommand.request().call().callId());
            assertThat(secondCommand.request().executionId())
                    .isNotEqualTo(firstCommand.request().executionId());
            assertThat(fixture.errors).isEmpty();
        }
    }

    @Test
    void restartBeforeResultHandoffReplaysSameExecutionOntoOriginalTurn() throws Exception {
        String turnId;
        Command original;
        try (var fixture = new Fixture(directory)) {
            var turn = fixture.submit("request-1");
            await(() -> !fixture.commands.isEmpty(), () -> fixture.pump(false));
            original = fixture.execution(turn.turnId());
            await(
                    () ->
                            fixture.hands.query(original.request()).state()
                                    == ExecutionState.SUCCEEDED,
                    () -> fixture.pump(false));
            assertThat(turn.status().status()).isEqualTo(TurnStatus.WAITING);
            assertThat(fixture.events).isEmpty();
            assertThat(fixture.hands.pendingEvent(fixture.hands.keys(null, 10).get(0))).isPresent();
            turnId = turn.turnId();
            assertThat(fixture.errors).isEmpty();
        }
        try (var fixture = new Fixture(directory)) {
            fixture.receive(original);
            fixture.receive(original);
            var turn = fixture.submit("request-1");
            assertThat(turn.turnId()).isEqualTo(turnId);
            await(() -> turn.status().status().terminal(), fixture::pump);
            assertSuccess(turn);
            assertThat(fixture.hands.query(original.request()).handle())
                    .isEqualTo(original.request().executionId());
            assertThat(fixture.errors).isEmpty();
        }
    }

    private static void assertSuccess(DurableTurn turn) {
        var result = turn.result();
        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(result.output().path("content").path(0).path("text").asText())
                .isEqualTo(EchoBrainExample.FINAL_ANSWER);
    }

    private static void await(BooleanSupplier condition, Runnable pump) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline)
                throw new AssertionError("Timed out driving echo round trip");
            pump.run();
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final SessionKey key = new SessionKey("demo", "conversation");
        final ThreadKey thread = new ThreadKey(key, "main");
        final Queue<Command> pending = new ArrayDeque<>();
        final List<Command> commands = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        final Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        final EchoHandsExample example = new EchoHandsExample(new EchoToolProperties());
        final SessionBackend backend;
        final JdbcRecordStore records;
        final TransactionalExecutionStore hands;
        final WorkerRuntime worker;
        final QueuedExecutor queue;
        final DurableRuntime runtime;

        Fixture(Path directory) {
            backend = LocalBackend.open(directory.resolve("brain"));
            records = JdbcRecordStore.sqlite(directory.resolve("hands.db"));
            hands = new TransactionalExecutionStore(records, "hands");
            worker =
                    WorkerRuntime.builder()
                            .executionStore(hands)
                            .executor("hands", EchoHandsExample.echoExecutor())
                            .pollInterval(Duration.ofMillis(10))
                            .onError(errors::add)
                            .build();
            queue =
                    new QueuedExecutor(
                            "hands",
                            backend.localStorage().orElseThrow().records(),
                            backend,
                            pending::add,
                            Duration.ofMillis(10));
            runtime = EchoBrainExample.runtime(backend, queue);
        }

        DurableTurn submit(String requestId) {
            return runtime.createSession(key, EchoBrainExample.HARNESS)
                    .submit(requestId, "Call echo");
        }

        Command execution(String turnId) {
            return commands.stream()
                    .filter(command -> command.kind() == Kind.EXECUTE)
                    .filter(command -> command.request().turnId().equals(turnId))
                    .findFirst()
                    .orElseThrow();
        }

        void receive(Command command) {
            example.authorize(command);
            hands.receive(command);
        }

        void pump() {
            pump(true);
        }

        void pump(boolean publish) {
            runtime.tick(thread);
            queue.tick(errors::add);
            while (!pending.isEmpty()) {
                var command = pending.remove();
                commands.add(command);
                receive(command);
                receive(command);
            }
            worker.tick();
            if (publish) {
                for (var execution : hands.keys(null, 100)) {
                    while (hands.pendingEvent(execution).isPresent()) {
                        var event = hands.pendingEvent(execution).orElseThrow();
                        events.add(event);
                        queue.receive(event);
                        queue.receive(event);
                        hands.published(event);
                    }
                }
            }
        }

        @Override
        public void close() {
            runtime.close();
            worker.close();
            records.close();
            backend.close();
        }
    }
}
