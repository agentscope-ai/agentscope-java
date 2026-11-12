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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedInputInbox.Type;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Message;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor.Outcome;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor.State;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.session.SessionInteractions;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Actual Harness reasoning loop; deterministic model, transport and external sandbox execution. */
@Timeout(30)
class ManagedHarnessIntegrationTest {
    @TempDir private Path managedCheckpointRoot;

    @TempDir Path root;
    private final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

    @ParameterizedTest
    @CsvSource({
        "16,5000,false,4,false",
        "2097152,5000,false,4,false",
        "16,150,false,4,false",
        "16,150,true,4,false",
        "16,150,true,1,false",
        "16,150,true,4,true"
    })
    void harnessAppliesInputDispatchesToolWorkerToolAndContinuesReasoningAfterDurableResult(
            int payloadSize,
            int inlineWaitMillis,
            boolean replaceAgent,
            int maxIters,
            boolean exhaustInlineRead)
            throws Exception {
        var mapper = new ObjectMapper();
        var scope = new ManagedScope("t", "n", "s");
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:harness-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var admission = ProjectionTestSupport.admission(source, mapper);
        var journal =
                ProjectionTestSupport.journal(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        admission.createSession(scope, "{}");
        byte[] attachment =
                Arrays.copyOf("attached-content".getBytes(StandardCharsets.UTF_8), payloadSize);
        String file =
                (String)
                        journal.files()
                                .upload(
                                        scope,
                                        "actor",
                                        "file",
                                        "source.txt",
                                        "text/plain",
                                        attachment)
                                .get("file_id");
        String turn =
                admission
                        .acceptTurn(
                                scope,
                                "actor",
                                "key",
                                mapper.writeValueAsString(
                                        Map.of(
                                                "input",
                                                List.of(
                                                        Map.of(
                                                                "role",
                                                                "user",
                                                                "content",
                                                                List.of(
                                                                        Map.of(
                                                                                "type", "text",
                                                                                "text", "run"),
                                                                        Map.of(
                                                                                "type", "file",
                                                                                "file_id",
                                                                                file)))))))
                        .id();
        var injected =
                admission.acceptInput(
                        scope,
                        turn,
                        "actor",
                        "inject",
                        Type.INJECT,
                        null,
                        mapper.writeValueAsString(
                                Map.of(
                                        "input",
                                        List.of(
                                                Map.of(
                                                        "role",
                                                        "user",
                                                        "content",
                                                        List.of(
                                                                Map.of(
                                                                        "type",
                                                                        "text",
                                                                        "text",
                                                                        "extra instruction"),
                                                                Map.of(
                                                                        "type", "file", "file_id",
                                                                        file)))))));
        var ttl = Duration.ofSeconds(4);
        var bus = new InMemoryManagedMessageBus(() -> System.nanoTime() / 1_000_000, 8);
        var leases = new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1_000_000);
        var inbox = new InMemoryManagedInputInbox(System::currentTimeMillis);
        var codec = new ManagedBatchCodec(mapper, root.resolve("private-blobs"));
        journal.transport().configure(bus, codec);
        var writer = ProjectionTestSupport.writer(journal, source, bus, codec, mapper);
        var subscriptions = new ArrayList<ManagedMessageBus.Subscription>();
        var resultDelivery = new InMemoryManagedToolResultDelivery();
        var resultRelay = new ManagedToolResultRelay(journal, codec, resultDelivery);
        var exhausted = new AtomicBoolean();
        var inlineDelivery =
                new ManagedToolResultDelivery() {
                    public void publish(ManagedScope scope, String taskId, Message message) {
                        resultDelivery.publish(scope, taskId, message);
                    }

                    public Optional<Message> await(
                            ManagedScope scope, String taskId, Duration block) {
                        if (exhaustInlineRead && exhausted.compareAndSet(false, true))
                            throw new ManagedReadCapacityException();
                        return resultDelivery.await(scope, taskId, block);
                    }
                };
        var requests =
                new ManagedToolRequests(
                        mapper, Duration.ofMillis(inlineWaitMillis), inlineDelivery, codec);
        var renewals = Executors.newScheduledThreadPool(4);
        var events = Executors.newSingleThreadExecutor();
        var toolLoops = new ArrayList<ManagedToolLoop>();
        var running = new AtomicBoolean(true);
        var toolStarts = new AtomicInteger();
        var memoryWrites = new ConcurrentHashMap<String, String>();
        var toolEnds = new ConcurrentHashMap<String, Long>();
        var model = new TwoStepModel(attachment);
        var harnessBus = mock(MessageBus.class);
        when(harnessBus.inboxDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
        when(harnessBus.queueDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
        when(harnessBus.inboxHasMessages(anyString())).thenReturn(Mono.just(false));
        when(harnessBus.queuePeek(anyString())).thenReturn(Mono.just(false));
        Supplier<HarnessAgent> agents =
                () ->
                        HarnessAgent.builder()
                                .name("actual-harness")
                                .model(model)
                                .workspace(root.resolve("workspace"))
                                .sessionLogStore(new ManagedSessionLogStore(journal))
                                .messageBus(harnessBus)
                                .asyncToolRegistry(new ManagedAsyncToolRegistry())
                                .taskRepository(new ManagedTaskRepository())
                                .abstractFilesystem(new ManagedRemoteFilesystem(requests))
                                .middleware(new ManagedRuntimeInputMiddleware(mapper))
                                .permissionContext(
                                        PermissionContextState.builder()
                                                .mode(PermissionMode.BYPASS)
                                                .build())
                                .disableCompaction()
                                .disableDefaultWorkspaceSkills()
                                .maxIters(maxIters)
                                .build();
        try (var agent = replaceAgent ? null : agents.get()) {
            var eventHandler =
                    new ManagedHarnessRuntimeToolEvents(codec, journal, resultRelay::delivered);
            for (var channel : List.of(Channel.TOOL_EVENTS))
                subscriptions.add(
                        bus.subscribe(
                                channel,
                                2,
                                eventHandler::consume,
                                (d, e) -> {
                                    errors.add(e);
                                }));
            var eventFuture =
                    events.submit(
                            () -> {
                                while (running.get()) {
                                    new ManagedResumePublisher(
                                                    journal.suspensions(), bus, leases, mapper)
                                            .retry(10);
                                    try {
                                        Thread.sleep(1);
                                    } catch (InterruptedException stopped) {
                                        Thread.currentThread().interrupt();
                                        return;
                                    }
                                }
                            });
            var toolWorker =
                    new ManagedToolExecutions(
                            leases,
                            inbox,
                            journal,
                            new ManagedToolResultPublisher(journal, bus, codec),
                            renewals,
                            ttl,
                            Duration.ofMillis(300),
                            Duration.ofMillis(10));
            var toolsLoop =
                    new ManagedToolLoop(
                            journal,
                            toolWorker,
                            1,
                            false,
                            (taskScope, ownedTask) ->
                                    new ManagedToolExecutor() {
                                        private long ends;
                                        private boolean framework;

                                        public boolean idempotentStart() {
                                            return true;
                                        }

                                        public String start(ManagedScope scope, ToolTask task) {
                                            assertTrue(
                                                    List.of(
                                                                    "sandbox.exec",
                                                                    "fs.upload",
                                                                    "fs.download")
                                                            .contains(task.name()));
                                            if (task.name().equals("fs.upload")) {
                                                try {
                                                    var data = mapper.readTree(task.arguments());
                                                    if (data.path("path")
                                                            .asText()
                                                            .startsWith("memory/"))
                                                        memoryWrites.putIfAbsent(
                                                                task.executionId(),
                                                                new String(
                                                                        Base64.getDecoder()
                                                                                .decode(
                                                                                        data.path(
                                                                                                        "content")
                                                                                                .asText()),
                                                                        StandardCharsets.UTF_8));
                                                } catch (Exception invalid) {
                                                    throw new IllegalStateException(invalid);
                                                }
                                            }
                                            framework =
                                                    !task.name().equals("sandbox.exec")
                                                            || !task.arguments()
                                                                    .contains(
                                                                            "\"command\":\"do-work\"");
                                            ends =
                                                    toolEnds.computeIfAbsent(
                                                            task.executionId(),
                                                            id -> {
                                                                if (!framework)
                                                                    toolStarts.incrementAndGet();
                                                                return System.nanoTime()
                                                                        + Duration.ofSeconds(2)
                                                                                .toNanos();
                                                            });
                                            return task.executionId();
                                        }

                                        public Outcome query(ManagedScope scope, ToolTask task) {
                                            if (task.name().equals("fs.upload")) {
                                                try {
                                                    return new Outcome(
                                                            State.SUCCEEDED,
                                                            mapper.writeValueAsString(
                                                                    Map.of(
                                                                            "path",
                                                                            mapper.readTree(
                                                                                            task
                                                                                                    .arguments())
                                                                                    .path("path")
                                                                                    .asText())));
                                                } catch (Exception invalid) {
                                                    throw new IllegalStateException(invalid);
                                                }
                                            }
                                            framework =
                                                    !task.name().equals("sandbox.exec")
                                                            || !task.arguments()
                                                                    .contains(
                                                                            "\"command\":\"do-work\"");
                                            ends = toolEnds.getOrDefault(task.executionId(), 0L);
                                            if (framework)
                                                return new Outcome(
                                                        State.SUCCEEDED,
                                                        "{\"output\":\"__NOT_FOUND__\",\"exitCode\":0,\"truncated\":false}");
                                            return System.nanoTime() < ends
                                                    ? new Outcome(State.RUNNING, null)
                                                    : new Outcome(
                                                            State.SUCCEEDED,
                                                            "{\"output\":\"hands-result\",\"exitCode\":0,\"truncated\":false}");
                                        }

                                        public Outcome cancel(ManagedScope scope, ToolTask task) {
                                            throw new AssertionError("No cancellation");
                                        }
                                    },
                            (ref, failure) -> errors.add(failure));
            toolLoops.add(toolsLoop);
            toolsLoop.start();
            new ManagedTurnPublisher(admission, bus, leases, mapper, ttl).publish(scope, turn);
            var harnessRuntime =
                    new ManagedHarnessRuntimeTurns(
                            bus,
                            leases,
                            admission,
                            journal,
                            writer,
                            mapper,
                            renewals,
                            ttl,
                            Duration.ofMillis(300),
                            1,
                            Duration.ofMillis(500));
            ManagedHarnessRuntimeTurns.Runner runner =
                    execution -> {
                        var currentAgent = replaceAgent ? agents.get() : agent;
                        var context =
                                RuntimeContext.builder()
                                        .userId("user")
                                        .sessionId(scope.sessionId())
                                        .put(ManagedScope.class, scope)
                                        .put(ManagedTurnExecution.class, execution)
                                        .put(
                                                ManagedToolRequests.Operations.class,
                                                new ManagedToolRequests.Operations())
                                        .put(ToolCallParam.CALL_CONTEXT_KEY, true)
                                        .put(MemoryFlushMiddleware.INLINE_CONTEXT_KEY, true)
                                        .put(SessionRecorder.TURN_ID_KEY, turn)
                                        .build();
                        try {
                            new ManagedHarnessTurns(
                                            mapper,
                                            new ManagedInputDelivery(admission, inbox),
                                            renewals)
                                    .run(execution, currentAgent, context);
                        } finally {
                            if (replaceAgent) currentAgent.close();
                        }
                    };
            try {
                subscriptions.add(
                        bus.subscribe(
                                Channel.TURNS,
                                4,
                                delivery -> harnessRuntime.consume(delivery, runner),
                                (d, e) -> {
                                    errors.add(e);
                                }));
                subscriptions.add(
                        bus.subscribe(
                                Channel.TURN_RESUMES,
                                4,
                                delivery -> harnessRuntime.consume(delivery, runner),
                                (d, e) -> {
                                    errors.add(e);
                                }));
                if (inlineWaitMillis < 2000) {
                    awaitTurnStatus(admission, scope, turn, "waiting_tool");
                    assertEquals(turn, admission.session(scope).currentTurnId());
                    assertEquals(0, admission.session(scope).settledSequence());
                    assertTrue(
                            SessionInteractions.pending(
                                            new ManagedMqSessionLog(
                                                    scope, null, 0, null, journal, null))
                                    .isEmpty());
                }
            } catch (Exception failure) {
                if (eventFuture.isDone()) eventFuture.get();
                throw failure;
            }
            try {
                awaitTurnStatus(admission, scope, turn, maxIters == 1 ? "failed" : "succeeded");
            } catch (AssertionError failure) {
                if (eventFuture.isDone()) eventFuture.get();
                throw new AssertionError(
                        "calls="
                                + model.calls.get()
                                + ", pending="
                                + journal.pendingTools(8)
                                + ", native="
                                + journal.recoveryEvents(scope, 0, 1000).stream()
                                        .skip(Math.max(0, journal.head(scope).sequence() - 30))
                                        .map(event -> event.seq() + ":" + event.type())
                                        .toList(),
                        failure);
            }
            long resumes =
                    new JdbcTemplate(source)
                            .queryForObject(
                                    "SELECT COUNT(*) FROM ma.turn_resume WHERE scope_key=? AND"
                                            + " turn_id=?",
                                    Long.class,
                                    scope.messageGroup(),
                                    turn);
            assertEquals(1 + resumes, admission.turn(scope, turn).executionEpoch());
            assertEquals(exhaustInlineRead, exhausted.get());
            if (inlineWaitMillis < 2000) assertTrue(resumes > 0);
            else assertEquals(0, resumes);
            harnessRuntime.close();
            var finalSnapshot = journal.objects().read(journal.head(scope).checkpoint());
            assertEquals("TERMINAL", finalSnapshot.continuation().get("resumePhase"));
            var operations =
                    (List<Map<String, Object>>) finalSnapshot.continuation().get("toolOperations");
            var business =
                    operations.stream()
                            .filter(
                                    operation ->
                                            String.valueOf(operation.get("arguments"))
                                                    .contains("do-work"))
                            .toList();
            assertEquals(1, business.size());
            assertEquals(true, business.get(0).get("resultRead"));
            assertEquals(true, business.get(0).get("applied"));
            assertFalse(mapper.writeValueAsString(operations).contains("workerToken"));
            assertEquals(maxIters == 1 ? 2 : 3, model.calls.get());
            assertEquals(1, toolStarts.get());
            assertEquals(
                    maxIters == 1 ? 0 : 1,
                    memoryWrites.size(),
                    "Memory append must use one physical write across resumes");
            var nativeFacts = journal.recoveryEvents(scope, 0, 1000);
            assertEquals(
                    maxIters == 1 ? List.of(0) : List.of(0, 1),
                    nativeFacts.stream()
                            .filter(event -> "step/start".equals(event.type()))
                            .map(event -> ((Number) event.data().get("iteration")).intValue())
                            .toList(),
                    "Rebuilding Harness must not restart a logical Turn's iteration budget");
            if (maxIters == 1)
                assertTrue(
                        nativeFacts.stream()
                                .filter(event -> "turn/output".equals(event.type()))
                                .anyMatch(event -> event.payloadJson().contains("MAX_ITERATIONS")));
            for (var modelEnd :
                    nativeFacts.stream()
                            .filter(event -> "model/end".equals(event.type()))
                            .toList()) {
                var saved =
                        journal.modelResult(
                                scope, turn, String.valueOf(modelEnd.data().get("modelCallId")));
                assertEquals(modelEnd.seq(), saved.completionSequence());
                assertEquals(modelEnd.data().get("status"), saved.status());
            }
            var startedFinalization =
                    nativeFacts.stream()
                            .filter(event -> ManagedFinalization.START.equals(event.type()))
                            .toList();
            assertEquals(maxIters == 1 ? 0 : 1, startedFinalization.size());
            if (maxIters > 1) {
                long startedAt =
                        ((Number) startedFinalization.get(0).data().get("startedAt")).longValue();
                String memory = memoryWrites.values().iterator().next();
                assertTrue(memory.contains("- Keep durable tool outcomes."));
                assertTrue(memory.contains(Instant.ofEpochMilli(startedAt).toString()));
            }
            assertEquals(
                    maxIters == 1 ? 0 : 1,
                    nativeFacts.stream()
                            .filter(event -> ManagedFinalization.END.equals(event.type()))
                            .count());
            assertEquals(
                    maxIters == 1 ? 2 : 3,
                    nativeFacts.stream()
                            .filter(event -> "model/dispatch".equals(event.type()))
                            .count());

            assertEquals("applied", admission.input(scope, injected.id()).status());
            assertEquals(
                    maxIters == 1 ? "failed" : "succeeded", admission.turn(scope, turn).status());
            var checkpointPage = journal.checkpoints().list(scope, null, null, 100);
            var checkpointRows = (List<Map<String, Object>>) checkpointPage.get("data");
            assertFalse(checkpointRows.isEmpty());
            String checkpointId =
                    (String) checkpointRows.get(checkpointRows.size() - 1).get("checkpoint_id");
            assertTrue(journal.checkpoints().files(scope, checkpointId).contains(file));
            assertFalse(mapper.writeValueAsString(checkpointPage).contains("stateJson"));
            assertTrue(
                    journal.recoveryEvents(scope, 0, 1000).stream()
                            .anyMatch(event -> event.type().equals("tool/dispatch")));
            assertTrue(
                    journal.recoveryEvents(scope, 0, 1000).stream()
                            .anyMatch(event -> event.type().equals("tool/result")));
            assertTrue(
                    ProjectionTestSupport.project(journal).publicEvents(scope, 0, 1000).stream()
                            .anyMatch(event -> event.payload().contains("finished")));
            var completedTools =
                    ProjectionTestSupport.project(journal).publicEvents(scope, 0, 1000).stream()
                            .filter(event -> event.type().equals("tool.completed"))
                            .filter(
                                    event ->
                                            JsonUtils.getJsonCodec()
                                                    .fromJson(event.payload(), Map.class)
                                                    .get("tool_call_id")
                                                    .equals("call"))
                            .toList();
            assertEquals(1, completedTools.size());
            assertEquals(
                    "completed",
                    JsonUtils.getJsonCodec()
                            .fromJson(completedTools.get(0).payload(), Map.class)
                            .get("status"));
            assertTrue(
                    ProjectionTestSupport.project(journal).publicEvents(scope, 0, 1000).stream()
                            .allMatch(
                                    event ->
                                            event.payload().getBytes(StandardCharsets.UTF_8).length
                                                    < 1024 * 1024));
            assertTrue(
                    ProjectionTestSupport.project(journal).publicEvents(scope, 0, 1000).stream()
                            .anyMatch(
                                    event ->
                                            event.payload().contains("\"type\":\"file\"")
                                                    && event.payload().contains(file)));
            running.set(false);
            eventFuture.get();
        } finally {
            running.set(false);
            subscriptions.forEach(ManagedMessageBus.Subscription::close);
            events.shutdownNow();
            toolLoops.forEach(ManagedToolLoop::close);
            renewals.shutdownNow();
            bus.close();
        }
    }

    private void awaitTurnStatus(
            ManagedAdmissionStore store, ManagedScope scope, String turn, String expected)
            throws Exception {
        // Execution now starts on a Push worker. The former ten-second check ran only after
        // synchronous inference returned; terminal waiting must include that execution time.
        // The existing thirty-second whole-test bound is retained.
        long deadline =
                System.nanoTime()
                        + Duration.ofSeconds("succeeded".equals(expected) ? 25 : 10).toNanos();
        while (!expected.equals(store.turn(scope, turn).status())
                && errors.isEmpty()
                && System.nanoTime() < deadline) Thread.sleep(10);
        if (!errors.isEmpty()) throw new AssertionError("Push callback failed", errors.peek());
        assertEquals(expected, store.turn(scope, turn).status());
    }

    private static final class TwoStepModel implements Model {
        final AtomicInteger calls = new AtomicInteger();
        private final byte[] attachment;

        TwoStepModel(byte[] attachment) {
            this.attachment = attachment;
        }

        public String getModelName() {
            return "deterministic-harness-model";
        }

        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int invocation = calls.getAndIncrement();
            if (invocation == 2) {
                assertTrue(
                        messages.stream()
                                .anyMatch(
                                        message ->
                                                message.getTextContent()
                                                        .contains("memory extraction assistant")));
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("- Keep durable tool outcomes.")
                                                        .build()))
                                .build());
            }
            if (invocation == 0) {
                assertEquals(
                        2,
                        messages.stream()
                                .flatMap(
                                        message ->
                                                message.getContentBlocks(DataBlock.class).stream())
                                .count());
                var attached =
                        messages.stream()
                                .flatMap(
                                        message ->
                                                message.getContentBlocks(DataBlock.class).stream())
                                .findFirst()
                                .orElseThrow();
                assertArrayEquals(
                        attachment,
                        Base64.getDecoder()
                                .decode(((Base64Source) attached.getSource()).getData()));
                assertTrue(
                        messages.stream()
                                .anyMatch(
                                        message ->
                                                message.getTextContent()
                                                        .contains("extra instruction")));
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("call")
                                                        .name("execute")
                                                        .content(
                                                                "{\"command\":\"do-work\",\"timeout\":10}")
                                                        .input(
                                                                Map.of(
                                                                        "command", "do-work",
                                                                        "timeout", 10))
                                                        .build()))
                                .build());
            }
            var returned =
                    messages.stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .toList();
            assertTrue(
                    returned.stream()
                            .flatMap(result -> result.getOutput().stream())
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .anyMatch(text -> text.getText().contains("hands-result")),
                    () -> "Returned tool blocks: " + JsonUtils.getJsonCodec().toJson(returned));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("finished").build()))
                            .build());
        }
    }
}
