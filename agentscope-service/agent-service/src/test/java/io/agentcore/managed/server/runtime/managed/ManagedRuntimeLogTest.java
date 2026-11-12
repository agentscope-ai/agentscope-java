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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Message;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Published;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.InputReceipt;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor.Outcome;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor.State;
import io.agentscope.core.session.SessionEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedRuntimeLogTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Map<Logger, Level> levels = new LinkedHashMap<>();
    private final ManagedScope scope =
            new ManagedScope("tenant", "ns", "session-" + UUID.randomUUID());
    private ManagedAdmissionStore admission;
    private ManagedJournalStore journal;
    private ManagedBatchCodec codec;
    private ManagedMessageBus bus;
    private ManagedHarnessRuntimeToolEvents toolWorker;
    private String turn;
    private final List<Message> published = new ArrayList<>();

    @BeforeEach
    void setup() {
        appender.start();
        for (var type :
                List.of(
                        ManagedRuntimeLogTest.class,
                        ManagedRuntimeWriter.class,
                        ManagedHarnessRuntimeToolEvents.class,
                        ManagedJournalStore.class,
                        ManagedToolExecutions.class)) {
            var logger = (Logger) LoggerFactory.getLogger(type);
            levels.put(logger, logger.getLevel());
            logger.setLevel(Level.INFO);
            logger.addAppender(appender);
        }
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:runtime-log-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        admission = ProjectionTestSupport.admission(source, mapper);
        journal =
                ProjectionTestSupport.journal(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, root.resolve("checkpoints")));
        codec = new ManagedBatchCodec(mapper, root.resolve("batches"));
        admission.createSession(scope, "{}");
        turn = admission.acceptTurn(scope, "user", "first", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "HARNESS_RUNTIME_TOKEN_SECRET");
        bus = mock(ManagedMessageBus.class);
        when(bus.publish(any(), any()))
                .thenAnswer(
                        call -> {
                            published.add(call.getArgument(1));
                            return new Published("broker-" + published.size());
                        });
        journal.transport().configure(bus, codec);
        toolWorker = new ManagedHarnessRuntimeToolEvents(codec, journal, r -> {});
    }

    @AfterEach
    void detach() {
        levels.forEach(
                (logger, level) -> {
                    logger.detachAppender(appender);
                    logger.setLevel(level);
                });
        appender.stop();
    }

    @Test
    void verboseReceiptHonorsDebugWithoutSuppressingLifecycleOrFailure() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(ManagedRuntimeLogTest.class);
        logger.setLevel(Level.INFO);
        ManagedRuntimeLog.write(logger, true, "receipt.verbose", scope, turn);
        ManagedRuntimeLog.info(logger, "receipt.failed", scope, turn);
        assertTrue(events("receipt.verbose").isEmpty());
        assertEquals(1, events("receipt.failed").size());
        logger.setLevel(Level.DEBUG);
        ManagedRuntimeLog.write(logger, true, "receipt.verbose", scope, turn);
        assertEquals(1, events("receipt.verbose").size());
        assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.DEBUG));
    }

    @Test
    void asynchronousMetadataKeepsProcessIdentityAndDoesNotLeakSessionScope() throws Exception {
        var logger = LoggerFactory.getLogger(ManagedRuntimeLogTest.class);
        var other = new ManagedScope("other", "ns", "quoted\"session\nnext-line");
        ManagedRuntimeLog.info(
                logger, "harness_runtime.test", scope, turn, "session_id", "spoofed");
        var worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(
                            () ->
                                    ManagedRuntimeLog.info(
                                            logger, "tool_worker.test", other, "other-turn"))
                    .get();
        } finally {
            worker.shutdownNow();
        }
        var first = events("harness_runtime.test").get(0);
        var second = events("tool_worker.test").get(0);
        assertEquals(scope.sessionId(), first.path("session_id").asText());
        assertEquals(other.sessionId(), second.path("session_id").asText());
        for (String field : List.of("instance_id", "host", "pid", "boot_id")) {
            assertEquals(first.get(field), second.get(field));
            assertFalse(first.get(field).asText().isBlank());
        }
        assertEquals(ProcessHandle.current().pid(), first.path("pid").asLong());
        assertNotNull(Instant.parse(first.path("timestamp").asText()));
        assertTrue(
                appender.list.stream()
                        .noneMatch(event -> event.getFormattedMessage().contains("\n")));
    }

    @Test
    void toolLogsWhitelistIdentifiersWithoutSerializingArgumentsResultsOrTokens() throws Exception {
        var task =
                new ToolTask(
                        "task",
                        turn,
                        "call",
                        "execution",
                        "exec",
                        "ARGUMENT_SECRET",
                        1,
                        "running",
                        "HANDLE_SECRET",
                        "RESULT_SECRET",
                        "WORKER_TOKEN_SECRET");
        ManagedRuntimeLog.tool(
                LoggerFactory.getLogger(ManagedRuntimeLogTest.class),
                "tool_worker.tool.test",
                scope,
                task);
        var event = events("tool_worker.tool.test").get(0);
        assertEquals("execution", event.path("execution_id").asText());
        assertEquals(turn, event.path("turn_id").asText());
        assertFalse(event.toString().contains("SECRET"));
    }

    @Test
    void harnessRuntimeLogsOnlyDurablyCommittedStateAndNeverCheckpointContents() throws Exception {
        var batch = checkpoint(List.of());
        var reference = journal.objects().reference(scope, batch.events().get(0));
        new ManagedRuntimeWriter(journal, new SessionCommitTransactions(journal))
                .commit(batch, mock(ManagedExecutionGuard.class));
        var committed = events("harness_runtime.checkpoint.committed").get(0);
        assertEquals(reference.identity().checkpointId(), committed.path("checkpoint_id").asText());
        assertEquals(scope.sessionId(), committed.path("session_id").asText());
        assertEquals(1, journal.head(scope).sequence());
        assertEquals(0, published.size());
        assertTrue(journal.objects().state(reference).contains("CONTEXT_SECRET"));
        assertTrue(structured().stream().noneMatch(event -> event.toString().contains("SECRET")));
    }

    @Test
    void rolledBackCheckpointDoesNotEmitCommitSuccess() throws Exception {
        var batch = checkpoint(List.of(new InputReceipt("missing-input", "applied", null)));
        assertThrows(
                RuntimeException.class,
                () ->
                        new ManagedRuntimeWriter(journal, new SessionCommitTransactions(journal))
                                .commit(batch, mock(ManagedExecutionGuard.class)));
        assertEquals(0, journal.head(scope).sequence());
        assertTrue(events("harness_runtime.checkpoint.committed").isEmpty());
    }

    @Test
    void toolTakeoverQueriesOriginalExecutionWithoutLoggingAnotherStart() throws Exception {
        AgentScopePreparation.commit(
                journal, new SessionCommitTransactions(journal), checkpoint(List.of()));
        String taskId = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "call");
        journal.claimTool(scope, taskId, "OLD_WORKER_TOKEN_SECRET");
        journal.recordToolHandle(scope, taskId, "OLD_WORKER_TOKEN_SECRET", "HANDLE_SECRET");
        var starts = new AtomicInteger();
        var executor =
                new ManagedToolExecutor() {
                    public String start(ManagedScope owner, ToolTask task) {
                        starts.incrementAndGet();
                        return task.executionId();
                    }

                    public Outcome query(ManagedScope owner, ToolTask task) {
                        return new Outcome(
                                State.SUCCEEDED, "{\"output\":\"RESULT_SECRET\",\"exitCode\":0}");
                    }

                    public Outcome cancel(ManagedScope owner, ToolTask task) {
                        throw new AssertionError("Unexpected cancellation");
                    }
                };
        var renewals = Executors.newSingleThreadScheduledExecutor();
        try {
            var worker =
                    new ManagedToolExecutions(
                            new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1_000_000),
                            new InMemoryManagedInputInbox(() -> System.nanoTime() / 1_000_000),
                            journal,
                            new ManagedToolResultPublisher(journal, bus, codec),
                            renewals,
                            Duration.ofSeconds(3),
                            Duration.ofMillis(200),
                            Duration.ofMillis(10));
            assertTrue(worker.execute(scope, taskId, executor));
        } finally {
            renewals.shutdownNow();
        }
        assertEquals(0, starts.get());
        assertTrue(events("tool_worker.tool.started").isEmpty());
        assertEquals(
                taskId,
                events("tool_worker.tool.querying_existing").get(0).path("execution_id").asText());
        assertEquals(1, events("tool_worker.tool.result.captured").size());
        assertTrue(events("tool_worker.tool.result.committed").isEmpty());
        for (var message : List.copyOf(published)) consume(message, Channel.TOOL_EVENTS);
        var committed = events("tool_worker.tool.result.committed").get(0);
        assertEquals(turn, committed.path("turn_id").asText());
        assertEquals(taskId, committed.path("execution_id").asText());
        assertTrue(structured().stream().noneMatch(event -> event.toString().contains("SECRET")));
    }

    private ManagedRuntimeBatch checkpoint(List<InputReceipt> inputs) throws Exception {
        var event =
                new SessionEvent(
                        1,
                        "checkpoint",
                        1,
                        1,
                        "state/checkpoint",
                        "run",
                        turn,
                        true,
                        mapper.writeValueAsString(
                                Map.of(
                                        "stateJson",
                                        "{\"context\":[],\"summary\":\"CONTEXT_SECRET\"}",
                                        "reason",
                                        "test")));
        return AgentScopeCheckpoints.prepare(
                journal.objects(),
                new ManagedRuntimeBatch(
                        scope,
                        turn,
                        1,
                        "HARNESS_RUNTIME_TOKEN_SECRET",
                        "writer",
                        "batch",
                        0,
                        List.of(event),
                        List.of(new ToolIntent("call", "exec", "{\"secret\":\"ARGUMENT_SECRET\"}")),
                        inputs,
                        null));
    }

    private void consume(Message message, Channel channel) {
        toolWorker.consume(
                new ManagedPushDelivery(
                        UUID.randomUUID().toString(),
                        "consumer",
                        channel,
                        "topic",
                        "group",
                        "broker",
                        0,
                        message));
    }

    private List<JsonNode> events(String name) throws Exception {
        return structured().stream()
                .filter(event -> name.equals(event.path("event").asText()))
                .toList();
    }

    private List<JsonNode> structured() throws Exception {
        var result = new ArrayList<JsonNode>();
        for (var event : appender.list) {
            String message = event.getFormattedMessage();
            if (message.startsWith(ManagedRuntimeLog.PREFIX))
                result.add(mapper.readTree(message.substring(ManagedRuntimeLog.PREFIX.length())));
        }
        return result;
    }
}
