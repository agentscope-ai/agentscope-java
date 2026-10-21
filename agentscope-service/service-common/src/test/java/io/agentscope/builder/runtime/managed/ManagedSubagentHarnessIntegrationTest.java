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
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor.Outcome;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor.State;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.subagent.SubagentExecution;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Actual parent/child Harness loops and native continuations; explicitly fake transport/model/tools. */
@Timeout(30)
class ManagedSubagentHarnessIntegrationTest {
    @TempDir private Path managedCheckpointRoot;

    @TempDir Path directory;
    private final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

    private void assertResumeEpoch(
            JdbcTemplate jdbc, ManagedAdmissionStore admission, ManagedScope scope, String turn) {
        long resumes =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.turn_resume WHERE scope_key=? AND turn_id=?",
                        Long.class,
                        scope.messageGroup(),
                        turn);
        assertTrue(resumes > 0, "Parent and child must each exercise durable resume");
        assertEquals(1 + resumes, admission.turn(scope, turn).executionEpoch());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneSharedTurnCallbackAndToolSlotSupportParentChildCompletionOrCancellation(boolean cancel)
            throws Exception {
        var mapper = new ObjectMapper();
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:child-harness-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var admission = new ManagedAdmissionStore(source, mapper);
        var journal =
                new ManagedJournalStore(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        var children = new ManagedSubagents(source, mapper, admission, journal);
        var root = new ManagedScope("tenant", "namespace", UUID.randomUUID().toString());
        admission.createSession(root, "{\"owner_id\":\"user\"}");
        String turn =
                admission
                        .acceptTurn(
                                root, "original-actor", "parent", "{\"message\":\"parent-task\"}")
                        .id();
        var ttl = Duration.ofSeconds(5);
        var leases = new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1_000_000);
        var inbox = new InMemoryManagedInputInbox(System::currentTimeMillis);
        var resultDelivery = new InMemoryManagedToolResultDelivery();
        var codec = new ManagedBatchCodec(mapper, directory.resolve("blobs"));
        var renewals = Executors.newScheduledThreadPool(3);
        var pump = Executors.newSingleThreadScheduledExecutor();
        var model = new DelegatingModel();
        var agents = new LinkedHashMap<ManagedScope, HarnessAgent>();
        try (var bus = new InMemoryManagedMessageBus(() -> System.nanoTime() / 1_000_000, 8)) {
            journal.transport().configure(bus, codec);
            var writer = new ManagedRuntimeWriter(journal);
            var requests =
                    new ManagedToolRequests(mapper, Duration.ofMillis(100), resultDelivery, codec);
            var resultRelay = new ManagedToolResultRelay(journal, codec, resultDelivery);
            var events = new ManagedBrainToolEvents(codec, journal, resultRelay::delivered);
            var subscriptions = new ArrayList<ManagedMessageBus.Subscription>();
            for (var channel : List.of(Channel.TOOL_EVENTS))
                subscriptions.add(
                        bus.subscribe(channel, 2, events::consume, (d, e) -> errors.add(e)));
            var resumes = new ManagedResumePublisher(journal.suspensions(), bus, leases, mapper);
            var publisher = new ManagedTurnPublisher(admission, bus, leases, mapper, ttl);
            pump.scheduleWithFixedDelay(
                    () -> {
                        try {
                            resumes.retry(16);
                            publisher.retry(16);
                            resultRelay.repair(16);
                        } catch (Throwable failure) {
                            errors.add(failure);
                        }
                    },
                    0,
                    5,
                    TimeUnit.MILLISECONDS);
            var childExecutor = new ManagedSubagentExecutor(children, admission, journal, mapper);
            var external = new DelayedTool(cancel);
            var executions =
                    new ManagedToolExecutions(
                            leases,
                            inbox,
                            journal,
                            new ManagedToolResultPublisher(journal, bus, codec),
                            renewals,
                            ttl,
                            Duration.ofMillis(300),
                            Duration.ofMillis(10));
            var parent = builder(root, model, journal, requests).build();
            ManagedTaskRepository.LOCAL_TOOLS.forEach(parent.getToolkit()::removeTool);
            agents.put(root, parent);
            try (var tools =
                            new ManagedToolLoop(
                                    journal,
                                    executions,
                                    1,
                                    false,
                                    (scope, task) -> external,
                                    (task, failure) -> errors.add(failure));
                    var childControls =
                            new ManagedToolLoop(
                                    journal,
                                    executions,
                                    1,
                                    true,
                                    (scope, task) -> childExecutor,
                                    (task, failure) -> errors.add(failure));
                    var brain =
                            new ManagedBrainTurns(
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
                                    Duration.ofMillis(500))) {
                tools.start();
                childControls.start();
                ManagedBrainTurns.Runner runner =
                        execution -> {
                            var scope = execution.scope();
                            var agent = agents.get(scope);
                            ManagedSubagentDefinition definition = null;
                            if (!scope.equals(root)) {
                                var call = children.parent(scope).orElseThrow();
                                definition =
                                        mapper.readValue(
                                                call.specification().frozenDefinition(),
                                                ManagedSubagentDefinition.class);
                                if (agent == null) {
                                    var childBuilder = builder(scope, model, journal, requests);
                                    definition.configure(childBuilder);
                                    agent = childBuilder.build();
                                    ManagedTaskRepository.LOCAL_TOOLS.forEach(
                                            agent.getToolkit()::removeTool);
                                    definition.restrict(agent);
                                    agents.put(scope, agent);
                                }
                            }
                            var context =
                                    RuntimeContext.builder()
                                            .userId("user")
                                            .sessionId(scope.sessionId())
                                            .put(ManagedScope.class, scope)
                                            .put(ManagedTurnExecution.class, execution)
                                            .put(
                                                    ManagedToolRequests.Operations.class,
                                                    new ManagedToolRequests.Operations())
                                            .put(
                                                    SubagentExecution.class,
                                                    new ManagedSubagentExecution(
                                                            agent, List.of(), children, requests,
                                                            mapper))
                                            .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                                            .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 600)
                                            .put(ToolCallParam.CALL_CONTEXT_KEY, true)
                                            .put(SessionRecorder.TURN_ID_KEY, execution.turnId())
                                            .build();
                            if (definition != null && execution.head().checkpoint() == null)
                                definition.initialize(agent, context);
                            new ManagedHarnessTurns(
                                            mapper,
                                            new ManagedInputDelivery(admission, inbox),
                                            renewals)
                                    .run(execution, agent, context);
                        };
                subscriptions.add(
                        bus.subscribe(
                                Channel.TURNS,
                                1,
                                delivery -> brain.consume(delivery, runner),
                                (d, e) -> errors.add(e)));
                subscriptions.add(
                        bus.subscribe(
                                Channel.TURN_RESUMES,
                                8,
                                delivery -> brain.consume(delivery, runner),
                                (d, e) -> errors.add(e)));
                publisher.publish(root, turn);
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                boolean cancellationSent = false;
                while (!"succeeded".equals(admission.turn(root, turn).status())
                        && !List.of("failed", "cancelled")
                                .contains(admission.turn(root, turn).status())
                        && System.nanoTime() < deadline
                        && errors.isEmpty()) {
                    if (cancel && !cancellationSent && external.starts.get() > 0) {
                        admission.acceptInput(
                                root,
                                turn,
                                "original-actor",
                                "cancel-parent",
                                ManagedInputInbox.Type.CANCEL,
                                null,
                                "{}");
                        cancellationSent = true;
                    }
                    Thread.sleep(5);
                }
                if (!errors.isEmpty())
                    throw new AssertionError(
                            "Unexpected native child pipeline failure", errors.peek());
                assertEquals(
                        cancel ? "cancelled" : "succeeded", admission.turn(root, turn).status());
                var childCall =
                        journal.toolsForNativeCall(root, turn, "parent-spawn").stream()
                                .filter(task -> ManagedSubagents.OPERATION.equals(task.name()))
                                .findFirst()
                                .orElseThrow();
                var child = children.find(root, childCall.id()).orElseThrow();
                var frozen =
                        mapper.readValue(
                                child.specification().frozenDefinition(),
                                ManagedSubagentDefinition.class);
                assertTrue(
                        frozen.tools().stream()
                                .noneMatch(ManagedTaskRepository.LOCAL_TOOLS::contains));
                assertEquals(
                        cancel ? "cancelled" : "succeeded",
                        admission.turn(child.child(), child.childTurnId()).status());
                assertEquals(
                        "original-actor",
                        admission.turn(child.child(), child.childTurnId()).actor());
                if (cancel) {
                    assertTrue(cancellationSent);
                    assertEquals(1, external.starts.get());
                    assertEquals(1, external.cancellations.get());
                    var actual =
                            journal.toolsForNativeCall(
                                    child.child(), child.childTurnId(), "child-execute");
                    assertEquals(1, actual.size());
                    assertEquals("cancelled", actual.get(0).status());
                    return;
                }
                assertResumeEpoch(new JdbcTemplate(source), admission, root, turn);
                assertResumeEpoch(
                        new JdbcTemplate(source), admission, child.child(), child.childTurnId());
                assertEquals(2, model.parentCalls.get());
                assertEquals(2, model.childCalls.get());
                assertEquals(1, external.starts.get());
                assertEquals(1, admission.turns(child.child(), 0, false, null, 10).size());
                assertTrue(
                        SessionProjection.read(
                                        new ManagedMqSessionLog(
                                                child.child(), null, 0, null, journal, null))
                                .restore()
                                .getPermissionContext()
                                .getDenyRules()
                                .containsKey("blocked_probe"));
                assertTrue(
                        journal.publicEvents(root, 0, 1000).stream()
                                .anyMatch(event -> event.payload().contains("parent-saw-child")));
            } finally {
                pump.shutdownNow();
                pump.awaitTermination(3, TimeUnit.SECONDS);
                for (var agent : agents.values()) agent.close();
            }
        } finally {
            pump.shutdownNow();
            renewals.shutdownNow();
        }
    }

    private HarnessAgent.Builder builder(
            ManagedScope scope,
            Model model,
            ManagedJournalStore journal,
            ManagedToolRequests requests) {
        var harnessBus = mock(MessageBus.class);
        when(harnessBus.inboxDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
        when(harnessBus.queueDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
        when(harnessBus.inboxHasMessages(anyString())).thenReturn(Mono.just(false));
        when(harnessBus.queuePeek(anyString())).thenReturn(Mono.just(false));
        return HarnessAgent.builder()
                .name("parent")
                .model(model)
                .messageBus(harnessBus)
                .workspace(directory.resolve(scope.sessionId()))
                .sessionLogStore(new ManagedSessionLogStore(journal))
                .abstractFilesystem(new ManagedRemoteFilesystem(requests))
                .asyncToolRegistry(new ManagedAsyncToolRegistry())
                .taskRepository(new ManagedTaskRepository())
                .middleware(
                        new MiddlewareBase() {
                            @Override
                            public Flux<AgentEvent> onAgent(
                                    Agent agent,
                                    RuntimeContext context,
                                    AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
                                return next.apply(input)
                                        .doOnError(
                                                failure -> {
                                                    if (ManagedToolWaiting.find(failure) == null)
                                                        errors.add(failure);
                                                });
                            }
                        })
                .permissionContext(
                        PermissionContextState.builder()
                                .mode(PermissionMode.BYPASS)
                                .addDenyRule(
                                        "blocked_probe",
                                        new PermissionRule(
                                                "blocked_probe",
                                                null,
                                                PermissionBehavior.DENY,
                                                "parent-platform-policy"))
                                .build())
                .disableWorkspaceContext()
                .disableDynamicSubagents()
                .disableDefaultWorkspaceSkills()
                .disableMemoryHooks()
                .disableCompaction()
                .maxIters(4);
    }

    private static final class DelayedTool implements ManagedToolExecutor {
        private final Map<String, Long> ends = new ConcurrentHashMap<>();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger cancellations = new AtomicInteger();
        private final Map<String, Outcome> cancelled = new ConcurrentHashMap<>();
        private final boolean longRunning;

        private DelayedTool(boolean longRunning) {
            this.longRunning = longRunning;
        }

        @Override
        public String start(ManagedScope scope, ToolTask task) {
            assertEquals("sandbox.exec", task.name());
            boolean framework = task.arguments().contains("framework_");
            ends.computeIfAbsent(
                    task.id(),
                    id -> {
                        if (!framework) starts.incrementAndGet();
                        return System.nanoTime()
                                + (framework
                                        ? 0
                                        : (longRunning
                                                        ? Duration.ofSeconds(30)
                                                        : Duration.ofMillis(500))
                                                .toNanos());
                    });
            return task.id();
        }

        @Override
        public Outcome query(ManagedScope scope, ToolTask task) {
            if (cancelled.containsKey(task.id())) return cancelled.get(task.id());
            return System.nanoTime() < ends.getOrDefault(task.id(), Long.MAX_VALUE)
                    ? new Outcome(State.RUNNING, null)
                    : new Outcome(
                            State.SUCCEEDED,
                            task.arguments().contains("framework_")
                                    ? "{\"output\":\"\",\"exitCode\":0,\"truncated\":false}"
                                    : "{\"output\":\"child-hands-result\",\"exitCode\":0,\"truncated\":false}");
        }

        @Override
        public Outcome cancel(ManagedScope scope, ToolTask task) {
            return cancelled.computeIfAbsent(
                    task.id(),
                    id -> {
                        cancellations.incrementAndGet();
                        return new Outcome(
                                State.CANCELLED,
                                "{\"output\":\"child-cancelled\",\"exitCode\":-1,\"truncated\":false}");
                    });
        }
    }

    private static final class DelegatingModel implements Model {
        private final AtomicInteger parentCalls = new AtomicInteger();
        private final AtomicInteger childCalls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "deterministic-child-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            boolean child =
                    messages.stream()
                            .anyMatch(message -> message.getTextContent().equals("child-task"));
            var results =
                    messages.stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .toList();
            if (child) childCalls.incrementAndGet();
            else parentCalls.incrementAndGet();
            if (results.isEmpty()) {
                String name = child ? "execute" : "agent_spawn";
                Map<String, Object> input =
                        child
                                ? Map.of("command", "child-command", "timeout", 10)
                                : Map.of(
                                        "agent_id",
                                        "general-purpose",
                                        "task",
                                        "child-task",
                                        "timeout_seconds",
                                        0);
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id(
                                                                child
                                                                        ? "child-execute"
                                                                        : "parent-spawn")
                                                        .name(name)
                                                        .input(input)
                                                        .content(
                                                                JsonUtils.getJsonCodec()
                                                                        .toJson(input))
                                                        .build()))
                                .build());
            }
            assertTrue(
                    results.stream()
                            .flatMap(result -> result.getOutput().stream())
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .anyMatch(
                                    text ->
                                            text.getText()
                                                    .contains(
                                                            child
                                                                    ? "child-hands-result"
                                                                    : "child-saw-hands")),
                    () -> JsonUtils.getJsonCodec().toJson(results));
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text(
                                                            child
                                                                    ? "child-saw-hands"
                                                                    : "parent-saw-child")
                                                    .build()))
                            .build());
        }
    }
}
