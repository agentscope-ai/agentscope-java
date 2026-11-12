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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.agentservice.config.AgentServiceJsonConfiguration;
import io.agentcore.managed.server.agentservice.sessionservice.api.SessionCoordinationApi;
import io.agentcore.managed.server.agentservice.sessionservice.api.SessionServiceApi;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceCoordination;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceStore;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceWorkspace;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor.Outcome;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor.State;
import io.agentcore.managed.server.runtime.managed.projection.PostgresSessionProjectionStore;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import io.agentscope.core.agent.RuntimeContext;
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
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionModelPolicy;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Publisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/** Real native Harness + HTTP Session Service + Hands inbox/outbox, deterministic model/sandbox. */
@Timeout(40)
class AgentScopeRemoteFlowTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class HttpConfiguration {}

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void toolResultContinuesThroughHttpAndAFreshBrain(boolean suspend) throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        PostgresSessionProjectionStore.initialize(source);
        var views =
                new ManagedQueryViews(
                        new PostgresSessionProjectionStore(source, mapper),
                        mapper,
                        "01234567890123456789012345678901".getBytes(),
                        "v1",
                        1000,
                        new ManagedPublicResources(source, mapper));
        var admission = new ManagedAdmissionStore(source, mapper);
        admission.queryViews(views);
        var scope = new ManagedScope("tenant", "test", "session");
        admission.createSession(scope, "{}");
        if (suspend) admission.queue().enable(scope);
        String turn = admission.acceptTurn(scope, "actor", "input", "{\"message\":\"run\"}").id();
        var injected = new AtomicReference<ManagedAdmissionStore.Input>();
        var serviceBudgets = new ManagedBudgetStore(source, mapper, (name, usage) -> null);
        var objects = new FileRuntimeObjectStore("test", root.resolve("service-objects"));
        var serviceJournal =
                new ManagedJournalStore(
                        source,
                        mapper,
                        serviceBudgets,
                        new ManagedCheckpointObjects(mapper, objects),
                        new SessionEventNotifier(source, 1000));
        serviceJournal.queryViews(views);
        var store = new SessionServiceStore(source, serviceJournal, mapper, objects);
        var workspace = new SessionServiceWorkspace(source, serviceJournal, mapper);
        var context = new AnnotationConfigApplicationContext();
        context.register(HttpConfiguration.class, AgentServiceJsonConfiguration.class);
        context.registerBean(ObjectMapper.class, () -> mapper);
        context.registerBean(
                SessionServiceApi.class,
                () ->
                        new SessionServiceApi(
                                store, null, workspace, null, serviceBudgets, admission));
        var coordination =
                new SessionServiceCoordination(
                        source,
                        mapper,
                        admission,
                        new ManagedSubagents(source, mapper, admission, serviceJournal),
                        null,
                        600,
                        3600);
        context.registerBean(
                SessionCoordinationApi.class, () -> new SessionCoordinationApi(coordination));
        var lostResponse = new AtomicInteger();
        var receiptReads = new AtomicInteger();
        var lostPublication = new AtomicInteger();
        context.registerBean(
                WebFilter.class,
                () ->
                        (exchange, chain) -> {
                            String path = exchange.getRequest().getPath().value();
                            if (path.endsWith("/prepared/receipt")) receiptReads.incrementAndGet();
                            boolean lose =
                                    path.endsWith("/prepared/commits")
                                            && lostResponse.getAndIncrement() == 0;
                            if (path.endsWith("/prepared/tool-publication"))
                                lose = lostPublication.getAndIncrement() == 0;
                            if (!lose) return chain.filter(exchange);
                            var response =
                                    new ServerHttpResponseDecorator(exchange.getResponse()) {
                                        @Override
                                        public Mono<Void> writeWith(
                                                Publisher<? extends DataBuffer> body) {
                                            setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                                            return super.writeWith(body);
                                        }
                                    };
                            return chain.filter(exchange.mutate().response(response).build());
                        });
        context.refresh();
        var http =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                new ReactorHttpHandlerAdapter(
                                        WebHttpHandlerBuilder.applicationContext(context).build()))
                        .bindNow();
        var client =
                new SessionServiceClient(
                        URI.create(
                                "http://127.0.0.1:" + http.port() + "/internal/session-service/v0"),
                        "test-token",
                        mapper,
                        Duration.ofSeconds(10));
        var bus = new InMemoryManagedMessageBus(() -> System.nanoTime() / 1_000_000, 8);
        var process =
                new ManagedProcessOwnership(
                        client,
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(10),
                        Duration.ofHours(1));
        var remoteAdmission = new ManagedAdmissionStore(client, mapper, process);
        var leases = new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1_000_000);
        var inbox = new InMemoryManagedInputInbox(System::currentTimeMillis);
        var results = new InMemoryManagedToolResultDelivery();
        var codec = new ManagedBatchCodec(mapper, objects);
        var errors = new ConcurrentLinkedQueue<Throwable>();
        var controls = Executors.newScheduledThreadPool(4);
        var starts = new AtomicInteger();
        var attempts = new AtomicInteger();
        var model = new ToolModel();
        var ttl = Duration.ofSeconds(10);
        var worker = new ManagedToolWorkerStore(source, mapper);
        var publisher = new ManagedToolResultPublisher(worker, bus, codec);
        var executions =
                new ManagedAsyncToolExecutions(
                        leases,
                        worker,
                        publisher,
                        controls,
                        ttl,
                        Duration.ofSeconds(1),
                        Duration.ofMillis(10));
        try (var commandPublisher = new ManagedToolCommandPublisher(source, bus, codec, mapper);
                var relay = new ManagedToolResultRelay(serviceJournal, codec, results);
                var resumes =
                        new ManagedResumePublisher(
                                serviceJournal.suspensions(), bus, leases, mapper);
                var hands =
                        new ManagedToolWorkerLoop(
                                worker,
                                executions,
                                2,
                                ttl,
                                Duration.ofMillis(10),
                                (s, id) ->
                                        new ManagedToolExecutor() {
                                            long ends;

                                            public boolean idempotentStart() {
                                                return true;
                                            }

                                            public String start(
                                                    ManagedScope s,
                                                    ManagedJournalStore.ToolTask task) {
                                                if ("call_0".equals(task.toolCallId()))
                                                    starts.incrementAndGet();
                                                ends =
                                                        System.nanoTime()
                                                                + Duration.ofMillis(
                                                                                suspend
                                                                                                && "call_0"
                                                                                                        .equals(
                                                                                                                task
                                                                                                                        .toolCallId())
                                                                                        ? 1200
                                                                                        : 0)
                                                                        .toNanos();
                                                return task.executionId();
                                            }

                                            public Outcome query(
                                                    ManagedScope s,
                                                    ManagedJournalStore.ToolTask task) {
                                                if (!"call_0".equals(task.toolCallId()))
                                                    return new Outcome(
                                                            State.SUCCEEDED,
                                                            "{\"output\":\"__NOT_FOUND__\",\"exitCode\":0,\"truncated\":false}");
                                                return System.nanoTime() < ends
                                                        ? new Outcome(State.RUNNING, null)
                                                        : new Outcome(
                                                                State.SUCCEEDED,
                                                                "{\"output\":\"hands-result\",\"exitCode\":0,\"truncated\":false}");
                                            }

                                            public Outcome cancel(
                                                    ManagedScope s,
                                                    ManagedJournalStore.ToolTask task) {
                                                return new Outcome(State.CANCELLED, "{}");
                                            }
                                        },
                                (task, error) -> errors.add(error))) {
            serviceJournal.transport().onPublicationReady(commandPublisher::wakeup);
            serviceJournal.transport().configure(bus, codec);
            commandPublisher.start();
            relay.start();
            resumes.start();
            publisher.start();
            hands.start();
            var commands =
                    new ManagedToolWorkerCommands(
                            worker, codec, publisher, hands, serviceJournal.transport()::confirm);
            bus.subscribe(Channel.TOOL_EXECUTE, 2, commands::consume, (d, e) -> errors.add(e));
            var events =
                    new ManagedHarnessRuntimeToolEvents(
                            codec,
                            serviceJournal,
                            result -> {
                                relay.delivered(result);
                                resumes.wakeup(result.scope().messageGroup());
                            });
            bus.subscribe(Channel.TOOL_EVENTS, 2, events::consume, (d, e) -> errors.add(e));
            var brain =
                    new ManagedJournalStore(
                            source,
                            mapper,
                            null,
                            new ManagedCheckpointObjects(
                                    mapper, new RemoteRuntimeObjectStore(client, "test", mapper)),
                            new SessionEventNotifier(source, 1000));
            brain.remoteReads(client);
            var brainBudgets = new ManagedBudgetStore(source, mapper, (name, usage) -> null);
            brainBudgets.remote(client);
            var writer = new ManagedRuntimeWriter(brain, client, mapper);
            var requests =
                    new ManagedToolRequests(
                            mapper, Duration.ofMillis(suspend ? 100 : 5000), results, codec);
            var harnessBus = mock(MessageBus.class);
            when(harnessBus.inboxDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
            when(harnessBus.queueDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
            when(harnessBus.inboxHasMessages(anyString())).thenReturn(Mono.just(false));
            when(harnessBus.queuePeek(anyString())).thenReturn(Mono.just(false));
            try (var runtime =
                    new ManagedHarnessRuntimeTurns(
                            bus,
                            leases,
                            remoteAdmission,
                            brain,
                            writer,
                            mapper,
                            controls,
                            ttl,
                            Duration.ofSeconds(1),
                            1,
                            Duration.ofMillis(200))) {
                ManagedHarnessRuntimeTurns.Runner runner =
                        execution -> {
                            attempts.incrementAndGet();
                            if (injected.get() == null)
                                injected.set(
                                        admission.acceptInput(
                                                scope,
                                                turn,
                                                "actor",
                                                "inject",
                                                ManagedInputInbox.Type.INJECT,
                                                null,
                                                "{\"message\":\"extra instruction\"}"));
                            try (var agent =
                                    HarnessAgent.builder()
                                            .name("remote-agentscope")
                                            .model(model)
                                            .workspace(root.resolve("brain-workspace"))
                                            .sessionLogStore(new ManagedSessionLogStore(brain))
                                            .messageBus(harnessBus)
                                            .asyncToolRegistry(new ManagedAsyncToolRegistry())
                                            .taskRepository(new ManagedTaskRepository())
                                            .abstractFilesystem(
                                                    new ManagedRemoteFilesystem(requests))
                                            .middleware(new ManagedRuntimeInputMiddleware(mapper))
                                            .permissionContext(
                                                    PermissionContextState.builder()
                                                            .mode(PermissionMode.BYPASS)
                                                            .build())
                                            .disableMemoryHooks()
                                            .disableCompaction()
                                            .disableDefaultWorkspaceSkills()
                                            .build()) {
                                var nativeContext =
                                        RuntimeContext.builder()
                                                .userId("actor")
                                                .sessionId(scope.sessionId())
                                                .put(ManagedScope.class, scope)
                                                .put(ManagedTurnExecution.class, execution)
                                                .put(
                                                        ManagedToolRequests.Operations.class,
                                                        new ManagedToolRequests.Operations())
                                                .put(ToolCallParam.CALL_CONTEXT_KEY, true)
                                                .put(SessionRecorder.TURN_ID_KEY, turn)
                                                .put(
                                                        SessionModelPolicy.CONTEXT_KEY,
                                                        new ManagedModelPolicy(
                                                                brainBudgets, execution))
                                                .build();
                                new ManagedHarnessTurns(
                                                mapper,
                                                new ManagedInputDelivery(remoteAdmission, inbox),
                                                controls)
                                        .run(execution, agent, nativeContext);
                            }
                        };
                bus.subscribe(
                        Channel.TURNS, 2, d -> runtime.consume(d, runner), (d, e) -> errors.add(e));
                bus.subscribe(
                        Channel.TURN_RESUMES,
                        2,
                        d -> runtime.consume(d, runner),
                        (d, e) -> errors.add(e));
                bus.subscribe(
                        Channel.SESSION_READY,
                        2,
                        d -> runtime.consume(d, runner),
                        (d, e) -> errors.add(e));
                new ManagedTurnPublisher(admission, bus, leases, mapper, ttl).publish(scope, turn);
                long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
                while (!List.of("succeeded", "failed")
                                .contains(admission.turn(scope, turn).status())
                        && errors.isEmpty()
                        && System.nanoTime() < deadline) Thread.sleep(10);
                if (!errors.isEmpty())
                    throw new AssertionError("Managed pipeline failed", errors.peek());
                assertEquals("succeeded", admission.turn(scope, turn).status());
                assertTrue(
                        receiptReads.get() > 0,
                        "Lost commit response must query the original receipt");
                assertEquals(2, model.calls.get());
                assertEquals("applied", admission.input(scope, injected.get().id()).status());
                assertEquals(
                        2,
                        new JdbcTemplate(source)
                                .queryForObject(
                                        "SELECT COUNT(*) FROM ma.model_call WHERE scope_key=?",
                                        Integer.class,
                                        scope.messageGroup()));
                assertEquals(1, starts.get());
                assertEquals(suspend, attempts.get() > 1);
                assertEquals(1, admission.session(scope).settledSequence());
                assertTrue(serviceJournal.pendingToolIds(scope, turn).isEmpty());
                assertEquals(
                        "TERMINAL",
                        brain.objects()
                                .read(brain.head(scope).checkpoint())
                                .continuation()
                                .get("resumePhase"));
                var facts = brain.recoveryEvents(scope, 0, 1000);
                assertTrue(facts.stream().anyMatch(e -> "tool/dispatch".equals(e.type())));
                assertTrue(facts.stream().anyMatch(e -> "tool/result".equals(e.type())));
                while (serviceJournal.projections().project(scope, 128) > 0) {}
                assertTrue(
                        serviceJournal.publicEvents(scope, 0, 1000).stream()
                                .anyMatch(e -> e.payload().contains("finished")));
                assertEquals(
                        "agentscope-java",
                        new JdbcTemplate(source)
                                .queryForObject(
                                        "SELECT harness_type FROM ma.harness_binding WHERE"
                                                + " scope_key=?",
                                        String.class,
                                        scope.messageGroup()));
            }
            if (suspend) {
                assertPrivateWorkspaceRecovery(source, admission, client);
                assertRemoteToolPublication(source, admission, client);
            }
        } finally {
            process.close();
            publisher.close();
            controls.shutdownNow();
            bus.close();
            http.disposeNow();
            context.close();
        }
    }

    private void assertRemoteToolPublication(
            DriverManagerDataSource source,
            ManagedAdmissionStore admission,
            SessionServiceClient client)
            throws Exception {
        var scope = new ManagedScope("tenant", "test", "publication");
        admission.createSession(scope, "{}");
        String turn = admission.acceptTurn(scope, "actor", "publication", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "owner");
        var denied = mock(DataSource.class);
        when(denied.getConnection())
                .thenThrow(new AssertionError("Brain must not access proposal SQL"));
        var remote = new ManagedToolTransport(denied, mapper);
        remote.remote(client);
        var task =
                new ManagedJournalStore.ToolTask(
                        "publication-task",
                        turn,
                        "call",
                        "publication-task",
                        "execute",
                        "{}",
                        1,
                        "ready",
                        null,
                        null,
                        null);
        var command =
                new ManagedToolCommand(
                        scope,
                        "execute_publication-task",
                        "execute",
                        task,
                        null,
                        new ManagedToolCommand.Submission(1, "owner", "batch"));
        assertThrows(SessionServiceClient.Unavailable.class, () -> remote.send(command));
        assertEquals(command, remote.send(command).command());
        assertEquals(List.of(command), remote.pending(scope, turn));
        assertEquals(
                1,
                new JdbcTemplate(source)
                        .queryForObject(
                                "SELECT COUNT(*) FROM ma.tool_command_outbox WHERE command_id=? AND"
                                        + " authorized=FALSE",
                                Integer.class,
                                command.commandId()));
        assertEquals(
                ManagedToolCommitGate.Decision.PENDING,
                new ManagedToolCommitGate(source, mapper).check(command));
        admission.activate(scope, turn, 1, "replacement");
        assertEquals(
                "stale_execution",
                assertThrows(ManagedStateException.class, () -> remote.send(command)).code());
        var suspensions = new ManagedSuspensions(new JdbcTemplate(denied), mapper);
        suspensions.remote(client);
        var absent = new ManagedSuspensions.Resume(scope, "absent", turn, "suspension", 1, 1);
        assertEquals(
                "resume_not_found",
                assertThrows(ManagedStateException.class, () -> suspensions.settled(absent))
                        .code());
        assertEquals(
                "resume_not_found",
                assertThrows(
                                ManagedStateException.class,
                                () -> suspensions.defer(absent, Duration.ofSeconds(1)))
                        .code());
    }

    private void assertPrivateWorkspaceRecovery(
            DriverManagerDataSource source,
            ManagedAdmissionStore admission,
            SessionServiceClient client)
            throws Exception {
        var scope = new ManagedScope("tenant", "test", "private-session");
        admission.createSession(scope, "{}");
        String turn = admission.acceptTurn(scope, "actor", "private-input", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "token");
        var brain =
                new ManagedJournalStore(
                        source,
                        mapper,
                        null,
                        new ManagedCheckpointObjects(
                                mapper, new RemoteRuntimeObjectStore(client, "test", mapper)),
                        new SessionEventNotifier(source, 1000));
        brain.remoteReads(client);
        var writer = new ManagedRuntimeWriter(brain, client, mapper);
        var guard = mock(ManagedExecutionGuard.class);
        when(guard.token()).thenReturn("token");
        when(guard.remaining()).thenReturn(Duration.ofSeconds(30));
        var event =
                new SessionEvent(
                        1,
                        "frame",
                        1,
                        123L,
                        ManagedFinalization.START,
                        "run",
                        turn,
                        true,
                        mapper.writeValueAsString(
                                Map.of(
                                        "id",
                                        "frame",
                                        "startedAt",
                                        123L,
                                        "zone",
                                        "UTC",
                                        "result",
                                        Map.of(),
                                        "stateJson",
                                        AgentState.builder().build().toJson())));
        writer.commit(
                AgentScopeCheckpoints.prepare(
                        brain.objects(),
                        new ManagedRuntimeBatch(
                                scope,
                                turn,
                                1,
                                "token",
                                "writer",
                                "frame",
                                0,
                                List.of(event),
                                List.of(),
                                List.of(),
                                null)),
                guard);
        var execution = new ManagedTurnExecution(scope, turn, 1, guard, admission, brain, writer);
        var remote = new RemoteWorkspaceStore(client, mapper);
        var namespace = List.of("private", scope.messageGroup());
        var recorder =
                new ManagedFinalizationWorkspace(
                        execution, "frame", remote, new AtomicInteger()::getAndIncrement, mapper);
        recorder.put(
                namespace,
                "file",
                Map.of("content", "data", "created_at", "first", "modified_at", "first"));
        var saved = recorder.get(namespace, "file");
        assertEquals("first", saved.value().get("modified_at"));
        remote.put(namespace, "file", Map.of("content", "outside-change"));
        var replay =
                new ManagedFinalizationWorkspace(
                        execution, "frame", remote, new AtomicInteger()::getAndIncrement, mapper);
        replay.put(
                namespace,
                "file",
                Map.of("content", "data", "created_at", "later", "modified_at", "later"));
        assertEquals(saved, replay.get(namespace, "file"), "Replay must use the frozen read");
        assertEquals(
                "outside-change",
                remote.get(namespace, "file").value().get("content"),
                "Replay must not write twice");
        assertFalse(
                recorder.putIfVersion(namespace, "file", Map.of("content", "wrong-version"), 0));
        var conflicting =
                new ManagedFinalizationWorkspace(execution, "frame", remote, () -> 0, mapper);
        assertEquals(
                "finalization_io_conflict",
                assertThrows(
                                SessionServiceClient.Rejected.class,
                                () ->
                                        conflicting.put(
                                                namespace, "file", Map.of("content", "different")))
                        .code());
        admission.activate(scope, turn, 1, "replacement");
        assertEquals(
                "stale_execution",
                assertThrows(
                                SessionServiceClient.Rejected.class,
                                () ->
                                        remote.atomic(
                                                List.of("get", namespace, "file"),
                                                execution,
                                                "frame",
                                                1,
                                                ManagedScope.fingerprint(
                                                        mapper.writeValueAsString(
                                                                List.of(
                                                                        "get", namespace,
                                                                        "file")))))
                        .code());
    }

    static class ToolModel implements Model {
        final AtomicInteger calls = new AtomicInteger();

        public String getModelName() {
            return "deterministic";
        }

        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (calls.getAndIncrement() == 0)
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("call")
                                                        .name("execute")
                                                        .content(
                                                                "{\"command\":\"work\",\"timeout\":10}")
                                                        .input(
                                                                Map.of(
                                                                        "command", "work",
                                                                        "timeout", 10))
                                                        .build()))
                                .build());
            assertTrue(
                    messages.stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .flatMap(r -> r.getOutput().stream())
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .anyMatch(t -> t.getText().contains("hands-result")));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("finished").build()))
                            .build());
        }
    }
}
