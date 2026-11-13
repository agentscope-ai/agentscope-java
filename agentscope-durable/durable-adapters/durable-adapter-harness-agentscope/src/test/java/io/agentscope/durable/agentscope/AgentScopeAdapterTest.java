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

package io.agentscope.durable.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.journal.JournalEvent;
import io.agentscope.core.journal.RecordedModels;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.DelegationExecutor;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableMessages;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTurn;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.DelegationTarget;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.Failure;
import io.agentscope.durable.DurableTypes.Lease;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.ModelObservation;
import io.agentscope.durable.DurableTypes.ModelStatus;
import io.agentscope.durable.DurableTypes.RecoveryPolicy;
import io.agentscope.durable.DurableTypes.RunStatus;
import io.agentscope.durable.DurableTypes.RunView;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.Signal;
import io.agentscope.durable.DurableTypes.StepPhase;
import io.agentscope.durable.DurableTypes.ThreadBinding;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ThreadSpec;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.EventProjection;
import io.agentscope.durable.EventStreamOptions;
import io.agentscope.durable.EventStreamOptions.EventDeltaType;
import io.agentscope.durable.Executor;
import io.agentscope.durable.HarnessContext;
import io.agentscope.durable.LiveEvents;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.SessionEventClient;
import io.agentscope.durable.SessionStreamEvent;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.Wakeups;
import io.agentscope.durable.codec.agentscope.AgentScopeCheckpointCodec;
import io.agentscope.durable.codec.agentscope.AgentScopeCheckpoints;
import io.agentscope.durable.codec.agentscope.DurableEventSchemas;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class AgentScopeAdapterTest {
    @TempDir Path directory;
    private final SessionKey key = new SessionKey("user", "session");

    static final class ScriptedModel implements Model {
        final List<List<Msg>> inputs = new CopyOnWriteArrayList<>();
        final boolean tool;

        ScriptedModel(boolean tool) {
            this.tool = tool;
        }

        public String getModelName() {
            return "fixture";
        }

        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            boolean applied =
                    messages.stream()
                            .anyMatch(
                                    m ->
                                            m.getContentBlocks(ToolResultBlock.class).stream()
                                                    .anyMatch(
                                                            r ->
                                                                    "call-1".equals(r.getId())
                                                                            && !r.isSuspended()));
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    tool && !applied
                                            ? List.of(
                                                    ToolUseBlock.builder()
                                                            .id("call-1")
                                                            .name("job")
                                                            .input(Map.of("value", 7))
                                                            .build())
                                            : List.of(TextBlock.builder().text("done").build()))
                            .build());
        }
    }

    AgentScopeAdapter adapter(Model model, PermissionMode mode, Map<String, String> bindings) {
        return AgentScopeAdapter.forReAct(
                binding -> {
                    var toolkit = new Toolkit();
                    toolkit.registerSchema(
                            ToolSchema.builder()
                                    .name("job")
                                    .description("external fixture")
                                    .parameters(
                                            Map.of(
                                                    "type",
                                                    "object",
                                                    "properties",
                                                    Map.of("value", Map.of("type", "integer"))))
                                    .build());
                    return ReActAgent.builder()
                            .name("durable-fixture")
                            .model(model)
                            .toolkit(toolkit)
                            .permissionContext(
                                    mode == PermissionMode.BYPASS
                                            ? PermissionContextState.builder().mode(mode).build()
                                            : PermissionContextState.builder()
                                                    .mode(mode)
                                                    .addAskRule(
                                                            "job",
                                                            new PermissionRule(
                                                                    "job",
                                                                    null,
                                                                    PermissionBehavior.ASK,
                                                                    "test"))
                                                    .build());
                },
                bindings);
    }

    DurableRuntime runtime(SessionBackend backend, AgentScopeAdapter adapter, Executor executor) {
        return runtime(backend, adapter, executor, LiveEvents.local());
    }

    DurableRuntime runtime(
            SessionBackend backend,
            AgentScopeAdapter adapter,
            Executor executor,
            LiveEvents liveEvents) {
        var builder =
                DurableRuntime.builder()
                        .backend(backend)
                        .harness("agent", "v1", adapter)
                        .checkpoints("agent", AgentScopeCheckpoints::restore)
                        .recoveryPolicy(new RecoveryPolicy(3, 1, 4))
                        .liveEvents(liveEvents)
                        .pollInterval(Duration.ofMillis(1));
        if (executor != null) builder.executor("jobs", executor);
        return builder.build();
    }

    @Test
    void adapterOwnsTheInvocationJournalConfiguration() {
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding ->
                                ReActAgent.builder()
                                        .name("journal-owner")
                                        .model(new ScriptedModel(false))
                                        .journalEnabled(false),
                        Map.of());
        try (var backend = LocalBackend.open(directory.resolve("journal-owner"));
                var runtime = runtime(backend, adapter, null)) {
            var turn = runtime.createSession(key, "agent").submit("request", "hello");
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertEquals(TurnStatus.SUCCEEDED, turn.status().status());
            assertTrue(!backend.checkpoints(new ThreadKey(key, "main"), 0, 1000).isEmpty());
        }
    }

    @Test
    void routedLocalToolUsesDurableChildRunAndResumesParentAfterHostRestart() throws Exception {
        var parentModel = new ScriptedModel(true);
        var childModel = new ScriptedModel(false);
        var localCalls = new AtomicInteger();
        var parent =
                AgentScopeAdapter.forReAct(
                        binding -> {
                            var toolkit = new Toolkit();
                            toolkit.registerAgentTool(
                                    new ToolBase(
                                            ToolBase.builder()
                                                    .name("job")
                                                    .description("Child Agent")
                                                    .inputSchema(Map.of("type", "object"))) {
                                        public Mono<ToolResultBlock> callAsync(
                                                ToolCallParam param) {
                                            localCalls.incrementAndGet();
                                            return Mono.just(
                                                    ToolResultBlock.text(
                                                            "must execute through child Turn"));
                                        }
                                    });
                            return ReActAgent.builder()
                                    .name("parent")
                                    .model(parentModel)
                                    .toolkit(toolkit)
                                    .permissionContext(
                                            PermissionContextState.builder()
                                                    .mode(PermissionMode.BYPASS)
                                                    .build());
                        },
                        Map.of("job", "children"));
        var child = adapter(childModel, PermissionMode.BYPASS, Map.of());
        try (var backend = LocalBackend.open(directory.resolve("child-turn"))) {
            var children =
                    new DelegationExecutor(
                            backend,
                            request ->
                                    new DelegationTarget(
                                            new ThreadSpec(
                                                    DelegationExecutor.childThread(request),
                                                    new ThreadBinding(
                                                            "child",
                                                            "v1",
                                                            AgentScopeCheckpointCodec
                                                                    .STATE_VERSION)),
                                            DurableJson.value("child prompt")));
            String turn;
            try (var runtime =
                    DurableRuntime.builder()
                            .backend(backend)
                            .harness("parent", "v1", parent)
                            .harness("child", "v1", child)
                            .executor("children", children)
                            .build()) {
                runtime.createSession(key, "parent").mainThread().view().definition();
                turn = runtime.getSession(key).submit("parent-request", "delegate").turnId();
                runtime.tick(new ThreadKey(key, "main"));
                assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
                assertEquals(
                        TurnStatus.WAITING,
                        backend.turn(new ThreadKey(key, "main"), turn).status());
            }
            try (var replacement =
                    DurableRuntime.builder()
                            .backend(backend)
                            .harness("parent", "v1", parent)
                            .harness("child", "v1", child)
                            .executor("children", children)
                            .build()) {
                for (int attempt = 0;
                        attempt < 10
                                && !backend.turn(new ThreadKey(key, "main"), turn)
                                        .status()
                                        .terminal();
                        attempt++) {
                    for (var session : backend.sessions(null, 100))
                        for (var thread : backend.threads(session.key(), null, 100).items())
                            replacement.tick(thread.definition().key());
                    Thread.sleep(20);
                }
                assertEquals(
                        TurnStatus.SUCCEEDED,
                        backend.turn(new ThreadKey(key, "main"), turn).status(),
                        () -> String.valueOf(replacement.lastFailure(new ThreadKey(key, "main"))));
                assertEquals(0, localCalls.get());
                assertEquals(1, childModel.inputs.size());
                assertEquals(2, parentModel.inputs.size());
                assertEquals(1, backend.sessions(null, 100).size());
                assertEquals(2, backend.threads(key, null, 100).items().size());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void physicalModelRetriesHaveSeparateUsageAndDoNotInventMissingCounts(boolean finalUsage) {
        var count = new AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "fixture";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        var config =
                                GenerateOptions.builder()
                                        .executionConfig(
                                                ExecutionConfig.builder()
                                                        .maxAttempts(2)
                                                        .initialBackoff(Duration.ofMillis(1))
                                                        .maxBackoff(Duration.ofMillis(1))
                                                        .build())
                                        .build();
                        return ModelUtils.applyTimeoutAndRetry(
                                Flux.defer(
                                        () -> {
                                            if (count.incrementAndGet() == 1)
                                                return Flux.concat(
                                                        Flux.just(
                                                                ChatResponse.builder()
                                                                        .content(List.of())
                                                                        .usage(
                                                                                new ChatUsage(
                                                                                        4, 1, 0))
                                                                        .build()),
                                                        Flux.error(
                                                                new IllegalStateException(
                                                                        "retry once")));
                                            var usage =
                                                    ChatResponse.builder()
                                                            .content(List.of())
                                                            .usage(
                                                                    finalUsage
                                                                            ? new ChatUsage(
                                                                                    10, 5, 0)
                                                                            : null)
                                                            .build();
                                            return Flux.just(
                                                    usage,
                                                    ChatResponse.builder()
                                                            .content(
                                                                    List.of(
                                                                            TextBlock.builder()
                                                                                    .text("answer")
                                                                                    .build()))
                                                            .build());
                                        }),
                                config,
                                config,
                                "fixture",
                                "test");
                    }
                };
        try (var backend = LocalBackend.open(directory.resolve("usage"));
                var runtime =
                        runtime(backend, adapter(model, PermissionMode.BYPASS, Map.of()), null)) {
            var turn = runtime.createSession(key, "agent").submit("request", "hello");
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertEquals(TurnStatus.SUCCEEDED, turn.status().status());
            assertEquals(2, count.get());
            var run = turn.listRuns(null, 10).items().get(0);
            var calls = turn.listModelCalls(run.runId(), null, null, 10).items();
            assertEquals(1, calls.size());
            var call = calls.get(0);
            assertEquals(ModelObservation.PROVIDER_ATTEMPTS, call.observation());
            assertEquals(ModelStatus.COMPLETED, call.status());
            assertEquals(2, call.requests().size());
            assertNotEquals(call.requests().get(0).requestId(), call.requests().get(1).requestId());
            assertEquals(ModelStatus.FAILED, call.requests().get(0).status());
            assertEquals(finalUsage ? 14L : 4L, call.usage().inputTokens());
            assertFalse(call.usageComplete(), "Failed attempts cannot declare complete accounting");
            assertEquals(finalUsage, call.requests().get(1).usageComplete());
            if (!finalUsage) assertNull(call.requests().get(1).usage());
            assertEquals(call, turn.getModelCall(run.runId(), call.modelCallId()));
            assertEquals(calls, turn.listModelCalls(run.runId(), call.stepId(), null, 10).items());
            var thread = new ThreadKey(key, "main");
            var checkpoints = backend.checkpoints(thread, 0, 1000);
            var fact =
                    backend
                            .checkpointJournal(
                                    thread,
                                    Long.parseLong(
                                            checkpoints.get(checkpoints.size() - 1).position()),
                                    0,
                                    1000)
                            .stream()
                            .filter(value -> "model/end".equals(value.path("type").asText()))
                            .map(value -> DurableJson.parse(value.path("payloadJson").asText()))
                            .findFirst()
                            .orElseThrow();
            if (finalUsage) {
                assertEquals(
                        10,
                        fact.path(ModelCapture.OUTCOME).path("usage").path("inputTokens").asInt());
            } else {
                assertTrue(fact.path("usage").isNull());
                assertFalse(
                        fact.path(ModelCapture.OUTCOME).has("usage"),
                        "Replay must not inherit usage from a failed transport attempt");
            }
        }
    }

    @Test
    void completedModelSurvivesCrashBeforeStateApplication() throws Exception {
        var model = new ScriptedModel(false);
        var crash = new AtomicBoolean();
        var armed = new AtomicBoolean();
        try (var storage = LocalBackend.open(directory.resolve("model-replay"))) {
            var backend = spy(storage);
            doAnswer(
                            invocation -> {
                                Commit command = invocation.getArgument(1);
                                if (command.journal() != null) {
                                    boolean ended =
                                            command.journal().entries().stream()
                                                    .map(
                                                            value ->
                                                                    DurableJson.convert(
                                                                            value,
                                                                            JournalEvent.class))
                                                    .anyMatch(
                                                            event ->
                                                                    event.type()
                                                                            .equals("model/end"));
                                    if (armed.get()) {
                                        crash.set(true);
                                        throw new DurableException(
                                                DurableException.Code.UNAVAILABLE,
                                                "Simulated process loss after model outcome");
                                    }
                                    Object receipt = invocation.callRealMethod();
                                    if (ended) armed.set(true);
                                    return receipt;
                                }
                                return invocation.callRealMethod();
                            })
                    .when(backend)
                    .commit(any(), any());
            String turn;
            try (var runtime =
                    runtime(backend, adapter(model, PermissionMode.BYPASS, Map.of()), null)) {
                runtime.createSession(key, "agent").mainThread().view().definition();
                turn = runtime.getSession(key).submit("one", "hello").turnId();
                runtime.tick(new ThreadKey(key, "main"));
                assertTrue(crash.get());
                assertFalse(backend.turn(new ThreadKey(key, "main"), turn).status().terminal());
            }
            Thread.sleep(5);
            try (var runtime =
                    runtime(storage, adapter(model, PermissionMode.BYPASS, Map.of()), null)) {
                runtime.tick(new ThreadKey(key, "main"));
                assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
                assertEquals(
                        TurnStatus.SUCCEEDED,
                        storage.turn(new ThreadKey(key, "main"), turn).status());
                assertEquals(
                        1,
                        model.inputs.size(),
                        "Persisted model outcome must replay before another provider call");
                var publicMessages = storage.snapshot(key).projection().path("messages");
                assertEquals(2, publicMessages.size(), "One user input and one logical answer");
                var answer =
                        StreamSupport.stream(publicMessages.spliterator(), false)
                                .filter(
                                        m ->
                                                "assistant"
                                                        .equals(
                                                                m.path("message")
                                                                        .path("role")
                                                                        .asText()))
                                .findFirst()
                                .orElseThrow();
                assertEquals("done", answer.path("text").asText());
                assertEquals(1, answer.path("attempt").asLong());
                assertEquals(
                        1, answer.path("revision").asLong(), "Replay replaces the partial stream");
                var projection = new EventProjection(key);
                for (var event : storage.events(key, null, 1000).events()) projection.accept(event);
                assertEquals(
                        DurableJson.parse(storage.snapshot(key).projection().toString()),
                        DurableJson.parse(projection.value().toString()));
                var runs = storage.runs(new ThreadKey(key, "main"), turn, null, 10).items();
                assertEquals(2, runs.size());
                assertEquals(
                        1,
                        storage.modelCalls(
                                        new ThreadKey(key, "main"),
                                        turn,
                                        runs.get(0).runId(),
                                        null,
                                        null,
                                        10)
                                .items()
                                .size());
                assertTrue(
                        storage.modelCalls(
                                        new ThreadKey(key, "main"),
                                        turn,
                                        runs.get(1).runId(),
                                        null,
                                        null,
                                        10)
                                .items()
                                .isEmpty(),
                        "Replaying a committed result must not invent a new model call or usage");
                var first =
                        storage.steps(
                                        new ThreadKey(key, "main"),
                                        turn,
                                        runs.get(0).runId(),
                                        null,
                                        10)
                                .items();
                var second =
                        storage.steps(
                                        new ThreadKey(key, "main"),
                                        turn,
                                        runs.get(1).runId(),
                                        null,
                                        10)
                                .items();
                assertEquals(1, first.size());
                assertEquals(1, second.size());
                assertEquals(first.get(0).stepId(), second.get(0).resumedFromStepId());
                var checkpoints = storage.checkpoints(new ThreadKey(key, "main"), 0, 1000);
                var journal =
                        storage.checkpointJournal(
                                new ThreadKey(key, "main"),
                                Long.parseLong(checkpoints.get(checkpoints.size() - 1).position()),
                                0,
                                1000);
                assertEquals(
                        1,
                        journal.stream()
                                .filter(e -> "model/dispatch".equals(e.path("type").asText()))
                                .count());
            }
        }
    }

    @Test
    void realHarnessMemoryFinalizationReplaysRecordedSummaryAfterRestart() throws Exception {
        var calls = new AtomicInteger();
        var armed = new AtomicBoolean();
        var crashed = new AtomicBoolean();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "native-memory";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.incrementAndGet();
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text(
                                                                        tools == null
                                                                                        || tools
                                                                                                .isEmpty()
                                                                                ? "NO_REPLY"
                                                                                : "done")
                                                                .build()))
                                        .build());
                    }
                };
        var adapter =
                AgentScopeAdapter.forHarness(
                        binding -> {
                            assertEquals(
                                    Boolean.TRUE,
                                    binding.context().get("agentscope.memory.inlineFlush"));
                            return HarnessAgent.builder()
                                    .name("memory")
                                    .model(model)
                                    .workspace("/work")
                                    .abstractFilesystem(
                                            new RemoteFilesystem(
                                                    binding.workspace(), List.of("workspace")));
                        },
                        Map.of());
        try (var storage = LocalBackend.open(directory.resolve("native-memory"))) {
            var backend = spy(storage);
            doAnswer(
                            invocation -> {
                                Commit commit = invocation.getArgument(1);
                                if (armed.get()) {
                                    crashed.set(true);
                                    throw new DurableException(
                                            DurableException.Code.UNAVAILABLE,
                                            "simulated crash after summary outcome");
                                }
                                var result = invocation.callRealMethod();
                                if (commit.journal() != null
                                        && commit.journal().entries().stream()
                                                .anyMatch(
                                                        entry ->
                                                                "model/end"
                                                                                .equals(
                                                                                        entry.path(
                                                                                                        "type")
                                                                                                .asText())
                                                                        && "SUMMARY"
                                                                                .equals(
                                                                                        DurableJson
                                                                                                .parse(
                                                                                                        entry.path(
                                                                                                                        "payloadJson")
                                                                                                                .asText())
                                                                                                .path(
                                                                                                        "purpose")
                                                                                                .asText())))
                                    armed.set(true);
                                return result;
                            })
                    .when(backend)
                    .commit(any(), any());
            String turn;
            try (var first = runtime(backend, adapter, null)) {
                first.createSession(key, "agent").mainThread().view().definition();
                turn = first.getSession(key).submit("input", "hello").turnId();
                first.tick(new ThreadKey(key, "main"));
                assertTrue(
                        crashed.get(),
                        "Real memory summary must pass through the durable model journal");
                assertFalse(storage.turn(new ThreadKey(key, "main"), turn).status().terminal());
            }
            armed.set(false);
            Thread.sleep(5);
            try (var recovered = runtime(storage, adapter, null)) {
                recovered.tick(new ThreadKey(key, "main"));
                assertNull(recovered.lastFailure(new ThreadKey(key, "main")));
                assertEquals(
                        TurnStatus.SUCCEEDED,
                        storage.turn(new ThreadKey(key, "main"), turn).status());
                assertEquals(
                        2,
                        calls.get(),
                        "The recorded primary and memory model outcomes must not repeat");
            }
        }
    }

    @Test
    void finalizationReplaysCompletedAuxiliaryModelAndAtomicWorkspaceIo() throws Exception {
        var primary = new ScriptedModel(false);
        var auxiliary = new ScriptedModel(false);
        var hooks = new AtomicInteger();
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding -> {
                            MiddlewareBase hook =
                                    new MiddlewareBase() {
                                        public Set<ExtensionPoint> activePoints() {
                                            return Set.of(ExtensionPoint.ON_AGENT_FINISH);
                                        }

                                        public Mono<Void> onAgentFinish(
                                                Agent agent, RuntimeContext context, Msg result) {
                                            return RecordedModels.wrap(auxiliary, context, "memory")
                                                    .stream(
                                                            List.of(
                                                                    Msg.builder()
                                                                            .textContent(
                                                                                    "write memory")
                                                                            .build()),
                                                            List.of(),
                                                            null)
                                                    .then(
                                                            Mono.fromRunnable(
                                                                    () -> {
                                                                        var existing =
                                                                                binding.workspace()
                                                                                        .get(
                                                                                                List
                                                                                                        .of(
                                                                                                                "memory"),
                                                                                                "count");
                                                                        int count =
                                                                                existing == null
                                                                                        ? 0
                                                                                        : ((Number)
                                                                                                        existing.value()
                                                                                                                .get(
                                                                                                                        "count"))
                                                                                                .intValue();
                                                                        binding.workspace()
                                                                                .put(
                                                                                        List.of(
                                                                                                "memory"),
                                                                                        "count",
                                                                                        Map.of(
                                                                                                "count",
                                                                                                count
                                                                                                        + 1));
                                                                        var files =
                                                                                new RemoteFilesystem(
                                                                                        binding
                                                                                                .workspace(),
                                                                                        List.of(
                                                                                                "finalization-files"));
                                                                        assertNull(
                                                                                files.write(
                                                                                                context,
                                                                                                "/summary.txt",
                                                                                                "completed")
                                                                                        .error());
                                                                        if (hooks.getAndIncrement()
                                                                                == 0) {
                                                                            binding.context()
                                                                                    .get(
                                                                                            HarnessContext
                                                                                                    .class)
                                                                                    .stop();
                                                                            throw new DurableException(
                                                                                    DurableException
                                                                                            .Code
                                                                                            .FENCED,
                                                                                    "Crash inside"
                                                                                        + " finalization");
                                                                        }
                                                                    }));
                                        }
                                    };
                            return ReActAgent.builder()
                                    .name("finalize")
                                    .model(primary)
                                    .middleware(hook);
                        },
                        Map.of());
        try (var backend = LocalBackend.open(directory.resolve("finalize"))) {
            String turn;
            try (var runtime = runtime(backend, adapter, null)) {
                runtime.createSession(key, "agent").mainThread().view().definition();
                turn = runtime.getSession(key).submit("first", "hello").turnId();
                runtime.tick(new ThreadKey(key, "main"));
                assertFalse(backend.turn(new ThreadKey(key, "main"), turn).status().terminal());
            }
            Thread.sleep(5);
            try (var runtime = runtime(backend, adapter, null)) {
                runtime.tick(new ThreadKey(key, "main"));
                assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
                assertEquals(
                        TurnStatus.SUCCEEDED,
                        backend.turn(new ThreadKey(key, "main"), turn).status());
                assertEquals(1, primary.inputs.size());
                assertEquals(1, auxiliary.inputs.size());
                assertEquals(2, hooks.get());
                var runs = backend.runs(new ThreadKey(key, "main"), turn, null, 10).items();
                assertEquals(2, runs.size());
                var firstSteps =
                        backend.steps(
                                        new ThreadKey(key, "main"),
                                        turn,
                                        runs.get(0).runId(),
                                        null,
                                        10)
                                .items();
                var resumedSteps =
                        backend.steps(
                                        new ThreadKey(key, "main"),
                                        turn,
                                        runs.get(1).runId(),
                                        null,
                                        10)
                                .items();
                assertEquals(1, firstSteps.size());
                assertEquals(1, resumedSteps.size());
                assertEquals(StepPhase.FINALIZATION, firstSteps.get(0).phase());
                assertEquals(StepPhase.FINALIZATION, resumedSteps.get(0).phase());
                assertEquals(firstSteps.get(0).stepId(), resumedSteps.get(0).resumedFromStepId());
                assertNotEquals(firstSteps.get(0).stepId(), resumedSteps.get(0).stepId());
                var checkpoints = backend.checkpoints(new ThreadKey(key, "main"), 0, 1000);
                var resources =
                        backend.checkpointResources(
                                new ThreadKey(key, "main"),
                                Long.parseLong(checkpoints.get(checkpoints.size() - 1).position()));
                assertEquals(
                        1,
                        resources.stream()
                                .filter(value -> value.key().equals("count"))
                                .findFirst()
                                .orElseThrow()
                                .value()
                                .path("count")
                                .asInt());
            }
        }
    }

    @Test
    void nativeHarnessWorkspaceForkCopiesPrivateFilesWithoutSharingSubsequentWrites() {
        var copied = new AtomicBoolean();
        var adapter =
                AgentScopeAdapter.forHarness(
                        binding -> {
                            var files =
                                    new RemoteFilesystem(binding.workspace(), List.of("workspace"));
                            var context = binding.context();
                            if (new ThreadKey(key, "main").equals(binding.definition().key())) {
                                assertNull(
                                        files.write(context, "/work/note.txt", "blue notebook")
                                                .error());
                            } else {
                                assertEquals(
                                        "blue notebook",
                                        files.read(context, "/work/note.txt", 0, 100)
                                                .fileData()
                                                .content());
                                copied.set(true);
                                assertNull(
                                        files.write(context, "/work/child.txt", "fork only")
                                                .error());
                            }
                            return HarnessAgent.builder()
                                    .name("files")
                                    .model(new ScriptedModel(false))
                                    .workspace("/work")
                                    .abstractFilesystem(files);
                        },
                        Map.of());
        try (var backend = LocalBackend.open(directory.resolve("workspace-fork"));
                var runtime = runtime(backend, adapter, null)) {
            runtime.createSession(key, "agent").mainThread().view().definition();
            var first = runtime.getSession(key).submit("first", "hello");
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertEquals(
                    TurnStatus.SUCCEEDED,
                    backend.turn(new ThreadKey(key, "main"), first.turnId()).status());
            var checkpoints = backend.checkpoints(new ThreadKey(key, "main"), 0, 1000);
            long position = Long.parseLong(checkpoints.get(checkpoints.size() - 1).position());
            var child = new ThreadKey(key, "workspace-fork");
            var target =
                    new ThreadSpec(
                            child,
                            new ThreadBinding(
                                    "agent", "v1", AgentScopeCheckpointCodec.STATE_VERSION));
            var forked = runtime.getSession(key).mainThread().fork(position, child, "fork");
            assertEquals(target, forked.view().definition());
            var next = forked.submit("next", "hello");
            runtime.tick(child);
            assertNull(runtime.lastFailure(child));
            assertEquals(TurnStatus.SUCCEEDED, backend.turn(child, next.turnId()).status());
            assertTrue(copied.get());
            assertTrue(
                    backend.checkpointResources(new ThreadKey(key, "main"), position).stream()
                            .noneMatch(value -> value.key().contains("child.txt")));
        }
    }

    @Test
    void settledCheckpointForkPreservesHistoryAndRewritesNativeIdentity() {
        var model = new ScriptedModel(false);
        try (var backend = LocalBackend.open(directory.resolve("fork"));
                var runtime =
                        runtime(backend, adapter(model, PermissionMode.BYPASS, Map.of()), null)) {
            runtime.createSession(key, "agent").mainThread().view().definition();
            var first = runtime.getSession(key).submit("first", "remember the blue notebook");
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertEquals(
                    TurnStatus.SUCCEEDED,
                    backend.turn(new ThreadKey(key, "main"), first.turnId()).status());
            var checkpoints = backend.checkpoints(new ThreadKey(key, "main"), 0, 1000);
            long position = Long.parseLong(checkpoints.get(checkpoints.size() - 1).position());
            var child = new SessionKey(key.namespace(), "fork");
            runtime.createSession(child, "agent");
            var definition =
                    new ThreadSpec(
                            new ThreadKey(child, "main"),
                            new ThreadBinding(
                                    "agent", "v1", AgentScopeCheckpointCodec.STATE_VERSION));
            var forked =
                    runtime.getSession(key)
                            .mainThread()
                            .fork(position, new ThreadKey(child, "main"), "fork-command");
            assertEquals(definition, forked.view().definition());
            assertEquals(
                    forked.key(),
                    runtime.getSession(key)
                            .mainThread()
                            .fork(position, new ThreadKey(child, "main"), "fork-command")
                            .key());
            var second =
                    runtime.getSession(child).submit("second", "what did I ask you to remember?");
            runtime.tick(new ThreadKey(child, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(child, "main")));
            assertEquals(
                    TurnStatus.SUCCEEDED,
                    backend.turn(new ThreadKey(child, "main"), second.turnId()).status());
            assertTrue(
                    model.inputs.get(1).stream()
                            .anyMatch(
                                    message -> message.getTextContent().contains("blue notebook")));
            assertEquals(
                    Long.toString(position),
                    backend.checkpoints(new ThreadKey(key, "main"), 0, 1000)
                            .get(checkpoints.size() - 1)
                            .position());
            assertThrows(
                    DurableException.class,
                    () ->
                            new AgentScopeCheckpoints(backend)
                                    .restore(
                                            new ThreadKey(key, "main"),
                                            position,
                                            new ThreadSpec(
                                                    new ThreadKey(
                                                            new SessionKey("other", "fork"),
                                                            "main"),
                                                    new ThreadBinding(
                                                            "agent",
                                                            "v1",
                                                            AgentScopeCheckpointCodec
                                                                    .STATE_VERSION)),
                                            0,
                                            "cross-scope"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "commit"})
    void pausePreservesFinalReplyAcrossHostRestartAndCheckpointControlRace(String boundary) {
        var calls = new AtomicInteger();
        var finishes = new AtomicInteger();
        var requested = new AtomicBoolean();
        var target = new AtomicReference<DurableTurn>();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "pause-fixture";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.incrementAndGet();
                        if (boundary.equals("model") && requested.compareAndSet(false, true)) {
                            target.get().pause("pause");
                            assertEquals(TurnStatus.PAUSING, target.get().status().status());
                        }
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("original reply")
                                                                .build()))
                                        .build());
                    }
                };
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding ->
                                ReActAgent.builder()
                                        .name("paused")
                                        .model(model)
                                        .middleware(
                                                new MiddlewareBase() {
                                                    public Mono<Void> onAgentFinish(
                                                            Agent agent,
                                                            RuntimeContext context,
                                                            Msg reply) {
                                                        assertEquals(
                                                                "original reply",
                                                                reply.getTextContent());
                                                        finishes.incrementAndGet();
                                                        return Mono.empty();
                                                    }
                                                }),
                        Map.of());
        try (var backend = spy(LocalBackend.open(directory.resolve("pause-" + boundary)))) {
            doAnswer(
                            invocation -> {
                                Commit command = invocation.getArgument(1);
                                if (boundary.equals("commit")
                                        && command.journal() != null
                                        && command.journal().entries().stream()
                                                .anyMatch(
                                                        entry ->
                                                                DurableEventSchemas.START.equals(
                                                                        entry.path("type")
                                                                                .asText()))
                                        && requested.compareAndSet(false, true)) {
                                    target.get().pause("pause");
                                    assertEquals(
                                            TurnStatus.PAUSING, target.get().status().status());
                                }
                                return invocation.callRealMethod();
                            })
                    .when(backend)
                    .commit(any(), any());
            String turnId;
            try (var first = runtime(backend, adapter, null)) {
                var turn = first.createSession(key, "agent").submit("task", "hello");
                target.set(turn);
                turnId = turn.turnId();
                first.tick(new ThreadKey(key, "main"));
                assertTrue(requested.get());
                assertNull(first.lastFailure(new ThreadKey(key, "main")));
                assertEquals(TurnStatus.PAUSED, turn.status().status());
                assertEquals(1, calls.get());
                assertEquals(0, finishes.get());
                var run = turn.listRuns(null, 10).items().get(0);
                assertEquals(RunStatus.STOPPED, run.status());
                assertEquals("HOST_PAUSED", run.reason());
                first.tick(new ThreadKey(key, "main"));
                assertEquals(1, calls.get());
            }
            try (var replacement = runtime(backend, adapter, null)) {
                var turn = replacement.getSession(key).getTurn(turnId);
                turn.resume("resume", turn.status().revision());
                replacement.tick(new ThreadKey(key, "main"));
                assertNull(replacement.lastFailure(new ThreadKey(key, "main")));
                assertEquals(TurnStatus.SUCCEEDED, turn.status().status());
                assertEquals(1, calls.get(), "Finalization recovery must reuse the saved reply");
                assertEquals(1, finishes.get());
                var runs = turn.listRuns(null, 10).items();
                assertEquals(
                        List.of(RunStatus.STOPPED, RunStatus.COMPLETED),
                        runs.stream().map(RunView::status).toList());
                var before = turn.listSteps(runs.get(0).runId(), null, 10).items();
                var after = turn.listSteps(runs.get(1).runId(), null, 10).items();
                assertEquals(
                        before.get(before.size() - 1).stepId(), after.get(0).resumedFromStepId());
                assertNotEquals(before.get(0).stepId(), after.get(0).stepId());
                var projection = new EventProjection(key);
                backend.events(key, null, 200).events().forEach(projection::accept);
                assertEquals(
                        DurableJson.hash(backend.snapshot(key).projection()),
                        DurableJson.hash(projection.value()));
                assertEquals(2, projection.value().path("messages").size());
            }
        }
    }

    @Test
    void pausedHandsResultIsAppliedByANewRunWithoutStartingTheToolAgain() throws Exception {
        var model = new ScriptedModel(true);
        var starts = new AtomicInteger();
        var started = new CountDownLatch(1);
        var request = new AtomicReference<ExecutionRequest>();
        var adapter = adapter(model, PermissionMode.BYPASS, Map.of("job", "jobs"));
        Executor executor =
                new Executor() {
                    public Capabilities capabilities() {
                        return new Capabilities(true, true, true);
                    }

                    public ExecutionResult startExecution(ExecutionRequest value) {
                        starts.incrementAndGet();
                        request.set(value);
                        started.countDown();
                        return query(value, value.executionId());
                    }

                    public ExecutionResult query(ExecutionRequest value, String handle) {
                        return new ExecutionResult(
                                ExecutionState.RUNNING, value.executionId(), null, null);
                    }

                    public ExecutionResult cancel(ExecutionRequest value, String handle) {
                        throw new AssertionError(
                                "Pause must not cancel an admitted Hands operation");
                    }
                };
        try (var backend = LocalBackend.open(directory.resolve("pause-hands"))) {
            String turnId;
            try (var first = runtime(backend, adapter, executor)) {
                var turn = first.createSession(key, "agent").submit("task", "job");
                turnId = turn.turnId();
                first.start();
                assertTrue(started.await(5, TimeUnit.SECONDS));
                turn.pause("pause");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (turn.status().status() != TurnStatus.PAUSED && System.nanoTime() < deadline)
                    Thread.sleep(5);
                assertEquals(TurnStatus.PAUSED, turn.status().status());
                backend.acceptResult(
                        new ThreadKey(key, "main"),
                        request.get().executionId(),
                        new ExecutionResult(
                                ExecutionState.SUCCEEDED,
                                request.get().executionId(),
                                DurableJson.value("original Hands result"),
                                null));
                assertEquals(TurnStatus.PAUSED, turn.status().status());
                assertEquals(1, model.inputs.size());
                assertFalse(
                        backend.execution(new ThreadKey(key, "main"), request.get().executionId())
                                .applied());
            }
            try (var replacement = runtime(backend, adapter, executor)) {
                var turn = replacement.getSession(key).getTurn(turnId);
                replacement.start();
                turn.resume("resume", turn.status().revision());
                assertEquals(
                        TurnStatus.SUCCEEDED,
                        turn.await(Duration.ofSeconds(5))
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                                .status());
                assertEquals(1, starts.get());
                assertEquals(2, model.inputs.size());
                assertEquals(
                        1,
                        model.inputs.get(1).stream()
                                .flatMap(
                                        message ->
                                                message
                                                        .getContentBlocks(ToolResultBlock.class)
                                                        .stream())
                                .filter(result -> "call-1".equals(result.getId()))
                                .count());
                assertTrue(
                        backend.execution(new ThreadKey(key, "main"), request.get().executionId())
                                .applied());
                assertEquals(2, turn.listRuns(null, 10).items().size());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 429, 503})
    void nativeModelFailureUsesProviderStatusForDurableRecovery(int status) {
        var model =
                new Model() {
                    public String getModelName() {
                        return "provider-status-fixture";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.error(
                                new HttpTransportException("provider failure", status, ""));
                    }
                };
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding -> ReActAgent.builder().name("failure").model(model), Map.of());
        try (var backend = LocalBackend.open(directory.resolve("provider-" + status));
                var runtime = runtime(backend, adapter, null)) {
            var turn = runtime.createSession(key, "agent").submit("request", "input");
            runtime.tick(new ThreadKey(key, "main"));
            var state = turn.status();
            assertEquals(status == 400 ? TurnStatus.FAILED : TurnStatus.WAITING, state.status());
            assertEquals(1, state.recovery().failures());
            assertEquals(status != 400, state.recovery().retryAllowed());
            var runs = turn.listRuns(null, 10).items();
            assertEquals(1, runs.size());
            assertEquals(RunStatus.FAILED, runs.get(0).status());
        }
    }

    @Test
    void failedNativeInvocationRecoversAutomaticallyWithANewHostWithoutAUserSignal()
            throws Exception {
        var failing = new AtomicBoolean(true);
        var successful = new ScriptedModel(false);
        Model model =
                new Model() {
                    public String getModelName() {
                        return "retryable-fixture";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return failing.get()
                                ? Flux.concat(
                                        Flux.just(
                                                ChatResponse.builder()
                                                        .content(
                                                                List.of(
                                                                        TextBlock.builder()
                                                                                .text(
                                                                                        "discard"
                                                                                            + " partial")
                                                                                .build()))
                                                        .build()),
                                        Flux.error(
                                                new HttpTransportException(
                                                        "temporary model failure", 503, "")))
                                : successful.stream(messages, tools, options);
                    }
                };
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding -> ReActAgent.builder().name("retryable").model(model), Map.of());
        try (var backend = LocalBackend.open(directory.resolve("native-retry"))) {
            String turnId;
            try (var first = runtime(backend, adapter, null)) {
                var turn = first.createSession(key, "agent").submit("first", "original");
                turnId = turn.turnId();
                first.tick(new ThreadKey(key, "main"));
                var waiting = turn.status();
                assertEquals(TurnStatus.WAITING, waiting.status());
                assertNull(waiting.waiting());
                assertEquals(1, waiting.recovery().failures());
                assertTrue(waiting.recovery().retryAllowed());
            }
            failing.set(false);
            try (var replacement = runtime(backend, adapter, null)) {
                replacement.start();
                replacement
                        .getSession(key)
                        .getTurn(turnId)
                        .await(Duration.ofSeconds(5))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                assertNull(replacement.lastFailure(new ThreadKey(key, "main")));
                assertEquals(
                        TurnStatus.SUCCEEDED,
                        backend.turn(new ThreadKey(key, "main"), turnId).status());
                var messages = backend.snapshot(key).projection().path("messages");
                assertEquals(2, messages.size());
                var answer =
                        StreamSupport.stream(messages.spliterator(), false)
                                .filter(
                                        m ->
                                                "assistant"
                                                        .equals(
                                                                m.path("message")
                                                                        .path("role")
                                                                        .asText()))
                                .findFirst()
                                .orElseThrow();
                assertEquals("done", answer.path("text").asText());
                assertTrue(answer.path("attempt").asLong() >= 2);
            }
            var runs = backend.runs(new ThreadKey(key, "main"), turnId, null, 10).items();
            assertEquals(
                    List.of(RunStatus.FAILED, RunStatus.COMPLETED),
                    runs.stream().map(RunView::status).toList());
            assertEquals(1, successful.inputs.size());
            assertEquals(
                    1,
                    successful.inputs.get(0).stream()
                            .filter(message -> "original".equals(message.getTextContent()))
                            .count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "finalization", "commit"})
    void steeringAtCompletionContinuesSameTurnWithNewRunAfterHostRebuild(String phase) {
        var model = new ScriptedModel(false);
        var injected = new AtomicBoolean();
        try (var storage = LocalBackend.open(directory.resolve("late-steer"))) {
            var backend = spy(storage);
            doAnswer(
                            invocation -> {
                                Commit commit = invocation.getArgument(1);
                                if (phase.equals("commit")
                                        && commit.status() == TurnStatus.SUCCEEDED
                                        && injected.compareAndSet(false, true)) {
                                    Lease lease = invocation.getArgument(0);
                                    backend.signal(
                                            new ThreadKey(key, "main"),
                                            lease.turnId(),
                                            new Signal(
                                                    "late-input",
                                                    "input:steer",
                                                    DurableJson.value("check the blue notebook")));
                                }
                                return invocation.callRealMethod();
                            })
                    .when(backend)
                    .commit(any(), any());
            var adapter =
                    AgentScopeAdapter.forReAct(
                            binding ->
                                    ReActAgent.builder()
                                            .name("late-input")
                                            .model(
                                                    new Model() {
                                                        public String getModelName() {
                                                            return model.getModelName();
                                                        }

                                                        public Flux<ChatResponse> stream(
                                                                List<Msg> messages,
                                                                List<ToolSchema> tools,
                                                                GenerateOptions options) {
                                                            if (phase.equals("model")
                                                                    && injected.compareAndSet(
                                                                            false, true))
                                                                backend.signal(
                                                                        new ThreadKey(key, "main"),
                                                                        binding.context()
                                                                                .getTurnId(),
                                                                        new Signal(
                                                                                "late-input",
                                                                                "input:steer",
                                                                                DurableJson.value(
                                                                                        "check the"
                                                                                            + " blue"
                                                                                            + " notebook")));
                                                            return model.stream(
                                                                    messages, tools, options);
                                                        }
                                                    })
                                            .middleware(
                                                    new MiddlewareBase() {
                                                        public Mono<Void> onAgentFinish(
                                                                Agent agent,
                                                                RuntimeContext context,
                                                                Msg result) {
                                                            if (phase.equals("finalization")
                                                                    && injected.compareAndSet(
                                                                            false, true))
                                                                backend.signal(
                                                                        new ThreadKey(key, "main"),
                                                                        context.getTurnId(),
                                                                        new Signal(
                                                                                "late-input",
                                                                                "input:steer",
                                                                                DurableJson.value(
                                                                                        "check the"
                                                                                            + " blue"
                                                                                            + " notebook")));
                                                            return Mono.empty();
                                                        }
                                                    }),
                            Map.of());
            String turnId;
            try (var first = runtime(backend, adapter, null)) {
                var turn = first.createSession(key, "agent").submit("first", "original");
                turnId = turn.turnId();
                first.tick(new ThreadKey(key, "main"));
                assertNull(first.lastFailure(new ThreadKey(key, "main")));
                assertFalse(turn.status().status().terminal());
                assertEquals(1, model.inputs.size());
            }
            try (var replacement = runtime(backend, adapter, null)) {
                // A released lease defers readiness by the poll interval. Let the replacement
                // scheduler discover it rather than assuming an immediate manual tick can claim it.
                replacement.start();
                replacement
                        .getSession(key)
                        .getTurn(turnId)
                        .await(Duration.ofSeconds(5))
                        .toCompletableFuture()
                        .join();
                assertNull(replacement.lastFailure(new ThreadKey(key, "main")));
                assertEquals(
                        TurnStatus.SUCCEEDED,
                        backend.turn(new ThreadKey(key, "main"), turnId).status());
            }
            assertEquals(2, model.inputs.size());
            assertEquals(
                    1,
                    model.inputs.get(1).stream()
                            .filter(message -> "original".equals(message.getTextContent()))
                            .count());
            assertEquals(
                    1,
                    model.inputs.get(1).stream()
                            .filter(
                                    message ->
                                            "check the blue notebook"
                                                    .equals(message.getTextContent()))
                            .count());
            var runs = backend.runs(new ThreadKey(key, "main"), turnId, null, 10).items();
            assertEquals(2, runs.size());
            assertNotEquals(runs.get(0).runId(), runs.get(1).runId());
            assertTrue(runs.stream().allMatch(run -> run.status() == RunStatus.COMPLETED));
        }
    }

    @Test
    void steeringIsAppliedWithNativeCheckpointAndWithdrawnInputStaysAbsent() {
        var model = new ScriptedModel(false);
        try (var backend = LocalBackend.open(directory.resolve("steer"));
                var runtime =
                        runtime(backend, adapter(model, PermissionMode.BYPASS, Map.of()), null)) {
            runtime.createSession(key, "agent").mainThread().view().definition();
            var turn = runtime.getSession(key).submit("first", "initial");
            backend.signal(
                    new ThreadKey(key, "main"),
                    turn.turnId(),
                    new Signal("keep", "input:steer", DurableJson.value("use the blue notebook")));
            backend.signal(
                    new ThreadKey(key, "main"),
                    turn.turnId(),
                    new Signal("drop", "input:inject", DurableJson.value("discard this")));
            backend.withdraw(new ThreadKey(key, "main"), turn.turnId(), "drop", "withdraw-command");
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertEquals(
                    TurnStatus.SUCCEEDED,
                    backend.turn(new ThreadKey(key, "main"), turn.turnId()).status());
            assertTrue(
                    model.inputs.get(0).stream()
                            .anyMatch(
                                    message -> message.getTextContent().contains("blue notebook")));
            assertFalse(
                    model.inputs.get(0).stream()
                            .anyMatch(
                                    message -> message.getTextContent().contains("discard this")));
            assertEquals(
                    1,
                    backend.events(key, null, 1000).events().stream()
                            .filter(event -> event.type().equals("signal.applied"))
                            .count());
            assertThrows(
                    DurableException.class,
                    () ->
                            backend.withdraw(
                                    new ThreadKey(key, "main"), turn.turnId(), "keep", "too-late"));
        }
    }

    @Test
    void structuredMcpAndNativeResultsRetainContentAndNeverKeepSuspendedState() {
        var call = ToolUseBlock.builder().id("call").name("remote").input(Map.of()).build();
        var value =
                DurableJson.parse(
                        """
                        {"format":"mcp.result.v1","result":{"content":[{"type":"text","text":"hello"},{"type":"image","data":"YWJj","mimeType":"image/png"}],"isError":false,"structuredContent":{"answer":42}}}
                        """);
        var result =
                AgentScopeToolResults.convert(
                        call, new ExecutionResult(ExecutionState.SUCCEEDED, "id", value, null));
        assertEquals("call", result.getId());
        assertTrue(result.getOutput().stream().anyMatch(block -> block instanceof ImageBlock));
        assertEquals(
                42,
                DurableJson.value(result.getMetadata())
                        .path("mcp")
                        .path("structuredContent")
                        .path("answer")
                        .asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "SUCCESS", "ERROR", "DENIED"})
    void settledNativeResultRetainsExplicitOutcomeAndCompletesDefaultChunks(String state) {
        var nativeState = ToolResultState.valueOf(state);
        var nativeResult = ToolResultBlock.text("result").withState(nativeState);
        var envelope =
                DurableJson.value(
                        Map.of(
                                "format",
                                "agentscope.tool-result.v1",
                                "result",
                                JsonUtils.getJsonCodec().convertValue(nativeResult, Map.class)));
        var block =
                AgentScopeToolResults.convert(
                        ToolUseBlock.builder().id("call").name("job").build(),
                        new ExecutionResult(ExecutionState.SUCCEEDED, "execution", envelope, null));
        assertEquals(
                nativeState == ToolResultState.RUNNING ? ToolResultState.SUCCESS : nativeState,
                block.getState());
        assertEquals("result", ((TextBlock) block.getOutput().get(0)).getText());
    }

    @Test
    void batchResultsPreserveMcpMediaAndPerCallMetadata() {
        var mcp =
                DurableJson.parse(
                        "{\"format\":\"mcp.result.v1\",\"result\":{\"content\":[{\"type\":\"image\",\"data\":\"YWJj\",\"mimeType\":\"image/png\"}],\"structuredContent\":{\"answer\":42}}}");
        var batch =
                DurableJson.value(
                        Map.of(
                                "format",
                                "durable.batch.v1",
                                "results",
                                List.of(
                                        Map.of(
                                                "callId",
                                                "media",
                                                "result",
                                                new ExecutionResult(
                                                        ExecutionState.SUCCEEDED,
                                                        "child",
                                                        mcp,
                                                        null)))));
        var block =
                AgentScopeToolResults.convert(
                        ToolUseBlock.builder().id("batch").name("batch").input(Map.of()).build(),
                        new ExecutionResult(ExecutionState.SUCCEEDED, "batch", batch, null));
        assertEquals(ToolResultState.SUCCESS, block.getState());
        assertTrue(block.getOutput().stream().anyMatch(value -> value instanceof ImageBlock));
        assertEquals(
                42,
                DurableJson.value(block.getMetadata())
                        .path("batch")
                        .get(0)
                        .path("metadata")
                        .path("mcp")
                        .path("structuredContent")
                        .path("answer")
                        .asInt());
    }

    @Test
    void textDeltaIsLiveOnlyWhileTheModelIsStillRunning() throws Exception {
        var release = Sinks.<Void>empty();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "streaming";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.just(
                                        ChatResponse.builder()
                                                .id("streaming-reply")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("visible prefix")
                                                                        .build()))
                                                .build())
                                .concatWith(release.asMono().thenMany(Flux.empty()));
                    }
                };
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding -> ReActAgent.builder().name("streaming").model(model), Map.of());
        var liveEvents = LiveEvents.local();
        var received = new CopyOnWriteArrayList<SessionStreamEvent>();
        try (var backend = LocalBackend.open(directory.resolve("streaming"));
                var runtime = runtime(backend, adapter, null, liveEvents)) {
            var turn = runtime.createSession(key, "agent").submit("stream", "hello");
            try (var observation =
                    turn.subscribeEvents(
                            EventStreamOptions.builder().deltas(EventDeltaType.MESSAGE).build(),
                            received::add)) {
                runtime.start();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (received.stream().noneMatch(SessionStreamEvent.EventDelta.class::isInstance)
                        && System.nanoTime() < until) Thread.sleep(10);
                var delta =
                        received.stream()
                                .filter(SessionStreamEvent.EventDelta.class::isInstance)
                                .map(SessionStreamEvent.EventDelta.class::cast)
                                .findFirst()
                                .orElseThrow();
                assertEquals(TurnStatus.RUNNING, turn.status().status());
                assertEquals("visible prefix", delta.data().path("text").asText());
                assertTrue(
                        backend.events(key, null, 100).events().stream()
                                .noneMatch(e -> e.type().equals("message.delta")));
                assertTrue(
                        backend.snapshot(key)
                                .projection()
                                .path("messages")
                                .path(delta.data().path("messageId").asText())
                                .isMissingNode());
            } finally {
                release.tryEmitEmpty();
            }
        }
    }

    @Test
    void liveDeliveryFailureDoesNotChangeTheDurableOutcome() {
        var failingLiveEvents =
                new LiveEvents() {
                    @Override
                    public void publish(LiveEvent event) {
                        throw new IllegalStateException("live transport unavailable");
                    }

                    @Override
                    public AutoCloseable subscribe(Consumer<LiveEvent> listener) {
                        return () -> {};
                    }
                };
        var adapter = adapter(new ScriptedModel(false), PermissionMode.BYPASS, Map.of());
        try (var backend = LocalBackend.open(directory.resolve("live-failure"));
                var runtime = runtime(backend, adapter, null, failingLiveEvents)) {
            var turn = runtime.createSession(key, "agent").submit("request", "hello");

            runtime.tick(new ThreadKey(key, "main"));

            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertEquals(TurnStatus.SUCCEEDED, turn.status().status());
            assertTrue(
                    backend.events(key, null, 100).events().stream()
                            .anyMatch(event -> "message.completed".equals(event.type())));
        }
    }

    @Test
    void eachReasoningRoundKeepsItsOwnPublicMessageAndLiveTransformedDeltas() throws Exception {
        var calls = new AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "multi-message";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        int index = calls.getAndIncrement();
                        var first =
                                ChatResponse.builder()
                                        .id("response-" + index)
                                        .content(
                                                List.of(
                                                        ThinkingBlock.builder()
                                                                .thinking("private reasoning")
                                                                .build(),
                                                        TextBlock.builder()
                                                                .text("raw " + index)
                                                                .build()))
                                        .build();
                        return index < 2
                                ? Flux.just(
                                        first,
                                        ChatResponse.builder()
                                                .id(first.getId())
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("job-" + index)
                                                                        .name("job")
                                                                        .content("{\"topic\":")
                                                                        .build()))
                                                .build(),
                                        ChatResponse.builder()
                                                .id(first.getId())
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("job-" + index)
                                                                        .content(
                                                                                "\"item-" + index
                                                                                        + "\"}")
                                                                        .build()))
                                                .build())
                                : Flux.just(first);
                    }
                };
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding -> {
                            var toolkit = new Toolkit();
                            toolkit.registerAgentTool(
                                    new ToolBase(
                                            ToolBase.builder()
                                                    .name("job")
                                                    .description("local")
                                                    .inputSchema(Map.of("type", "object"))) {
                                        public Mono<ToolResultBlock> callAsync(
                                                ToolCallParam param) {
                                            for (int i = 0; i < 4; i++)
                                                param.getEmitter()
                                                        .emit(
                                                                ToolResultBlock.text(
                                                                        "progress-" + i));
                                            return Mono.just(ToolResultBlock.text("result"));
                                        }
                                    });
                            return ReActAgent.builder()
                                    .name("multi-message")
                                    .model(model)
                                    .toolkit(toolkit)
                                    .permissionContext(
                                            PermissionContextState.builder()
                                                    .mode(PermissionMode.BYPASS)
                                                    .build())
                                    .middleware(
                                            new MiddlewareBase() {
                                                @Override
                                                public Flux<AgentEvent> onModelCall(
                                                        Agent agent,
                                                        RuntimeContext context,
                                                        ModelCallInput input,
                                                        Function<ModelCallInput, Flux<AgentEvent>>
                                                                next) {
                                                    return next.apply(input)
                                                            .map(
                                                                    event ->
                                                                            event
                                                                                            instanceof
                                                                                            TextBlockDeltaEvent
                                                                                                    delta
                                                                                    ? new TextBlockDeltaEvent(
                                                                                            delta
                                                                                                    .getReplyId(),
                                                                                            delta
                                                                                                    .getBlockId(),
                                                                                            delta.getDelta()
                                                                                                    .replace(
                                                                                                            "raw",
                                                                                                            "shown"))
                                                                                    : event);
                                                }
                                            });
                        },
                        Map.of());
        var liveEvents = LiveEvents.local();
        var live = new CopyOnWriteArrayList<LiveEvent>();
        try (var subscription = liveEvents.subscribe(live::add);
                var backend = LocalBackend.open(directory.resolve("messages"));
                var runtime = runtime(backend, adapter, null, liveEvents)) {
            var turn = runtime.createSession(key, "agent").submit("request", "hello");
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertEquals(TurnStatus.SUCCEEDED, turn.status().status());
            assertEquals(3, calls.get());
            var snapshot = backend.snapshot(key);
            var answers =
                    StreamSupport.stream(
                                    snapshot.projection().path("messages").spliterator(), false)
                            .filter(
                                    m ->
                                            "assistant"
                                                    .equals(
                                                            m.path("message")
                                                                    .path("role")
                                                                    .asText()))
                            .sorted(
                                    Comparator.comparing(
                                            m -> new BigInteger(m.path("sequence").asText())))
                            .toList();
            assertEquals(
                    List.of("shown 0", "shown 1", "shown 2"),
                    answers.stream().map(m -> m.path("text").asText()).toList());
            assertEquals(
                    3, answers.stream().map(m -> m.path("messageId").asText()).distinct().count());
            assertTrue(
                    answers.stream()
                            .allMatch(
                                    m ->
                                            m.path("complete").asBoolean()
                                                    && m.hasNonNull("stepId")
                                                    && m.hasNonNull("runId")));
            assertEquals(turn.status().output(), answers.get(2).path("message"));
            assertEquals(2, snapshot.projection().path("tools").size());
            for (int i = 0; i < 2; i++) {
                var tool =
                        snapshot.projection()
                                .path("tools")
                                .path(EventProjection.toolId("main", turn.turnId(), "job-" + i));
                assertEquals("SUCCEEDED", tool.path("data").path("status").asText());
                assertEquals("job", tool.path("data").path("name").asText());
                assertEquals(
                        "item-" + i, tool.path("data").path("arguments").path("topic").asText());
                assertEquals(
                        "{\"topic\":\"item-" + i + "\"}",
                        tool.path("data").path("argumentsText").asText());
                assertEquals(
                        "result", tool.path("data").path("content").get(0).path("text").asText());
                assertEquals(4, tool.path("data").path("progress").size());
                assertEquals(
                        "text", tool.path("data").path("progress").get(0).path("type").asText());
                assertEquals(
                        "progress-3",
                        tool.path("data").path("progress").get(3).path("text").asText());
                assertEquals(
                        answers.get(i).path("messageId").asText(),
                        tool.path("data").path("messageId").asText());
                assertFalse(tool.path("data").has("executionId"));
                assertEquals(turn.turnId(), tool.path("turnId").asText());
            }
            var events = backend.events(key, null, 1000).events();
            var arguments =
                    events.stream().filter(e -> "tool.call.arguments".equals(e.type())).toList();
            assertTrue(arguments.isEmpty());
            assertEquals(
                    4, live.stream().filter(e -> "tool.arguments.delta".equals(e.type())).count());
            assertTrue(events.stream().noneMatch(e -> "message.delta".equals(e.type())));
            var deltas = live.stream().filter(e -> "message.delta".equals(e.type())).toList();
            assertEquals(3, deltas.size());
            assertEquals(
                    List.of("shown 0", "shown 1", "shown 2"),
                    deltas.stream().map(e -> e.data().path("text").asText()).toList());
            assertFalse(DurableJson.value(events).toString().contains("private reasoning"));
            assertFalse(DurableJson.value(events).toString().contains("raw "));
            var projection = new EventProjection(key);
            events.forEach(projection::accept);
            assertEquals(
                    DurableJson.parse(snapshot.projection().toString()),
                    DurableJson.parse(projection.value().toString()));
            DurableProtocol.validate("snapshot", snapshot);
        }
    }

    @Test
    void structuredInputReachesNativeModelAndPublicOutputUsesNeutralProtocol() {
        var model = new ScriptedModel(false);
        var backend =
                new TransactionalSessionBackend(
                        JdbcRecordStore.sqlite(directory.resolve("content.db")));
        try (var runtime =
                runtime(backend, adapter(model, PermissionMode.BYPASS, Map.of()), null)) {
            runtime.createSession(key, "agent").mainThread().view().definition();
            var input =
                    new DurableMessages.Input(
                            "1",
                            List.of(
                                    new DurableMessages.Text("describe"),
                                    new DurableMessages.Image(
                                            new DurableMessages.Url(
                                                    "https://example.org/image.png",
                                                    "image/png"))));
            var turn = runtime.getSession(key).submit("content", input);
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertTrue(
                    model.inputs.get(0).stream()
                            .anyMatch(m -> !m.getContentBlocks(ImageBlock.class).isEmpty()));
            var result = backend.turn(new ThreadKey(key, "main"), turn.turnId());
            assertEquals(TurnStatus.SUCCEEDED, result.status());
            DurableProtocol.validate("message", result.output());
            assertEquals("done", result.output().path("content").get(0).path("text").asText());
            var completed =
                    backend.events(key, null, 100).events().stream()
                            .filter(
                                    e ->
                                            e.type().equals("message.completed")
                                                    && "assistant"
                                                            .equals(
                                                                    e.data()
                                                                            .path("message")
                                                                            .path("role")
                                                                            .asText()))
                            .findFirst()
                            .orElseThrow();
            assertEquals(result.output(), completed.data().get("message"));
            assertEquals(
                    result.output(),
                    backend.snapshot(key)
                            .projection()
                            .path("messages")
                            .path(completed.data().path("messageId").asText())
                            .get("message"));
            var messages = backend.snapshot(key).projection().path("messages");
            assertEquals(2, messages.size());
            assertTrue(
                    StreamSupport.stream(messages.spliterator(), false)
                            .anyMatch(
                                    m ->
                                            "user".equals(m.path("message").path("role").asText())
                                                    && turn.turnId()
                                                            .equals(m.path("turnId").asText())));
            DurableProtocol.validate("snapshot", backend.snapshot(key));
            for (var event : backend.events(key, null, 100).events())
                DurableProtocol.validate("event", event);
            DurableProtocol.validate(
                    "harnessCapabilities",
                    runtime.executionCapabilities().harnesses().get("agent"));
        }
    }

    @Test
    void backendAdmissionWakesNativeAgentAndObserverThroughTheSameBoundary() throws Exception {
        var model = new ScriptedModel(false);
        var backend =
                spy(
                        new TransactionalSessionBackend(
                                        JdbcRecordStore.sqlite(directory.resolve("notify.db")))
                                .withWakeups(Wakeups.local()));
        // Disable discovery in this test to prove direct backend admission drives the native
        // adapter.
        doReturn(List.of()).when(backend).sessions(null, 100);
        try (var runtime =
                DurableRuntime.builder()
                        .backend(backend)
                        .harness("agent", "v1", adapter(model, PermissionMode.BYPASS, Map.of()))
                        .pollInterval(Duration.ofSeconds(30))
                        .build()) {
            runtime.createSession(key, "agent").mainThread().view().definition();
            var projection = new EventProjection(key);
            projection.reset(backend.snapshot(key));
            try (var reader =
                    new SessionEventClient(
                            backend,
                            key,
                            projection.cursor(),
                            projection::reset,
                            projection::accept)) {
                runtime.start();
                var receipt =
                        backend.submit(
                                new ThreadKey(key, "main"), "direct", DurableJson.value("hello"));
                // The full reactor runs many modules concurrently, and a loaded CI host can
                // delay the runtime worker even though the local wakeup has already fired.
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (!backend.turn(new ThreadKey(key, "main"), receipt.turnId())
                                .status()
                                .terminal()
                        && System.nanoTime() < deadline) {
                    reader.poll();
                    reader.awaitChanges(Duration.ofMillis(10));
                }
                reader.poll();
                assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
                assertEquals(
                        TurnStatus.SUCCEEDED,
                        backend.turn(new ThreadKey(key, "main"), receipt.turnId()).status());
                assertEquals(1, model.inputs.size());
                assertTrue(projection.value().toString().contains("done"));
            }
        }
    }

    @Test
    void publicMessagesExcludeReasoningAndRejectNativeObjectInput() {
        var nativeMessage =
                AssistantMessage.builder()
                        .content(
                                TextBlock.builder().text("visible").build(),
                                ThinkingBlock.builder().thinking("private reasoning").build())
                        .build();
        var publicMessage = AgentScopeMessages.output(nativeMessage, "reply");
        DurableProtocol.validate("message", publicMessage);
        assertFalse(DurableJson.value(publicMessage).toString().contains("private reasoning"));
        assertEquals(1, publicMessage.content().size());
        assertThrows(
                DurableException.class,
                () ->
                        AgentScopeMessages.input(
                                DurableJson.value(Map.of("nativeState", "secret")), "input"));
    }

    @Test
    void nativeHistoryAndReliableFinalSurviveRebuiltAgentAndBackend() throws Exception {
        var file = directory.resolve("native.db");
        var firstModel = new ScriptedModel(false);
        var backend = new TransactionalSessionBackend(JdbcRecordStore.sqlite(file));
        try (var runtime =
                runtime(backend, adapter(firstModel, PermissionMode.BYPASS, Map.of()), null)) {
            runtime.createSession(key, "agent").mainThread().view().definition();
            var turn = runtime.getSession(key).submit("one", "remember this");
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertEquals(
                    TurnStatus.SUCCEEDED,
                    backend.turn(new ThreadKey(key, "main"), turn.turnId()).status());
            assertTrue(backend.snapshot(key).projection().toString().contains("done"));
        }
        var reopened = new TransactionalSessionBackend(JdbcRecordStore.sqlite(file));
        var model = new ScriptedModel(false);
        try (var runtime =
                runtime(reopened, adapter(model, PermissionMode.BYPASS, Map.of()), null)) {
            var turn = runtime.getSession(key).submit("two", "continue");
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertEquals(
                    TurnStatus.SUCCEEDED,
                    reopened.turn(new ThreadKey(key, "main"), turn.turnId()).status());
            assertTrue(
                    model.inputs.get(0).stream()
                            .anyMatch(m -> m.getTextContent().contains("remember this")));
            assertTrue(
                    reopened.events(key, null, 100).events().stream()
                            .noneMatch(e -> e.type().contains("checkpoint")));
        }
    }

    @Test
    void harnessFactoryUsesItsWorkspaceAndTheSameDurableNativeJournal() {
        var backend =
                new TransactionalSessionBackend(
                        JdbcRecordStore.sqlite(directory.resolve("harness.db")));
        var adapter =
                AgentScopeAdapter.forHarness(
                        binding ->
                                HarnessAgent.builder()
                                        .name("harness")
                                        .model(new ScriptedModel(false))
                                        .workspace(directory.resolve("workspace")),
                        Map.of());
        try (var runtime = runtime(backend, adapter, null)) {
            runtime.createSession(key, "agent").mainThread().view().definition();
            var turn = runtime.getSession(key).submit("harness", "hello");
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertEquals(
                    TurnStatus.SUCCEEDED,
                    backend.turn(new ThreadKey(key, "main"), turn.turnId()).status());
        }
    }

    @Test
    void completeLocalBackendRunsNativeAgentWhileManagedObserverIsBlocked() throws Exception {
        var model = new ScriptedModel(false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var backend = LocalBackend.open(directory.resolve("local"));
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .harness(
                                        "agent",
                                        "v1",
                                        adapter(model, PermissionMode.BYPASS, Map.of()))
                                .observerParallelism(1)
                                .pollInterval(Duration.ofMillis(10))
                                .build()) {
            runtime.createSession(key, "agent").mainThread().view().definition();
            try (var observer =
                    runtime.getSession(key)
                            .observe(
                                    snapshot -> {
                                        entered.countDown();
                                        try {
                                            assertTrue(release.await(5, TimeUnit.SECONDS));
                                        } catch (InterruptedException error) {
                                            throw new IllegalStateException(error);
                                        }
                                    },
                                    event -> {})) {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var receipt = runtime.getSession(key).submit("request", "hello");
                runtime.start();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (!runtime.getSession(key)
                                .getTurn(receipt.turnId())
                                .status()
                                .status()
                                .terminal()
                        && System.nanoTime() < until) Thread.sleep(5);
                assertEquals(
                        TurnStatus.SUCCEEDED,
                        runtime.getSession(key).getTurn(receipt.turnId()).status().status());
                assertEquals(1, model.inputs.size());
                assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
                release.countDown();
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void reasoningBudgetSurvivesRepeatedToolRecoveryAndResetsForTheNextTurn() throws Exception {
        var reasoning = new AtomicInteger();
        var summaries = new AtomicInteger();
        var starts = new AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "budget-fixture";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (tools.isEmpty()) {
                            summaries.incrementAndGet();
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("budget exhausted")
                                                                    .build()))
                                            .build());
                        }
                        int call = reasoning.incrementAndGet();
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .id("call-" + call)
                                                                .name("job")
                                                                .input(Map.of())
                                                                .build()))
                                        .build());
                    }
                };
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding -> {
                            var toolkit = new Toolkit();
                            toolkit.registerSchema(
                                    ToolSchema.builder()
                                            .name("job")
                                            .description("external")
                                            .parameters(
                                                    Map.of(
                                                            "type",
                                                            "object",
                                                            "properties",
                                                            Map.of()))
                                            .build());
                            return ReActAgent.builder()
                                    .name("budget")
                                    .model(model)
                                    .maxIters(2)
                                    .toolkit(toolkit)
                                    .permissionContext(
                                            PermissionContextState.builder()
                                                    .mode(PermissionMode.BYPASS)
                                                    .build());
                        },
                        Map.of("job", "jobs"));
        Executor executor =
                new Executor() {
                    public Capabilities capabilities() {
                        return new Capabilities(true, true, true);
                    }

                    public ExecutionResult startExecution(ExecutionRequest request) {
                        starts.incrementAndGet();
                        return new ExecutionResult(
                                ExecutionState.SUCCEEDED,
                                request.executionId(),
                                DurableJson.value("done"),
                                null);
                    }

                    public ExecutionResult query(ExecutionRequest request, String handle) {
                        throw new AssertionError("Duplicate collection");
                    }

                    public ExecutionResult cancel(ExecutionRequest request, String handle) {
                        throw new AssertionError("Unexpected cancellation");
                    }
                };
        try (var backend = LocalBackend.open(directory.resolve("budget"))) {
            String turn = null;
            for (int invocation = 0; invocation < 3; invocation++) {
                try (var host = runtime(backend, adapter, executor)) {
                    if (invocation == 0)
                        turn =
                                host.createSession(key, "agent")
                                        .submit("first", "keep working")
                                        .turnId();
                    host.tick(new ThreadKey(key, "main"));
                    assertNull(host.lastFailure(new ThreadKey(key, "main")));
                    assertEquals(
                            invocation < 2 ? TurnStatus.WAITING : TurnStatus.FAILED,
                            backend.turn(new ThreadKey(key, "main"), turn).status());
                }
                Thread.sleep(5);
            }
            assertEquals(2, reasoning.get());
            assertEquals(2, starts.get());
            assertEquals(1, summaries.get());
            assertEquals(
                    3, backend.runs(new ThreadKey(key, "main"), turn, null, 10).items().size());
            try (var host = runtime(backend, adapter, executor)) {
                var next = host.getSession(key).submit("second", "new task");
                host.tick(new ThreadKey(key, "main"));
                assertNull(host.lastFailure(new ThreadKey(key, "main")));
                assertEquals(TurnStatus.WAITING, next.status().status());
                assertEquals(3, reasoning.get());
                assertEquals(1, summaries.get());
            }
        }
    }

    @Test
    void externalToolResultResumesNativeLoopOnceAcrossRuntimeReplacement() throws Exception {
        var file = directory.resolve("tool.db");
        var backend = new TransactionalSessionBackend(JdbcRecordStore.sqlite(file));
        var model = new ScriptedModel(true);
        var starts = new AtomicInteger();
        Executor executor =
                new Executor() {
                    public Capabilities capabilities() {
                        return new Capabilities(true, true, true);
                    }

                    public ExecutionResult startExecution(ExecutionRequest request) {
                        starts.incrementAndGet();
                        return new ExecutionResult(
                                ExecutionState.SUCCEEDED,
                                "operation-1",
                                DurableJson.value("original output"),
                                null);
                    }

                    public ExecutionResult query(ExecutionRequest request, String handle) {
                        throw new AssertionError("No duplicate collection");
                    }

                    public ExecutionResult cancel(ExecutionRequest request, String handle) {
                        throw new AssertionError("Not cancelled");
                    }
                };
        String turnId;
        try (var runtime =
                runtime(
                        backend,
                        adapter(model, PermissionMode.BYPASS, Map.of("job", "jobs")),
                        executor)) {
            runtime.createSession(key, "agent").mainThread().view().definition();
            turnId = runtime.getSession(key).submit("one", "run job").turnId();
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertEquals(
                    TurnStatus.WAITING, backend.turn(new ThreadKey(key, "main"), turnId).status());
            assertEquals(
                    0, starts.get()); // Native checkpoint and intent precede external dispatch.
        }
        Thread.sleep(5);
        var reopened = new TransactionalSessionBackend(JdbcRecordStore.sqlite(file));
        try (var runtime =
                runtime(
                        reopened,
                        adapter(model, PermissionMode.BYPASS, Map.of("job", "jobs")),
                        executor)) {
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertEquals(
                    TurnStatus.SUCCEEDED,
                    reopened.turn(new ThreadKey(key, "main"), turnId).status(),
                    () ->
                            "starts="
                                    + starts
                                    + ", turn="
                                    + reopened.turn(new ThreadKey(key, "main"), turnId)
                                    + ", events="
                                    + reopened.events(key, null, 100));
            assertEquals(1, starts.get());
            assertEquals(2, model.inputs.size());
            var runs = reopened.runs(new ThreadKey(key, "main"), turnId, null, 10).items();
            assertEquals(2, runs.size());
            assertEquals(RunStatus.STOPPED, runs.get(0).status());
            assertEquals(RunStatus.COMPLETED, runs.get(1).status());
            assertEquals(runs.get(0).runId(), runs.get(1).previousRunId());
            var firstSteps =
                    reopened.steps(
                                    new ThreadKey(key, "main"),
                                    turnId,
                                    runs.get(0).runId(),
                                    null,
                                    10)
                            .items();
            var resumedSteps =
                    reopened.steps(
                                    new ThreadKey(key, "main"),
                                    turnId,
                                    runs.get(1).runId(),
                                    null,
                                    10)
                            .items();
            assertEquals(1, firstSteps.size());
            assertEquals(2, resumedSteps.size());
            assertEquals(StepPhase.ACTING, resumedSteps.get(0).phase());
            assertEquals(firstSteps.get(0).stepId(), resumedSteps.get(0).resumedFromStepId());
            assertNotEquals(firstSteps.get(0).stepId(), resumedSteps.get(0).stepId());
            var checkpoints = reopened.checkpoints(new ThreadKey(key, "main"), 0, 100);
            var checkpoint = checkpoints.get(checkpoints.size() - 1);
            var journal =
                    reopened.checkpointJournal(
                            new ThreadKey(key, "main"),
                            Long.parseLong(checkpoint.position()),
                            0,
                            1000);
            var nativeRuns =
                    journal.stream()
                            .filter(e -> "run/start".equals(e.path("type").asText()))
                            .map(e -> e.path("executionRunId").asText())
                            .toList();
            assertEquals(runs.stream().map(RunView::runId).toList(), nativeRuns);
            var toolsView = reopened.snapshot(key).projection().path("tools");
            assertEquals(
                    1, toolsView.size(), "Native and Hands facts refer to the same logical call");
            var toolView =
                    toolsView.path(EventProjection.toolId("main", turnId, "call-1")).path("data");
            assertTrue(toolView.hasNonNull("executionId"));
            assertEquals("SUCCEEDED", toolView.path("status").asText());
            assertEquals("original output", toolView.path("content").path(0).path("text").asText());
            assertTrue(toolView.path("applied").asBoolean());
            assertEquals(runs.get(0).runId(), toolView.path("origin").path("runId").asText());
            assertEquals(
                    firstSteps.get(0).stepId(), toolView.path("origin").path("stepId").asText());
            assertEquals(runs.get(1).runId(), toolView.path("appliedAt").path("runId").asText());
            assertEquals(
                    resumedSteps.get(0).stepId(),
                    toolView.path("appliedAt").path("stepId").asText());
            var execution =
                    reopened.execution(
                            new ThreadKey(key, "main"), toolView.path("executionId").asText());
            assertEquals(
                    DurableJson.value(execution.request().call().origin()),
                    toolView.path("origin"));
            assertEquals(DurableJson.value(execution.appliedAt()), toolView.path("appliedAt"));
            var replay = new EventProjection(key);
            reopened.events(key, null, 1000).events().forEach(replay::accept);
            assertEquals(
                    DurableJson.parse(reopened.snapshot(key).projection().toString()),
                    DurableJson.parse(replay.value().toString()));
            assertTrue(
                    model.inputs.get(1).stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .filter(result -> "call-1".equals(result.getId()))
                            .flatMap(result -> result.getOutput().stream())
                            .anyMatch(
                                    block ->
                                            block instanceof TextBlock text
                                                    && "original output".equals(text.getText())));
            assertEquals(
                    1,
                    reopened.events(key, null, 100).events().stream()
                            .filter(e -> e.type().equals("tool.result.applied"))
                            .count());
        }
    }

    @Test
    void confirmationIsDurableAndDoesNotBypassNativePermissions() throws Exception {
        var backend =
                new TransactionalSessionBackend(
                        JdbcRecordStore.sqlite(directory.resolve("permission.db")));
        var model = new ScriptedModel(true);
        try (var runtime =
                runtime(backend, adapter(model, PermissionMode.DEFAULT, Map.of()), null)) {
            runtime.createSession(key, "agent").mainThread().view().definition();
            var turn = runtime.getSession(key).submit("one", "run job");
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertEquals(
                    TurnStatus.WAITING,
                    backend.turn(new ThreadKey(key, "main"), turn.turnId()).status());
            assertEquals(
                    "interaction:call-1",
                    backend.turn(new ThreadKey(key, "main"), turn.turnId()).waiting().key());
            String interactionId = DurableJson.hash(List.of(turn.turnId(), "call-1"));
            var pending =
                    backend.snapshot(key).projection().path("interactions").path(interactionId);
            assertEquals("pending", pending.path("status").asText());
            assertEquals("confirmation", pending.path("kind").asText());
            assertEquals("job", pending.path("call").path("name").asText());
            assertFalse(pending.path("automatic").asBoolean());

            var approval = runtime.getSession(key).getTurn(turn.turnId());
            var interaction = approval.pendingInteractions().get(0);
            approval.steer("focus", "use the blue notebook");
            approval.inject("drop-context", DurableMessages.Input.text("discard this"));
            approval.withdraw("withdraw-context", "drop-context");
            approval.respond("approval", interaction.id(), false);
            assertEquals(
                    "accepted",
                    backend.snapshot(key)
                            .projection()
                            .path("signals")
                            .path(DurableJson.hash(List.of(turn.turnId(), "approval")))
                            .path("status")
                            .asText());
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertTrue(backend.turn(new ThreadKey(key, "main"), turn.turnId()).status().terminal());
            assertEquals(
                    "resolved",
                    backend.snapshot(key)
                            .projection()
                            .path("interactions")
                            .path(interactionId)
                            .path("status")
                            .asText());
            assertTrue(
                    model.inputs.get(model.inputs.size() - 1).stream()
                            .anyMatch(m -> "use the blue notebook".equals(m.getTextContent())));
            assertTrue(
                    model.inputs.stream()
                            .flatMap(List::stream)
                            .noneMatch(m -> "discard this".equals(m.getTextContent())));
            backend.events(key, null, 1000)
                    .events()
                    .forEach(event -> DurableProtocol.validate("event", event));
            assertEquals(
                    "applied",
                    backend.snapshot(key)
                            .projection()
                            .path("signals")
                            .path(DurableJson.hash(List.of(turn.turnId(), "approval")))
                            .path("status")
                            .asText());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exhaustedRecoverySettlesOriginalToolAndClearsNativeStateForTheNextTurn(
            boolean rejectSettlement) throws Exception {
        var model = new ScriptedModel(true);
        var thread = new ThreadKey(key, "main");
        try (var backend = LocalBackend.open(directory.resolve("failed-settlement"))) {
            var rejecting = new AtomicBoolean(rejectSettlement);
            var observed = spy(backend);
            doAnswer(
                            invocation -> {
                                Commit commit = invocation.getArgument(1);
                                if (rejecting.get()
                                        && commit.status() == TurnStatus.FAILED
                                        && commit.journal() != null)
                                    throw new DurableException(
                                            DurableException.Code.UNAVAILABLE,
                                            "settlement transaction unavailable");
                                return invocation.callRealMethod();
                            })
                    .when(observed)
                    .commit(any(), any());
            String turnId;
            try (var runtime =
                    runtime(
                            observed,
                            adapter(model, PermissionMode.BYPASS, Map.of("job", "jobs")),
                            null)) {
                var turn = runtime.createSession(key, "agent").submit("first", "run job");
                turnId = turn.turnId();
                runtime.tick(thread);
                assertEquals(TurnStatus.WAITING, turn.status().status());
                var owner =
                        backend.claim(thread, "failed-host", Duration.ofSeconds(5)).orElseThrow();
                var execution = backend.frame(owner).tools().get(0).request().executionId();
                backend.beginDispatch(owner, execution);
                backend.acceptResult(
                        thread,
                        execution,
                        new ExecutionResult(
                                ExecutionState.SUCCEEDED,
                                null,
                                DurableJson.value("settled original output"),
                                null));
                backend.recordFailure(
                        owner,
                        new Failure(
                                false, "unrecoverable host failure", new RecoveryPolicy(0, 1, 1)));
                backend.release(owner, Duration.ZERO);
                runtime.tick(thread);
                if (rejectSettlement) {
                    assertEquals(TurnStatus.WAITING, turn.status().status());
                    assertFalse(turn.pendingInteractions().isEmpty());
                    assertFalse(backend.execution(thread, execution).applied());
                    assertEquals(1, turn.status().recovery().failures());
                    rejecting.set(false);
                    Thread.sleep(5);
                    runtime.tick(thread);
                } else assertNull(runtime.lastFailure(thread));
                assertEquals(TurnStatus.FAILED, turn.status().status());
                assertTrue(turn.pendingInteractions().isEmpty());
                assertTrue(backend.execution(thread, execution).applied());
                assertNull(
                        backend.execution(thread, execution).appliedAt(),
                        "Host settlement does not fabricate an Agent invocation");
                assertNotNull(backend.execution(thread, execution).request().call().origin());
                assertEquals(1, model.inputs.size());
                assertEquals(
                        1,
                        turn.listRuns(null, 10).items().size(),
                        "Settlement does not create an Agent invocation");
                assertEquals(
                        1,
                        backend.events(key, null, 1000).events().stream()
                                .filter(event -> event.type().equals("tool.result.applied"))
                                .count());
            }
            var nextModel = new ScriptedModel(false);
            try (var runtime =
                    runtime(backend, adapter(nextModel, PermissionMode.BYPASS, Map.of()), null)) {
                var next = runtime.getSession(key).submit("next", "continue conversation");
                runtime.tick(thread);
                assertNull(runtime.lastFailure(thread));
                assertEquals(TurnStatus.SUCCEEDED, next.status().status());
                var results =
                        nextModel.inputs.get(0).stream()
                                .flatMap(
                                        message ->
                                                message
                                                        .getContentBlocks(ToolResultBlock.class)
                                                        .stream())
                                .filter(result -> "call-1".equals(result.getId()))
                                .toList();
                assertEquals(1, results.size());
                assertEquals(ToolResultState.SUCCESS, results.get(0).getState());
                assertTrue(
                        results.get(0).getOutput().stream()
                                .anyMatch(
                                        block ->
                                                block instanceof TextBlock text
                                                        && text.getText()
                                                                .contains(
                                                                        "settled original"
                                                                                + " output")));
            }
        }
    }

    @Test
    void cancelPendingInteractionClearsNativeStateForNextTurn() throws Exception {
        var backend =
                new TransactionalSessionBackend(
                        JdbcRecordStore.sqlite(directory.resolve("cancel.db")));
        var model = new ScriptedModel(true);
        try (var runtime =
                runtime(backend, adapter(model, PermissionMode.BYPASS, Map.of()), null)) {
            runtime.createSession(key, "agent").mainThread().view().definition();
            var turn = runtime.getSession(key).submit("one", "run job");
            runtime.tick(new ThreadKey(key, "main"));
            backend.cancel(new ThreadKey(key, "main"), turn.turnId(), "cancel");
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertEquals(
                    TurnStatus.CANCELLED,
                    backend.turn(new ThreadKey(key, "main"), turn.turnId()).status());
        }
        var nextModel = new ScriptedModel(false);
        try (var runtime =
                runtime(backend, adapter(nextModel, PermissionMode.BYPASS, Map.of()), null)) {
            var turn = runtime.getSession(key).submit("two", "next turn");
            runtime.tick(new ThreadKey(key, "main"));
            assertNull(runtime.lastFailure(new ThreadKey(key, "main")));
            assertEquals(
                    TurnStatus.SUCCEEDED,
                    backend.turn(new ThreadKey(key, "main"), turn.turnId()).status());
        }
    }
}
