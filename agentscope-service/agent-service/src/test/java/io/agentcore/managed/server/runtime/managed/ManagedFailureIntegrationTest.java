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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.runtime.managed.ManagedInputInbox.Type;
import io.agentcore.managed.server.runtime.managed.ManagedLeaseStore.Lease;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInteractions;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.session.SessionModelPolicy;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.shutdown.AgentShuttingDownException;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Actual Harness failure/recovery with explicit fake MQ/Redis and transactional Tool Worker storage. */
@Timeout(30)
class ManagedFailureIntegrationTest {
    @TempDir private Path managedCheckpointRoot;

    @TempDir Path root;

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false"})
    void externalReplyOrCancelResumesAPersistedWaitWithoutHoldingTheHarness(
            boolean cancel, boolean error) throws Exception {
        try (var fixture =
                new Fixture(
                        (call, messages) -> {
                            if (call > 1) {
                                var results =
                                        messages.stream()
                                                .flatMap(
                                                        message ->
                                                                message
                                                                        .getContentBlocks(
                                                                                ToolResultBlock
                                                                                        .class)
                                                                        .stream())
                                                .filter(
                                                        result ->
                                                                "external-call"
                                                                        .equals(result.getId()))
                                                .toList();
                                assertEquals(1, results.size());
                                assertEquals(
                                        error ? ToolResultState.ERROR : ToolResultState.SUCCESS,
                                        results.get(0).getState());
                                assertTrue(
                                        messages.stream()
                                                .flatMap(
                                                        message ->
                                                                message
                                                                        .getContentBlocks(
                                                                                ToolResultBlock
                                                                                        .class)
                                                                        .stream())
                                                .flatMap(result -> result.getOutput().stream())
                                                .filter(TextBlock.class::isInstance)
                                                .map(TextBlock.class::cast)
                                                .anyMatch(
                                                        text ->
                                                                text.getText()
                                                                        .equals(
                                                                                "actual external"
                                                                                    + " result")));
                                return answer();
                            }
                            return call == 1
                                    ? Flux.just(
                                            ChatResponse.builder()
                                                    .content(
                                                            List.of(
                                                                    ToolUseBlock.builder()
                                                                            .id("external-call")
                                                                            .name("external_answer")
                                                                            .input(
                                                                                    Map.of(
                                                                                            "question",
                                                                                            "answer"
                                                                                                + " needed"))
                                                                            .build()))
                                                    .build())
                                    : answer();
                        },
                        3)) {
            fixture.agent
                    .getToolkit()
                    .registerSchema(
                            ToolSchema.builder()
                                    .name("external_answer")
                                    .description("Explicit fixture external reply")
                                    .parameters(Map.of("type", "object", "properties", Map.of()))
                                    .build());
            var errors = new ConcurrentLinkedQueue<Throwable>();
            var resumePublisher =
                    new ManagedResumePublisher(
                            fixture.journal.suspensions(),
                            fixture.transport,
                            fixture.leases,
                            fixture.mapper);
            var publisher =
                    fixture.renewals.scheduleWithFixedDelay(
                            () -> resumePublisher.retry(16), 0, 10, TimeUnit.MILLISECONDS);
            var executor = Executors.newSingleThreadExecutor();
            try (var resumes =
                    fixture.transport.subscribe(
                            Channel.TURN_RESUMES,
                            2,
                            delivery -> fixture.harnessRuntime.consume(delivery, fixture::run),
                            (delivery, failure) -> errors.add(failure))) {
                String turn = fixture.accept("wait");
                var original =
                        executor.submit(
                                () -> {
                                    fixture.consume();
                                    return null;
                                });
                fixture.await(turn, "waiting_input");
                assertEquals(1, fixture.calls.get());
                original.get(5, TimeUnit.SECONDS);
                assertTrue(original.isDone());
                assertEquals(0, fixture.admission.session(fixture.scope).settledSequence());
                assertEquals(null, fixture.admission.turn(fixture.scope, turn).executionToken());
                Lease lease = null;
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (lease == null && System.nanoTime() < deadline) {
                    lease =
                            fixture.leases
                                    .acquire(fixture.scope.key("execution"), fixture.ttl)
                                    .orElse(null);
                    if (lease == null) Thread.sleep(1);
                }
                assertTrue(lease != null, "Waiting Harness did not release its execution token");
                fixture.leases.release(lease);
                assertEquals(
                        1,
                        ProjectionTestSupport.project(fixture.journal)
                                .publicEvents(fixture.scope, 0, 256)
                                .stream()
                                .filter(event -> "turn.requires_action".equals(event.type()))
                                .count());
                var input =
                        fixture.admission.acceptInput(
                                fixture.scope,
                                turn,
                                "reply-user",
                                "answer",
                                cancel ? Type.CANCEL : Type.REPLY,
                                cancel ? null : "external-call",
                                cancel
                                        ? "{}"
                                        : "{\"content\":[{\"type\":\"text\",\"text\":\"actual"
                                                + " external result\"}],\"is_error\":"
                                                + error
                                                + "}");
                fixture.await(turn, cancel ? "cancelled" : "succeeded");
                original.get(5, TimeUnit.SECONDS);
                assertEquals(
                        "applied", fixture.admission.input(fixture.scope, input.id()).status());
                assertEquals(1, fixture.admission.session(fixture.scope).settledSequence());
                assertTrue(fixture.admission.turn(fixture.scope, turn).executionEpoch() > 1);
                assertEquals(cancel ? 1 : 2, fixture.calls.get());
                assertTrue(errors.isEmpty(), errors.toString());
                assertTrue(
                        SessionInteractions.pending(
                                        new ManagedMqSessionLog(
                                                fixture.scope,
                                                null,
                                                0,
                                                null,
                                                fixture.journal,
                                                null))
                                .isEmpty());
            } finally {
                publisher.cancel(false);
                executor.shutdownNow();
            }
        }
    }

    @Test
    void budgetDenialTerminatesWithoutCallingModelAndNextTurnUsesUpdatedBudget() throws Exception {
        try (var fixture = new Fixture(call -> answer())) {
            fixture.budgets.configure(fixture.scope, Map.of("max_model_calls", 1), 0L);
            String priming = fixture.accept("prime");
            fixture.consume();
            fixture.await(priming, "succeeded");
            assertEquals(1, fixture.calls.get());
            String first = fixture.accept("denied"), second = fixture.accept("next");
            fixture.consume();
            fixture.await(first, "failed");
            assertEquals(1, fixture.calls.get());
            assertEquals(
                    1,
                    ProjectionTestSupport.project(fixture.journal)
                            .publicEvents(fixture.scope, 0, 256)
                            .stream()
                            .filter(event -> "budget.exceeded".equals(event.type()))
                            .count());
            fixture.budgets.configure(fixture.scope, Map.of("max_model_calls", 2), 1L);
            fixture.consume();
            fixture.await(second, "succeeded");
            assertEquals(2, fixture.calls.get());
            assertEquals(3, fixture.admission.session(fixture.scope).settledSequence());
        }
    }

    @Test
    void agentFailureSettlesInputsAndAllowsTheNextTurn() throws Exception {
        try (var fixture =
                new Fixture(
                        call ->
                                call == 1
                                        ? Flux.error(
                                                new IllegalArgumentException(
                                                        "private model detail"))
                                        : answer())) {
            String first = fixture.accept("first"), second = fixture.accept("second");
            var input =
                    fixture.admission.acceptInput(
                            fixture.scope,
                            first,
                            "user",
                            "steer",
                            Type.STEER,
                            null,
                            "{\"message\":\"later instruction\"}");
            fixture.consume();
            fixture.await(first, "failed");
            assertEquals("rejected", fixture.admission.input(fixture.scope, input.id()).status());
            fixture.consume();
            fixture.await(second, "succeeded");
            assertEquals(2, fixture.calls.get());
            assertEquals(2, fixture.admission.session(fixture.scope).settledSequence());
            assertTrue(
                    ProjectionTestSupport.project(fixture.journal)
                            .publicEvents(fixture.scope, 0, 256)
                            .stream()
                            .anyMatch(event -> "turn.failed".equals(event.type())));
            assertFalse(
                    ProjectionTestSupport.project(fixture.journal)
                            .publicEvents(fixture.scope, 0, 256)
                            .stream()
                            .anyMatch(event -> event.payload().contains("private model detail")));
        }
    }

    @Test
    void failedDecisionSurvivesTerminalPublicationFailureWithoutCallingModelAgain()
            throws Exception {
        try (var fixture =
                new Fixture(
                        call ->
                                Flux.error(
                                        new IllegalArgumentException("invalid model request")))) {
            var drop = new AtomicBoolean(true);
            doAnswer(
                            invocation -> {
                                ManagedRuntimeBatch batch = invocation.getArgument(0);
                                if (batch.terminalStatus() != null && drop.getAndSet(false))
                                    throw new ManagedStateException(
                                            "fixture_transport_down", "terminal publish failed");
                                return invocation.callRealMethod();
                            })
                    .when(fixture.writer)
                    .commit(any(), any());
            String turn = fixture.accept("first");
            assertThrows(ManagedStateException.class, fixture::consume);
            fixture.awaitFailureDecision(turn);
            fixture.redeliver();
            fixture.consume();
            fixture.await(turn, "failed");
            assertEquals(1, fixture.calls.get());
            assertEquals(2, fixture.admission.turn(fixture.scope, turn).executionEpoch());
        }
    }

    @Test
    void iterationLimitFailureRetainsItsDecisionAcrossRedelivery() throws Exception {
        try (var fixture =
                new Fixture(
                        call ->
                                Flux.just(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("missing-" + call)
                                                                        .name("unregistered_tool")
                                                                        .input(Map.of())
                                                                        .content("{}")
                                                                        .build()))
                                                .build()))) {
            var drop = new AtomicBoolean(true);
            doAnswer(
                            invocation -> {
                                ManagedRuntimeBatch batch = invocation.getArgument(0);
                                if (batch.terminalStatus() != null && drop.getAndSet(false))
                                    throw new ManagedStateException(
                                            "fixture_transport_down", "terminal publish failed");
                                return invocation.callRealMethod();
                            })
                    .when(fixture.writer)
                    .commit(any(), any());
            String turn = fixture.accept("first");
            assertThrows(ManagedTransportException.class, fixture::consume);
            fixture.awaitFailureDecision(turn);
            int before = fixture.calls.get();
            assertTrue(before > 0);
            fixture.redeliver();
            fixture.consume();
            fixture.await(turn, "failed");
            assertEquals(before, fixture.calls.get());
        }
    }

    @Test
    void sessionLogFailureRetriesInsteadOfBecomingAnAgentFailure() throws Exception {
        try (var fixture =
                new Fixture(
                        call ->
                                call == 1
                                        ? Flux.error(
                                                new SessionLogException(
                                                        "temporary log transport failure"))
                                        : answer())) {
            String turn = fixture.accept("first");
            assertThrows(RuntimeException.class, fixture::consume);
            assertFalse(fixture.failureDecision(turn));
            fixture.awaitNativeRunEnd();
            fixture.redeliver();
            fixture.consume();
            fixture.await(turn, "succeeded");
            assertEquals(2, fixture.calls.get());
        }
    }

    @Test
    void failureRecoveryNeverInventsAnUnknownExternalToolOutcome() throws Exception {
        try (var fixture = new Fixture(call -> answer())) {
            String turn = fixture.accept("first");
            var delivery = fixture.receive();
            assertThrows(
                    ManagedStateException.class,
                    () ->
                            fixture.harnessRuntime.consume(
                                    delivery,
                                    execution -> {
                                        String batch = "prepared-failure";
                                        var state =
                                                fixture.event(
                                                        execution,
                                                        1,
                                                        "state/checkpoint",
                                                        Map.of(
                                                                "stateJson",
                                                                AgentState.builder()
                                                                        .build()
                                                                        .toJson()));
                                        var dispatch =
                                                fixture.event(
                                                        execution,
                                                        2,
                                                        "tool/dispatch",
                                                        Map.of(
                                                                "toolCallId",
                                                                "unknown-call",
                                                                "name",
                                                                "external_effect",
                                                                "arguments",
                                                                Map.of()));
                                        var failure =
                                                fixture.event(
                                                        execution,
                                                        3,
                                                        "turn/failed",
                                                        Map.of(
                                                                "status",
                                                                "failed",
                                                                "reason",
                                                                "managed_agent_execution_failed"));
                                        execution.boundary(
                                                batch,
                                                List.of(state, dispatch, failure),
                                                List.of(),
                                                List.of(),
                                                null);
                                        fixture.run(execution);
                                    }));
            fixture.awaitFailureDecision(turn);
            assertEquals(0, fixture.calls.get());
            assertTrue(
                    SessionProjection.read(
                                    new ManagedMqSessionLog(
                                            fixture.scope, null, 0, null, fixture.journal, null))
                            .uncertainToolCalls()
                            .contains("unknown-call"));
            assertFalse(
                    List.of("failed", "succeeded", "cancelled")
                            .contains(fixture.admission.turn(fixture.scope, turn).status()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"shutdown", "rejected", "unexpected"})
    void recoveryEntryFailureCannotReuseThePreviousFailedRun(String kind) throws Exception {
        try (var fixture =
                new Fixture(
                        call ->
                                call == 1
                                        ? Flux.error(new SessionLogException("temporary failure"))
                                        : answer())) {
            String turn = fixture.accept("recover");
            assertThrows(RuntimeException.class, fixture::consume);
            fixture.awaitNativeRunEnd();
            assertFalse(fixture.failureDecision(turn));
            fixture.activationFailure =
                    switch (kind) {
                        case "shutdown" -> new AgentShuttingDownException();
                        case "rejected" -> new RejectedExecutionException("executor stopped");
                        default -> new IllegalStateException("recovery entry failed");
                    };
            fixture.redeliver();
            if (!"unexpected".equals(kind)) fixture.consume();
            else assertThrows(RuntimeException.class, fixture::consume);
            assertFalse(fixture.failureDecision(turn));
            assertFalse(
                    List.of("failed", "succeeded", "cancelled")
                            .contains(fixture.admission.turn(fixture.scope, turn).status()));
            assertEquals(1, fixture.calls.get(), "Failure preceded the next model invocation");
            fixture.redeliver();
            fixture.consume();
            fixture.await(turn, "succeeded");
            assertEquals(2, fixture.calls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"shutdown", "executor", "service-unavailable", "service-rejected"})
    void infrastructureFailureWithinCurrentRunRemainsRetryable(String kind) throws Exception {
        RuntimeException failure =
                switch (kind) {
                    case "shutdown" -> new AgentShuttingDownException();
                    case "executor" -> new RejectedExecutionException("executor stopped");
                    case "service-unavailable" ->
                            new SessionServiceClient.Unavailable("response lost", null);
                    case "service-rejected" ->
                            new SessionServiceClient.Rejected(409, "stale_execution");
                    default -> throw new IllegalArgumentException(kind);
                };
        try (var fixture = new Fixture(call -> call == 1 ? Flux.error(failure) : answer())) {
            String turn = fixture.accept("retry");
            if (kind.startsWith("service-")) assertThrows(failure.getClass(), fixture::consume);
            else fixture.consume();
            fixture.awaitNativeRunEnd();
            assertFalse(fixture.failureDecision(turn));
            fixture.redeliver();
            fixture.consume();
            fixture.await(turn, "succeeded");
        }
    }

    private static Flux<ChatResponse> answer() {
        return Flux.just(
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("recovered").build()))
                        .build());
    }

    private final class Fixture implements AutoCloseable {
        final ObjectMapper mapper = new ObjectMapper();
        final ManagedScope scope = new ManagedScope("t", "n", UUID.randomUUID().toString());
        final Duration ttl = Duration.ofSeconds(2);
        final AtomicLong clock = new AtomicLong();
        final AtomicInteger calls = new AtomicInteger();
        final InMemoryManagedMessageBus transport = new InMemoryManagedMessageBus(this::now, 8);
        final InMemoryManagedLeaseStore leases = new InMemoryManagedLeaseStore(this::now);
        final InMemoryManagedInputInbox inbox = new InMemoryManagedInputInbox(this::now);
        final ManagedPushTestSupport.Probe originals =
                new ManagedPushTestSupport.Probe(transport, Channel.TURNS, 2);
        ManagedPushTestSupport.Invocation invocation;
        final ScheduledExecutorService renewals = Executors.newScheduledThreadPool(4);
        final ScheduledExecutorService events = Executors.newSingleThreadScheduledExecutor();
        final AtomicBoolean pumping = new AtomicBoolean(true);
        final ManagedAdmissionStore admission;
        final ManagedJournalStore journal;
        final ManagedBudgetStore budgets;
        final ManagedRuntimeWriter writer;
        final ManagedHarnessRuntimeTurns harnessRuntime;
        final HarnessAgent agent;
        final Future<?> eventFuture;
        RuntimeException activationFailure;

        Fixture(IntFunction<Flux<ChatResponse>> model) {
            this(model, 1);
        }

        Fixture(IntFunction<Flux<ChatResponse>> model, int maxIters) {
            this((call, messages) -> model.apply(call), maxIters);
        }

        Fixture(BiFunction<Integer, List<Msg>, Flux<ChatResponse>> model, int maxIters) {
            var source =
                    new DriverManagerDataSource(
                            "jdbc:h2:mem:failure-"
                                    + UUID.randomUUID()
                                    + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                            "sa",
                            "");
            ManagedAdmissionStore.initialize(source);
            admission = ProjectionTestSupport.admission(source, mapper);
            budgets = new ManagedBudgetStore(source, mapper, (modelName, usage) -> null);
            journal =
                    ProjectionTestSupport.journal(
                            source,
                            mapper,
                            budgets,
                            new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
            admission.createSession(scope, "{}");
            var codec = new ManagedBatchCodec(mapper, root.resolve(scope.sessionId()));
            journal.transport().configure(transport, codec);
            writer = spy(ProjectionTestSupport.writer(journal, source, transport, codec, mapper));
            harnessRuntime =
                    new ManagedHarnessRuntimeTurns(
                            transport,
                            leases,
                            admission,
                            journal,
                            writer,
                            mapper,
                            renewals,
                            ttl,
                            Duration.ofMillis(200),
                            1,
                            Duration.ofMillis(500));
            var bus = mock(MessageBus.class);
            when(bus.inboxDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
            when(bus.queueDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
            when(bus.inboxHasMessages(anyString())).thenReturn(Mono.just(false));
            when(bus.queuePeek(anyString())).thenReturn(Mono.just(false));
            agent =
                    HarnessAgent.builder()
                            .name("failure-fixture")
                            .model(
                                    new Model() {
                                        public String getModelName() {
                                            return "explicit-failure-fixture";
                                        }

                                        public Flux<ChatResponse> stream(
                                                List<Msg> messages,
                                                List<ToolSchema> tools,
                                                GenerateOptions options) {
                                            return model.apply(calls.incrementAndGet(), messages);
                                        }
                                    })
                            .workspace(root.resolve(scope.sessionId()).resolve("workspace"))
                            .sessionLogStore(new ManagedSessionLogStore(journal))
                            .messageBus(bus)
                            .maxIters(maxIters)
                            .disableCompaction()
                            .disableMemoryHooks()
                            .disableMemoryTools()
                            .disableDefaultWorkspaceSkills()
                            .build();
            var toolWorker = new ManagedHarnessRuntimeToolEvents(codec, journal, r -> {});
            for (var channel : List.of(Channel.TOOL_EVENTS))
                transport.subscribe(
                        channel,
                        2,
                        toolWorker::consume,
                        (d, e) -> {
                            throw new AssertionError(e);
                        });
            eventFuture =
                    events.submit(
                            () -> {
                                while (pumping.get()) {
                                    try {
                                        Thread.sleep(1);
                                    } catch (InterruptedException stopped) {
                                        return;
                                    }
                                }
                            });
        }

        String accept(String key) {
            var turn = admission.acceptTurn(scope, "user", key, "{\"message\":\"run\"}");
            new ManagedTurnPublisher(admission, transport, leases, mapper, ttl)
                    .publish(scope, turn.id());
            return turn.id();
        }

        void consume() throws Exception {
            var delivery = receive();
            try {
                harnessRuntime.consume(delivery, this::run);
                invocation.succeed();
            } catch (ManagedDeliverySuspended waiting) {
                invocation.suspend(waiting.delay());
            } catch (Exception failure) {
                invocation.fail();
                throw failure;
            }
        }

        void run(ManagedTurnExecution execution) {
            var context =
                    RuntimeContext.builder()
                            .userId("user")
                            .sessionId(scope.sessionId())
                            .put(ManagedScope.class, scope)
                            .put(ManagedTurnExecution.class, execution)
                            .put(
                                    SessionModelPolicy.CONTEXT_KEY,
                                    new ManagedModelPolicy(budgets, execution))
                            .put(SessionRecorder.TURN_ID_KEY, execution.turnId())
                            .build();
            HarnessAgent next = agent;
            if (activationFailure != null) {
                next = spy(agent);
                doThrow(activationFailure).when(next).prepareRun(anyList(), any());
                activationFailure = null;
            }
            new ManagedHarnessTurns(mapper, new ManagedInputDelivery(admission, inbox), renewals)
                    .run(execution, next, context);
        }

        long now() {
            return System.nanoTime() / 1_000_000 + clock.get();
        }

        ManagedPushDelivery receive() throws Exception {
            invocation = originals.next();
            return invocation.delivery();
        }

        void redeliver() {
            if (invocation != null) invocation.fail();
        }

        boolean failureDecision(String turn) {
            return journal.recoveryEvents(scope, 0, 1000).stream()
                    .anyMatch(
                            event ->
                                    turn.equals(event.turnId())
                                            && "managed_agent_execution_failed"
                                                    .equals(event.data().get("reason")));
        }

        void awaitFailureDecision(String turn) throws Exception {
            await(() -> failureDecision(turn));
        }

        void awaitNativeRunEnd() throws Exception {
            await(
                    () ->
                            journal.recoveryEvents(scope, 0, 1000).stream()
                                    .anyMatch(event -> "run/end".equals(event.type())));
        }

        void await(String turn, String status) throws Exception {
            await(() -> status.equals(admission.turn(scope, turn).status()));
        }

        void await(BooleanSupplier ready) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
                if (eventFuture.isDone()) eventFuture.get();
                Thread.sleep(5);
            }
            assertTrue(ready.getAsBoolean());
        }

        SessionEvent event(
                ManagedTurnExecution execution, long seq, String type, Map<String, Object> data) {
            return new SessionEvent(
                    1,
                    UUID.randomUUID().toString(),
                    seq,
                    System.currentTimeMillis(),
                    type,
                    "fixture-run",
                    execution.turnId(),
                    true,
                    JsonUtils.getJsonCodec().toJson(data));
        }

        public void close() {
            pumping.set(false);
            originals.close();
            harnessRuntime.close();
            events.shutdownNow();
            renewals.shutdownNow();
            agent.close();
            transport.close();
        }
    }
}
