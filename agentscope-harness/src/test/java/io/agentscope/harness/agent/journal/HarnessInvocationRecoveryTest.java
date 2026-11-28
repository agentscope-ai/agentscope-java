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
package io.agentscope.harness.agent.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RunOutcome;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.journal.JournalException;
import io.agentscope.core.journal.ThreadHistory;
import io.agentscope.core.journal.ThreadInteractions;
import io.agentscope.core.journal.ThreadJournal;
import io.agentscope.core.journal.ThreadJournalStore;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolExecutionDetails;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class HarnessInvocationRecoveryTest {
    @TempDir Path workspace;
    private static final Duration WAIT = Duration.ofSeconds(15);

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void publicRecoveryResumesCancelledSessionOnlyAfterVerifiedToolOutcome(
            boolean stream, boolean interruptOnCancel) throws Exception {
        var store = new InMemoryThreadJournalStore();
        var released = new CountDownLatch(1);
        ThreadJournalStore observed =
                (key, ctx) -> {
                    var journal = store.open(key, ctx);
                    return (ThreadJournal)
                            Proxy.newProxyInstance(
                                    ThreadJournal.class.getClassLoader(),
                                    new Class<?>[] {ThreadJournal.class},
                                    (proxy, method, args) -> {
                                        try {
                                            Object result = method.invoke(journal, args);
                                            if (method.getName().equals("release"))
                                                released.countDown();
                                            return result;
                                        } catch (InvocationTargetException error) {
                                            throw error.getCause();
                                        }
                                    });
                };
        var slowStarted = new CountDownLatch(1);
        var slowCancelled = new CountDownLatch(1);
        var fastCompleted = new CountDownLatch(1);
        var slowExecutions = new AtomicInteger();
        var fastExecutions = new AtomicInteger();
        var receipt = new AtomicReference<String>();
        var toolkit = new Toolkit();
        toolkit.registerTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("slow")
                                .description("slow receipt delivery")
                                .inputSchema(Map.of("type", "object"))
                                .concurrencySafe(true)) {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.defer(
                                () -> {
                                    slowExecutions.incrementAndGet();
                                    // The effect has occurred, but its reply has not reached the
                                    // caller. Recovery obtains this receipt independently.
                                    receipt.set("committed-receipt-42");
                                    slowStarted.countDown();
                                    return Mono.<ToolResultBlock>never()
                                            .doOnCancel(slowCancelled::countDown);
                                });
                    }
                });
        toolkit.registerTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("fast")
                                .description("fast result")
                                .inputSchema(Map.of("type", "object"))
                                .concurrencySafe(true)) {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        fastExecutions.incrementAndGet();
                        return Mono.just(ToolResultBlock.success("fast-receipt"));
                    }
                });
        var modelCalls = new AtomicInteger();
        Model model =
                new io.agentscope.core.agent.test.MockModel(
                        messages ->
                                List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        modelCalls.getAndIncrement() == 0
                                                                ? List.of(
                                                                        ToolUseBlock.builder()
                                                                                .id("slow-call")
                                                                                .name("slow")
                                                                                .input(Map.of())
                                                                                .build(),
                                                                        ToolUseBlock.builder()
                                                                                .id("fast-call")
                                                                                .name("fast")
                                                                                .input(Map.of())
                                                                                .build())
                                                                : List.of(
                                                                        TextBlock.builder()
                                                                                .text("continued")
                                                                                .build()))
                                                .build()));
        var context = RuntimeContext.builder().userId("u").sessionId("s").build();
        try (var agent = agent(model, toolkit, observed)) {
            java.util.function.Consumer<io.agentscope.core.event.AgentEvent> collect =
                    event -> {
                        if (event instanceof ToolResultEndEvent end
                                && end.getToolCallId().equals("fast-call"))
                            fastCompleted.countDown();
                    };
            CompletableFuture<?> running =
                    stream
                            ? agent.streamEvents(List.of(new UserMessage("start")), context)
                                    .doOnNext(collect)
                                    .doOnCancel(
                                            () -> {
                                                if (interruptOnCancel) agent.interrupt(context);
                                            })
                                    .collectList()
                                    .toFuture()
                            : agent.call(List.of(new UserMessage("start")), context)
                                    .contextWrite(
                                            ctx ->
                                                    ctx.put(
                                                            AgentEventEmitter
                                                                    .FORWARDING_CONTEXT_KEY,
                                                            (AgentEventEmitter) collect::accept))
                                    .doOnCancel(
                                            () -> {
                                                if (interruptOnCancel) agent.interrupt(context);
                                            })
                                    .toFuture();
            try {
                assertTrue(slowStarted.await(10, TimeUnit.SECONDS));
                assertTrue(fastCompleted.await(10, TimeUnit.SECONDS));
                assertTrue(running.cancel(true));
                assertTrue(slowCancelled.await(10, TimeUnit.SECONDS));
                assertTrue(released.await(10, TimeUnit.SECONDS));
                assertEquals(
                        Set.of("slow-call"), agent.inspectThread(context).uncertainToolCalls());
                assertThrows(
                        JournalException.class,
                        () -> agent.setPermissionMode(context, PermissionMode.DEFAULT));

                long before = agent.inspectThread(context).asOfSeq();
                var unknown =
                        ToolResultBlock.error("slow-call", "cancel requested; outcome unknown")
                                .withIdAndName("slow-call", "slow")
                                .withExecutionDetails(
                                        new ToolExecutionDetails(
                                                "external-operation",
                                                ToolExecutionDetails.Outcome.UNKNOWN,
                                                null,
                                                false));
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                agent.reconcileToolOutcomes(
                                        context, Map.of("slow-call", unknown), "still unknown"));
                assertEquals(before, agent.inspectThread(context).asOfSeq());
                assertEquals(
                        Set.of("slow-call"), agent.inspectThread(context).uncertainToolCalls());

                for (var other :
                        List.of(
                                RuntimeContext.builder().userId("other").sessionId("s").build(),
                                RuntimeContext.builder().userId("u").sessionId("other").build())) {
                    agent.setPermissionMode(other, PermissionMode.DEFAULT);
                    assertTrue(agent.inspectThread(other).uncertainToolCalls().isEmpty());
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    agent.reconcileToolOutcomes(
                                            other,
                                            Map.of(
                                                    "slow-call",
                                                    ToolResultBlock.success(receipt.get())
                                                            .withIdAndName("slow-call", "slow")),
                                            "receipt belongs to a different thread"));
                }

                agent.reconcileToolOutcomes(
                        context,
                        Map.of(
                                "slow-call",
                                ToolResultBlock.success(receipt.get())
                                        .withIdAndName("slow-call", "slow")),
                        "verified original operation receipt");
                assertTrue(agent.inspectThread(context).uncertainToolCalls().isEmpty());
                agent.setPermissionMode(context, PermissionMode.DEFAULT);
                assertEquals(
                        "continued",
                        agent.call(List.of(new UserMessage("continue")), context)
                                .block(WAIT)
                                .getTextContent());
                var results =
                        agent.inspectThread(context).restore().getContext().stream()
                                .flatMap(
                                        message ->
                                                message
                                                        .getContentBlocks(ToolResultBlock.class)
                                                        .stream())
                                .toList();
                assertEquals(
                        1,
                        results.stream()
                                .filter(result -> result.getId().equals("slow-call"))
                                .count());
                assertTrue(
                        results.stream()
                                .anyMatch(
                                        result ->
                                                result.getOutput()
                                                        .toString()
                                                        .contains(receipt.get())));
                assertEquals(1, fastExecutions.get());
                assertEquals(1, slowExecutions.get());
                assertFalse(
                        agent.inspectThread(context).uncertainToolCalls().contains("fast-call"));
            } finally {
                running.cancel(true);
            }
        }
    }

    @Test
    void permissionAnswerUsesExistingHitlAndIsNotReplayedAsChatInput() throws Exception {
        var executions = new AtomicInteger();
        var afterTool = new AtomicInteger();
        var reasoning = new CountDownLatch(1);
        var toolkit = new Toolkit();
        toolkit.registerTool(
                new ToolBase(
                        "approval",
                        "requires permission",
                        Map.of("type", "object"),
                        false,
                        true,
                        false,
                        null,
                        false,
                        false) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState context) {
                        return Mono.just(PermissionDecision.ask("Approve this action"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        executions.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("executed"));
                    }
                });
        Model model =
                new Model() {
                    public String getModelName() {
                        return "permission-test";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        boolean hasResult =
                                messages.stream()
                                        .anyMatch(
                                                m ->
                                                        !m.getContentBlocks(ToolResultBlock.class)
                                                                .isEmpty());
                        if (!hasResult)
                            return response(
                                    ToolUseBlock.builder()
                                            .id("approval-1")
                                            .name("approval")
                                            .input(Map.of())
                                            .build());
                        if (afterTool.getAndIncrement() == 0) {
                            reasoning.countDown();
                            return Flux.interval(Duration.ofMillis(50))
                                    .map(
                                            tick ->
                                                    ChatResponse.builder()
                                                            .content(
                                                                    List.of(
                                                                            TextBlock.builder()
                                                                                    .text(
                                                                                            "working"
                                                                                                + " ")
                                                                                    .build()))
                                                            .build());
                        }
                        return response(TextBlock.builder().text("done").build());
                    }
                };
        var store = new InMemoryThreadJournalStore();
        var context =
                RuntimeContext.builder()
                        .userId("u")
                        .sessionId("s")
                        .turnId("permission-turn")
                        .build();
        try (var agent = agent(model, toolkit, store)) {
            var call = agent.prepareCall(List.of(new UserMessage("perform action")), context);
            call.stream().blockLast(WAIT);
            assertEquals(RunOutcome.Status.SUSPENDED, call.outcome().block(WAIT).status());
            assertEquals(0, executions.get());
        }
        try (var agent = agent(model, toolkit, store)) {
            var log = agent.threadJournal(context);
            var request = ThreadInteractions.pending(log).get("approval-1");
            var tool =
                    JsonUtils.getJsonCodec()
                            .convertValue(request.data().get("call"), ToolUseBlock.class);
            var confirmation =
                    UserMessage.builder()
                            .textContent("")
                            .metadata(
                                    Map.of(
                                            Msg.METADATA_CONFIRM_RESULTS,
                                            List.of(new ConfirmResult(true, tool, null, null))))
                            .build();
            var answer = agent.prepareRun(List.of(confirmation), context);
            var completion = answer.stream().collectList().toFuture();
            assertTrue(reasoning.await(10, TimeUnit.SECONDS));
            assertTrue(answer.interrupt());
            completion.get(15, TimeUnit.SECONDS);
            assertEquals(RunOutcome.Status.INTERRUPTED, answer.outcome().block(WAIT).status());
            assertEquals(1, executions.get());
            assertTrue(ThreadInteractions.pending(log).isEmpty());
            var continuation = agent.prepareCall(List.of(), context);
            continuation.stream().blockLast(WAIT);
            assertEquals(RunOutcome.Status.COMPLETED, continuation.outcome().block(WAIT).status());
            assertNotEquals(answer.runId(), continuation.runId());
            assertEquals(1, executions.get());
            assertTrue(
                    ThreadHistory.transcript(log).messages().stream()
                            .noneMatch(
                                    m ->
                                            m.getMetadata()
                                                    .containsKey(Msg.METADATA_CONFIRM_RESULTS)));
        }
    }

    private static Flux<ChatResponse> response(ContentBlock content) {
        return Flux.just(ChatResponse.builder().content(List.of(content)).build());
    }

    private HarnessAgent agent(Model model, Toolkit toolkit, ThreadJournalStore store) {
        return HarnessAgent.builder()
                .name("session-test")
                .agentId("session-test")
                .workspace(workspace)
                .model(model)
                .toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore())
                .threadJournalStore(store)
                .disableShellTool()
                .disableFilesystemTools()
                .disableWebTools()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableToolsConfig()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .build();
    }
}
