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
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Message;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInteractions;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Timeout(15)
class ManagedToolRecoveryTest {
    @TempDir private Path managedCheckpointRoot;

    @TempDir Path directory;

    @Test
    void newEpochRecoversOriginalHandsResultBeforeFurtherReasoning() throws Exception {
        exercise("execute", true);
    }

    @Test
    void newEpochRestoresTheOriginalSpawnResultWithoutSpawningAgain() throws Exception {
        exercise("agent_spawn", true);
    }

    @Test
    void newEpochRestoresTheOriginalPersistentSendResultWithoutSendingAgain() throws Exception {
        exercise("agent_send", true);
    }

    @Test
    void noOriginalIntentCannotBeReplacedWithANewEffect() throws Exception {
        exercise("execute", false);
    }

    @Test
    void opaqueExternalToolRequiresExplicitReconciliation() throws Exception {
        exercise("send_email", false);
    }

    @Test
    void cancellingPendingConfirmationAllowsTheNextTurn() throws Exception {
        exercise("execute", false, true);
    }

    private void exercise(String name, boolean originalIntent) throws Exception {
        exercise(name, originalIntent, false);
    }

    private void exercise(String name, boolean originalIntent, boolean cancellation)
            throws Exception {
        var mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        var scope = new ManagedScope("t", "n", "s");
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:recovery-"
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
        admission.createSession(scope, "{}");
        String turn = admission.acceptTurn(scope, "u", "key", "{\"message\":\"run\"}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "old");
        var call =
                ToolUseBlock.builder()
                        .id("call")
                        .name(name)
                        .content("{\"command\":\"original-command\",\"timeout\":30}")
                        .input(Map.of("command", "original-command", "timeout", 30))
                        .build();
        var user = UserMessage.builder().id("command_" + turn).textContent("run").build();
        var state = AgentState.builder().build();
        state.contextMutable().add(user);
        state.contextMutable()
                .add(
                        AssistantMessage.builder()
                                .id("assistant")
                                .content(cancellation ? call.withState(ToolCallState.ASKING) : call)
                                .build());
        @SuppressWarnings("unchecked")
        var typedCall =
                new LinkedHashMap<String, Object>(
                        JsonUtils.getJsonCodec()
                                .convertValue(call.withState(ToolCallState.ASKING), Map.class));
        typedCall.put("type", "tool_use");
        var facts =
                List.of(
                        event(turn, 1, "state/checkpoint", Map.of("stateJson", state.toJson())),
                        event(turn, 2, "input/received", Map.of("messages", List.of(user))),
                        event(
                                turn,
                                3,
                                cancellation ? "interaction/requested" : "tool/dispatch",
                                cancellation
                                        ? Map.of(
                                                "requestId",
                                                "call",
                                                "kind",
                                                "confirmation",
                                                "call",
                                                typedCall)
                                        : Map.of(
                                                "toolCallId",
                                                "call",
                                                "actionId",
                                                "action",
                                                "name",
                                                name,
                                                "arguments",
                                                call.getInput())));
        boolean child = List.of("agent_spawn", "agent_send").contains(name);
        var intent =
                new ToolIntent(
                        "call_0",
                        child ? ManagedSubagents.OPERATION : "sandbox.exec",
                        child
                                ? mapper.writeValueAsString(
                                        Map.of(
                                                "native_tool_call_id",
                                                "call",
                                                "subagent",
                                                new ManagedSubagents.Specification(
                                                        "worker",
                                                        "original task",
                                                        "original spawn identity",
                                                        "{}")))
                                : mapper.writeValueAsString(
                                        Map.of(
                                                "command",
                                                "original-command",
                                                "timeout_seconds",
                                                30,
                                                "native_tool_call_id",
                                                "call")));
        journal.commit(
                journal.objects()
                        .prepare(
                                new ManagedRuntimeBatch(
                                        scope,
                                        turn,
                                        1,
                                        "old",
                                        "old-writer",
                                        "old-prefix",
                                        0,
                                        facts,
                                        originalIntent ? List.of(intent) : List.of(),
                                        List.of(),
                                        null)));
        if (cancellation)
            admission.acceptInput(
                    scope, turn, "u", "cancel", ManagedInputInbox.Type.CANCEL, null, "{}");
        if (originalIntent) {
            String task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "call_0");
            var launched = journal.claimTool(scope, task, "hands").task();
            ManagedPushTestSupport.commitToolResult(
                    journal,
                    new ManagedToolResult(
                            scope,
                            task,
                            launched.executionId(),
                            "succeeded",
                            child
                                    ? mapper.writeValueAsString(
                                            new ManagedSubagents.Reply(
                                                    "succeeded",
                                                    JsonUtils.getJsonCodec()
                                                            .toJson(
                                                                    new AssistantMessage(
                                                                            "original-hands-result")),
                                                    null))
                                    : "{\"output\":\"original-hands-result\",\"exitCode\":0,\"truncated\":false}"));
        }
        var bus = new InMemoryManagedMessageBus(() -> System.nanoTime() / 1_000_000, 8);
        var redis = new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1_000_000);
        var inbox = new InMemoryManagedInputInbox(System::currentTimeMillis);
        var ttl = Duration.ofSeconds(30);
        var codec = new ManagedBatchCodec(mapper, directory.resolve("blobs"));
        journal.transport().configure(bus, codec);
        var writer = new ManagedRuntimeWriter(journal);
        bus.publish(Channel.TURNS, new Message(turn, scope.messageGroup(), "{}".getBytes()));
        var originals = new ManagedPushTestSupport.Probe(bus, Channel.TURNS, 2);
        var invocation = originals.next();
        var delivery = invocation.delivery();
        var pump = Executors.newSingleThreadExecutor();
        var controls = Executors.newScheduledThreadPool(2);
        var running = new AtomicBoolean(true);
        var resultDelivery = new InMemoryManagedToolResultDelivery();
        var resultRelay = new ManagedToolResultRelay(journal, codec, resultDelivery);
        var hands = new ManagedBrainToolEvents(codec, journal, resultRelay::delivered);
        for (var channel : List.of(Channel.TOOL_EVENTS))
            bus.subscribe(
                    channel,
                    2,
                    hands::consume,
                    (d, e) -> {
                        throw new AssertionError(e);
                    });
        var future =
                pump.submit(
                        () -> {
                            while (running.get()) {
                                for (var ref : journal.pendingTools(128)) {
                                    var task =
                                            journal.claimTool(ref.scope(), ref.taskId(), "hands")
                                                    .task();
                                    ManagedPushTestSupport.commitToolResult(
                                            journal,
                                            new ManagedToolResult(
                                                    ref.scope(),
                                                    ref.taskId(),
                                                    task.executionId(),
                                                    "succeeded",
                                                    "{\"output\":\"__NOT_FOUND__\",\"exitCode\":0,\"truncated\":false}"));
                                }
                                resultRelay.repair(128);
                                try {
                                    Thread.sleep(2);
                                } catch (InterruptedException stop) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                        });
        var harnessBus = mock(MessageBus.class);
        when(harnessBus.inboxDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
        when(harnessBus.queueDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
        when(harnessBus.queuePeek(anyString())).thenReturn(Mono.just(false));
        when(harnessBus.inboxHasMessages(anyString())).thenReturn(Mono.just(false));
        var requests = new ManagedToolRequests(mapper, resultDelivery, codec);
        try (var guard =
                        ManagedExecutionGuard.acquire(
                                        scope,
                                        bus,
                                        redis,
                                        delivery,
                                        ttl,
                                        System::nanoTime,
                                        () -> {})
                                .orElseThrow();
                var agent =
                        HarnessAgent.builder()
                                .name("recovery")
                                .model(new RecoveredModel(originalIntent))
                                .workspace(directory.resolve("workspace"))
                                .abstractFilesystem(new ManagedRemoteFilesystem(requests))
                                .messageBus(harnessBus)
                                .asyncToolRegistry(new ManagedAsyncToolRegistry())
                                .sessionLogStore(new ManagedSessionLogStore(journal))
                                .permissionContext(
                                        PermissionContextState.builder()
                                                .mode(PermissionMode.BYPASS)
                                                .build())
                                .disableDefaultWorkspaceSkills()
                                .disableMemoryHooks()
                                .disableCompaction()
                                .build()) {
            long epoch = admission.activate(scope, turn, 1, guard.token());
            assertEquals(2, epoch);
            var execution =
                    new ManagedTurnExecution(scope, turn, epoch, guard, admission, journal, writer);
            var context =
                    RuntimeContext.builder()
                            .userId("u")
                            .sessionId("s")
                            .put(ManagedScope.class, scope)
                            .put(ManagedTurnExecution.class, execution)
                            .put(
                                    ManagedToolRequests.Operations.class,
                                    new ManagedToolRequests.Operations())
                            .put(ToolCallParam.CALL_CONTEXT_KEY, true)
                            .put(SessionRecorder.TURN_ID_KEY, turn)
                            .build();
            if (cancellation) {
                var runner =
                        new ManagedHarnessTurns(
                                mapper, new ManagedInputDelivery(admission, inbox), controls);
                runner.run(execution, agent, context);
                long settlementDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (!"cancelled".equals(admission.turn(scope, turn).status())
                        && System.nanoTime() < settlementDeadline) Thread.sleep(10);
                assertEquals("cancelled", admission.turn(scope, turn).status());
                assertTrue(SessionInteractions.pending(execution.log()).isEmpty());
                invocation.succeed();
                assertEquals(1, admission.session(scope).settledSequence());
                guard.close();
                String next =
                        admission
                                .acceptTurn(
                                        scope, "u", "next", "{\"message\":\"after cancellation\"}")
                                .id();
                admission.reservePublication(scope, next);
                admission.confirmPublication(scope, next, "broker2");
                bus.publish(
                        Channel.TURNS, new Message(next, scope.messageGroup(), "{}".getBytes()));
                var nextInvocation = originals.next();
                var nextDelivery = nextInvocation.delivery();
                try (var nextGuard =
                        ManagedExecutionGuard.acquire(
                                        scope,
                                        bus,
                                        redis,
                                        nextDelivery,
                                        ttl,
                                        System::nanoTime,
                                        () -> {})
                                .orElseThrow()) {
                    long nextEpoch = admission.activate(scope, next, 2, nextGuard.token());
                    var nextExecution =
                            new ManagedTurnExecution(
                                    scope, next, nextEpoch, nextGuard, admission, journal, writer);
                    var nextContext =
                            RuntimeContext.builder(context)
                                    .put(ManagedTurnExecution.class, nextExecution)
                                    .put(SessionRecorder.TURN_ID_KEY, next)
                                    .put(
                                            ManagedToolRequests.Operations.class,
                                            new ManagedToolRequests.Operations())
                                    .build();
                    runner.run(nextExecution, agent, nextContext);
                    long nextSettlementDeadline =
                            System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    while (!"succeeded".equals(admission.turn(scope, next).status())
                            && System.nanoTime() < nextSettlementDeadline) Thread.sleep(10);
                    assertEquals("succeeded", admission.turn(scope, next).status());
                }
            } else if (!originalIntent) {
                if (name.equals("execute"))
                    assertThrows(
                            RuntimeException.class,
                            () -> ManagedToolRecovery.reconcile(execution, agent, context));
                else
                    assertEquals(
                            "tool_reconciliation_required",
                            assertThrows(
                                            ManagedStateException.class,
                                            () ->
                                                    ManagedToolRecovery.reconcile(
                                                            execution, agent, context))
                                    .code());
                assertTrue(journal.pendingTools(128).isEmpty());
                assertTrue(
                        SessionProjection.read(execution.log())
                                .uncertainToolCalls()
                                .contains("call"));
            } else {
                new ManagedHarnessTurns(
                                mapper, new ManagedInputDelivery(admission, inbox), controls)
                        .run(execution, agent, context);
                long settlementDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (!"succeeded".equals(admission.turn(scope, turn).status())
                        && System.nanoTime() < settlementDeadline) Thread.sleep(10);
                assertEquals("succeeded", admission.turn(scope, turn).status());
                assertTrue(SessionProjection.read(execution.log()).uncertainToolCalls().isEmpty());
                assertEquals(
                        1,
                        journal.nativeEvents(scope, 0, 256).stream()
                                .filter(e -> e.type().equals("recovery/applied"))
                                .count());
                assertFalse(journal.publicEvents(scope, 0, 256).toString().contains("stateJson"));
            }
        } finally {
            running.set(false);
            future.get();
            pump.shutdownNow();
            controls.shutdownNow();
            originals.close();
            bus.close();
        }
    }

    private static SessionEvent event(String turn, long seq, String type, Object data) {
        return new SessionEvent(
                1,
                "event_" + seq,
                seq,
                System.currentTimeMillis(),
                type,
                "old-run",
                turn,
                true,
                JsonUtils.getJsonCodec().toJson(data));
    }

    private static final class RecoveredModel implements Model {
        private final boolean original;

        RecoveredModel(boolean original) {
            this.original = original;
        }

        public String getModelName() {
            return "recovered";
        }

        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (original)
                assertTrue(
                        messages.stream()
                                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                                .flatMap(r -> r.getOutput().stream())
                                .filter(TextBlock.class::isInstance)
                                .map(TextBlock.class::cast)
                                .anyMatch(t -> t.getText().contains("original-hands-result")));
            assertTrue(
                    messages.stream()
                            .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                            .noneMatch(call -> call.getState() == ToolCallState.ASKING));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("done").build()))
                            .build());
        }
    }
}
