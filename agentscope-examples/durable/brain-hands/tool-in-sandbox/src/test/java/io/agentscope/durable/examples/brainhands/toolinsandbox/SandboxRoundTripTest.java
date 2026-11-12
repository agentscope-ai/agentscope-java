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
package io.agentscope.durable.examples.brainhands.toolinsandbox;

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
import io.agentscope.extensions.durable.sandbox.SandboxBindings;
import java.nio.file.Files;
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

/** Public queue/store handoff with real local commands; does not validate RocketMQ SDK or Broker. */
@Timeout(60)
class SandboxRoundTripTest {
    @TempDir Path directory;

    @Test
    void realSandboxRoundTripAppliesSuccessfulResultAndDuplicateRequestDoesNotRunAgain()
            throws Exception {
        try (var fixture = new Fixture(directory)) {
            var turn = fixture.submit();
            await(() -> turn.status().status().terminal(), () -> fixture.pump(true));

            assertSuccess(turn);
            assertThat(fixture.commands).anyMatch(command -> command.kind() == Kind.EXECUTE);
            assertThat(fixture.events)
                    .anyMatch(
                            event ->
                                    event.result() != null
                                            && event.result().state() == ExecutionState.SUCCEEDED);
            var status = fixture.bindings.status(fixture.example.resourceBinding());
            assertThat(status.ready()).isTrue();
            assertThat(status.deleted()).isFalse();
            assertThat(Files.readString(fixture.sandboxDirectory().resolve("count")))
                    .isEqualTo("once");

            var duplicate = fixture.submit();
            assertThat(duplicate.turnId()).isEqualTo(turn.turnId());
            for (int i = 0; i < 3; i++) fixture.pump(true);
            assertSuccess(duplicate);
            assertThat(Files.readString(fixture.sandboxDirectory().resolve("count")))
                    .isEqualTo("once");
            assertThat(fixture.errors).isEmpty();
        }
    }

    @Test
    void newRequestOnSameThreadExecutesAgainInOriginalSandboxAndDuplicateDoesNotRunAgain()
            throws Exception {
        try (var fixture = new Fixture(directory)) {
            var first = fixture.submit("request-1");
            await(() -> first.status().status().terminal(), () -> fixture.pump(true));
            assertSuccess(first);
            var firstTurnId = first.turnId();
            var resourceId =
                    fixture.bindings.status(fixture.example.resourceBinding()).resourceId();
            var firstCallId =
                    fixture.commands.stream()
                            .filter(command -> command.kind() == Kind.EXECUTE)
                            .filter(command -> command.request().turnId().equals(firstTurnId))
                            .findFirst()
                            .orElseThrow()
                            .request()
                            .call()
                            .callId();

            var second = fixture.submit("request-2");
            await(() -> second.status().status().terminal(), () -> fixture.pump(true));
            var secondExecutions =
                    fixture.commands.stream()
                            .filter(command -> command.kind() == Kind.EXECUTE)
                            .filter(command -> command.request().turnId().equals(second.turnId()))
                            .toList();
            assertThat(secondExecutions)
                    .as("request-2 must dispatch a new EXECUTE, not reuse request-1's ToolResult")
                    .isNotEmpty();
            assertThat(second.turnId()).isNotEqualTo(firstTurnId);
            assertThat(secondExecutions.get(0).request().call().callId()).isNotEqualTo(firstCallId);
            var status = fixture.bindings.status(fixture.example.resourceBinding());
            assertThat(status.resourceId()).isEqualTo(resourceId);
            assertThat(status.deleted()).isFalse();
            assertThat(Files.readString(fixture.sandboxDirectory().resolve("count")))
                    .isEqualTo("onceonce");
            assertSuccess(second);
            assertThat(fixture.errors).isEmpty();

            var commandCount = fixture.commands.size();
            var duplicate = fixture.submit("request-2");
            assertThat(duplicate.turnId()).isEqualTo(second.turnId());
            for (int i = 0; i < 3; i++) fixture.pump(true);
            assertSuccess(duplicate);
            assertThat(fixture.commands).hasSize(commandCount);
            assertThat(Files.readString(fixture.sandboxDirectory().resolve("count")))
                    .isEqualTo("onceonce");
            assertThat(fixture.errors).isEmpty();
        }
    }

    @Test
    void restartBeforeResultHandoffReplaysEventsOntoSameTurnAndRetainsOriginalSandbox()
            throws Exception {
        String turnId;
        String resourceId;
        String originalHandle;
        Command original;
        try (var fixture = new Fixture(directory)) {
            var turn = fixture.submit();
            await(() -> !fixture.commands.isEmpty(), () -> fixture.pump(false));
            original =
                    fixture.commands.stream()
                            .filter(command -> command.kind() == Kind.EXECUTE)
                            .findFirst()
                            .orElseThrow();
            var request = original.request();
            await(
                    () -> fixture.hands.query(request).state() == ExecutionState.SUCCEEDED,
                    () -> fixture.pump(false));
            assertThat(turn.status().status()).isEqualTo(TurnStatus.WAITING);
            assertThat(fixture.events).isEmpty(); // The result exists only on the Hands side.
            assertThat(fixture.hands.pendingEvent(fixture.hands.keys(null, 10).get(0))).isPresent();
            turnId = turn.turnId();
            resourceId = fixture.bindings.status(fixture.example.resourceBinding()).resourceId();
            var originalResult =
                    fixture.bindings
                            .executor(fixture.example.resourceBinding(), SandboxTool.tool())
                            .query(original.request(), null);
            assertThat(originalResult.state()).isEqualTo(ExecutionState.SUCCEEDED);
            assertThat(originalResult.handle()).isNotNull().startsWith(resourceId + ":");
            originalHandle = originalResult.handle();
            assertThat(Files.readString(fixture.sandboxDirectory().resolve("count")))
                    .isEqualTo("once");
            assertThat(fixture.errors).isEmpty();
        }

        // Reopen both SQLite stores, bindings, worker, queue and Brain harness, not just a new
        // Turn.
        try (var fixture = new Fixture(directory)) {
            fixture.receive(original);
            fixture.receive(original); // Duplicate command after a lost acknowledgement.
            var turn = fixture.submit();
            assertThat(turn.turnId()).isEqualTo(turnId);
            await(() -> turn.status().status().terminal(), () -> fixture.pump(true));
            assertSuccess(turn);
            var stored = fixture.hands.query(original.request());
            assertThat(stored.state()).isEqualTo(ExecutionState.SUCCEEDED);
            var attached =
                    fixture.bindings
                            .executor(fixture.example.resourceBinding(), SandboxTool.tool())
                            .query(original.request(), originalHandle);
            assertThat(attached.state()).isEqualTo(ExecutionState.SUCCEEDED);
            assertThat(attached.handle()).isEqualTo(originalHandle);
            assertThat(attached.result().path("output").asText()).isEqualTo("recovered");
            assertThat(fixture.bindings.status(fixture.example.resourceBinding()).resourceId())
                    .isEqualTo(resourceId);
            assertThat(fixture.bindings.status(fixture.example.resourceBinding()).deleted())
                    .isFalse();
            assertThat(Files.readString(fixture.sandboxDirectory().resolve("count")))
                    .isEqualTo("once");
            assertThat(fixture.events)
                    .anyMatch(
                            event ->
                                    event.result() != null
                                            && event.result().state() == ExecutionState.SUCCEEDED);
            assertThat(fixture.errors).isEmpty();
        }

        // A completed request also survives a full restart without a new Sandbox or command.
        try (var fixture = new Fixture(directory)) {
            var turn = fixture.submit();
            assertThat(turn.turnId()).isEqualTo(turnId);
            assertSuccess(turn);
            for (int i = 0; i < 3; i++) fixture.pump(true);
            assertThat(fixture.commands).isEmpty();
            assertThat(fixture.bindings.status(fixture.example.resourceBinding()).resourceId())
                    .isEqualTo(resourceId);
            assertThat(Files.readString(fixture.sandboxDirectory().resolve("count")))
                    .isEqualTo("once");
            assertThat(fixture.errors).isEmpty();
        }
    }

    private static void assertSuccess(DurableTurn turn) {
        var result = turn.result();
        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(result.output().path("content").path(0).path("text").asText())
                .isEqualTo(SandboxTool.FINAL_ANSWER);
    }

    private static void await(BooleanSupplier condition, Runnable pump) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline)
                throw new AssertionError("Timed out driving the real queued Sandbox round trip");
            pump.run();
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    /** Only transport delivery is in memory; all Brain, Hands and Sandbox evidence is persistent. */
    private static final class Fixture implements AutoCloseable {
        final Path workerDirectory;
        final SessionKey key = new SessionKey("demo", "conversation");
        final ThreadKey thread = new ThreadKey(key, "main");
        final Queue<Command> pending = new ArrayDeque<>();
        final List<Command> commands = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        final Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        final SandboxHandsExample example;
        final SessionBackend backend;
        final JdbcRecordStore records;
        final TransactionalExecutionStore hands;
        final SandboxBindings bindings;
        final WorkerRuntime worker;
        final QueuedExecutor queue;
        final DurableRuntime runtime;

        Fixture(Path directory) throws Exception {
            workerDirectory = Files.createDirectories(directory.resolve("worker"));
            var properties = new SandboxToolProperties();
            properties.setDataDirectory(workerDirectory);
            example = new SandboxHandsExample(properties);
            backend = LocalBackend.open(Files.createDirectories(directory.resolve("brain")));
            records = JdbcRecordStore.sqlite(workerDirectory.resolve("hands.db"));
            hands = new TransactionalExecutionStore(records, "hands");
            bindings = SandboxHandsExample.sandboxBindings(records, workerDirectory);
            worker =
                    WorkerRuntime.builder()
                            .executionStore(hands)
                            .executor(
                                    "hands",
                                    bindings.executor(
                                            example.resourceBinding(), SandboxTool.tool()))
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
            runtime = SandboxBrainExample.runtime(backend, queue);
        }

        DurableTurn submit() {
            return submit("request-1");
        }

        DurableTurn submit(String requestId) {
            return runtime.createSession(key, SandboxBrainExample.HARNESS)
                    .submit(requestId, "Run the sandbox command");
        }

        void receive(Command command) {
            example.authorize(command);
            hands.receive(command);
        }

        void pump(boolean publish) {
            runtime.tick(thread);
            queue.tick(errors::add);
            while (!pending.isEmpty()) {
                var command = pending.remove();
                commands.add(command);
                receive(command);
                receive(command); // Each command is deliberately delivered twice.
            }
            bindings.tick();
            worker.tick();
            if (publish) {
                for (var execution : hands.keys(null, 100)) {
                    while (hands.pendingEvent(execution).isPresent()) {
                        var event = hands.pendingEvent(execution).orElseThrow();
                        events.add(event);
                        queue.receive(event);
                        queue.receive(event); // Duplicate event after a lost acknowledgement.
                        hands.published(event);
                    }
                }
            }
        }

        Path sandboxDirectory() {
            return workerDirectory
                    .resolve("sandboxes")
                    .resolve(bindings.status(example.resourceBinding()).resourceId());
        }

        @Override
        public void close() {
            runtime.close();
            worker.close();
            bindings.close();
            records.close();
            backend.close();
        }
    }
}
