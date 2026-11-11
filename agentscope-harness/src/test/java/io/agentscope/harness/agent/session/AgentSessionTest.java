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
package io.agentscope.harness.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.MessageUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class AgentSessionTest {
    @TempDir Path workspace;
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Test
    void submittedTaskKeepsExpandedFileAttachments() throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "ATTACHMENT_CONTENT");
        var requests = new CopyOnWriteArrayList<List<Msg>>();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "attachment-test";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        requests.add(List.copyOf(messages));
                        return response(TextBlock.builder().text("done").build());
                    }
                };
        try (var agent =
                agentBuilder(model, new Toolkit(), new InMemorySessionLogStore())
                        .filesystem(new LocalFilesystemSpec().project(workspace))
                        .build()) {
            var session = session(agent);
            var result = session.await(session.submit("Read @./notes.txt")).block(WAIT);
            assertEquals("completed", result.status(), session.executionError());
            assertTrue(
                    requests.get(0).stream()
                            .filter(message -> message.getRole() == MsgRole.USER)
                            .anyMatch(
                                    message ->
                                            message.getTextContent()
                                                    .contains("ATTACHMENT_CONTENT")),
                    "the submitted task must retain the built-in @path expansion");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialExternalAnswerLeavesTheOriginalSiblingRequestPending(boolean legacyCheckpoint) {
        var dispatches = new CopyOnWriteArrayList<String>();
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("external_quote")
                                .description("Submit a quote request")
                                .inputSchema(Map.of("type", "object"))) {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        var call = param.getToolUseBlock();
                        dispatches.add(call.getId());
                        return Mono.just(
                                call.getId().equals("quote-c")
                                        ? ToolResultBlock.success("Cached quote")
                                        : ToolResultBlock.suspended(call));
                    }
                });
        var calls = new AtomicInteger();
        Model model =
                new Model() {
                    @Override
                    public String getModelName() {
                        return "external-quote-test";
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (calls.getAndIncrement() != 0)
                            return response(TextBlock.builder().text("done").build());
                        var batch =
                                List.<ContentBlock>of(
                                        ToolUseBlock.builder()
                                                .id("quote-a")
                                                .name("external_quote")
                                                .build(),
                                        ToolUseBlock.builder()
                                                .id("quote-b")
                                                .name("external_quote")
                                                .build(),
                                        ToolUseBlock.builder()
                                                .id("quote-c")
                                                .name("external_quote")
                                                .build());
                        return Flux.just(ChatResponse.builder().content(batch).build());
                    }
                };
        var store = new InMemorySessionLogStore();
        String bEventId;
        String turnId;
        var deferCachedQuote =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onActing(
                            Agent agent,
                            RuntimeContext context,
                            ActingInput input,
                            Function<ActingInput, Flux<AgentEvent>> next) {
                        return next.apply(
                                new ActingInput(
                                        input.toolCalls().stream()
                                                .filter(call -> !call.getId().equals("quote-c"))
                                                .toList()));
                    }
                };
        try (var agent =
                agentBuilder(model, toolkit, store)
                        .disableAtPathExpansion()
                        .middleware(deferCachedQuote)
                        .build()) {
            var session = session(agent);
            var first = session.await(session.submit("compare quotes")).block(WAIT);
            assertEquals("suspended", first.status(), session.executionError());
            assertEquals(Set.of("quote-a", "quote-b"), session.pending().keySet());
            bEventId = session.pending().get("quote-b").eventId();
            turnId = first.turnId();
        }
        if (legacyCheckpoint) {
            var context = RuntimeContext.builder().userId("u").sessionId("s").build();
            var recorder =
                    new SessionRecorder(
                            store.open(new SessionKey("u", "session-test", "s"), context), turnId);
            try {
                var state = recorder.initial().restore();
                var allowedCalls = new HashMap<String, ToolUseBlock>();
                // An approval before deferral can leave even the undispatched call ALLOWED.
                for (var call :
                        MessageUtils.extractRecentToolCalls(state.getContext(), "session-test"))
                    allowedCalls.put(call.getId(), call.withState(ToolCallState.ALLOWED));
                assertTrue(MessageUtils.replaceToolUseBlocks(state.contextMutable(), allowedCalls));
                recorder.checkpointNow(state, "legacy_external_wait");
            } finally {
                recorder.close();
            }
        }
        try (var agent = agent(model, toolkit, store)) {
            var session = session(agent);
            var partial = session.await(session.respond("quote-a", "A")).block(WAIT);
            assertEquals("suspended", partial.status(), session.executionError());
            assertEquals(turnId, partial.turnId());
            assertEquals(1, calls.get());
            assertEquals(List.of("quote-a", "quote-b"), dispatches.stream().sorted().toList());
            assertEquals(Set.of("quote-b"), session.pending().keySet());
            assertEquals(bEventId, session.pending().get("quote-b").eventId());

            var completed = session.await(session.respond("quote-b", "B")).block(WAIT);
            assertEquals("completed", completed.status(), session.executionError());
            assertEquals(turnId, completed.turnId());
            assertTrue(session.pending().isEmpty());
            assertEquals(
                    List.of("quote-a", "quote-b", "quote-c"),
                    dispatches.stream().sorted().toList());
            assertEquals(2, calls.get());
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
        var store = new InMemorySessionLogStore();
        String turn;
        try (var agent = agent(model, toolkit, store)) {
            var session = session(agent);
            var task = session.submit("perform action");
            turn = task.turnId();
            assertEquals("suspended", session.await(task).block(WAIT).status());
            assertEquals(0, executions.get());
        }
        try (var agent = agent(model, toolkit, store)) {
            var session = session(agent);
            var answer = session.respond(session.pending().keySet().iterator().next(), true);
            assertEquals(turn, answer.turnId());
            assertTrue(reasoning.await(10, TimeUnit.SECONDS));
            assertTrue(session.interrupt());
            var interrupted = session.await(answer).block(WAIT);
            assertEquals("interrupted", interrupted.status());
            assertEquals(1, executions.get());
            assertTrue(session.pending().isEmpty());
            var continuation = session.resume(turn);
            var completed = session.await(continuation).block(WAIT);
            assertEquals("completed", completed.status(), session.executionError());
            assertEquals(interrupted.turnId(), completed.turnId());
            assertNotEquals(interrupted.runId(), completed.runId());
            assertEquals(1, executions.get());
            assertTrue(
                    session.transcript().messages().stream()
                            .noneMatch(
                                    m ->
                                            m.getMetadata()
                                                    .containsKey(Msg.METADATA_CONFIRM_RESULTS)));
        }
    }

    @Test
    void steeringAcceptedDuringFinalModelCallGetsAnotherStepInTheSameRun() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = Sinks.<Void>one();
        var calls = new AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "steering-test";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (calls.getAndIncrement() == 0) {
                            entered.countDown();
                            return finish.asMono()
                                    .thenMany(
                                            response(
                                                    TextBlock.builder()
                                                            .text("initial answer")
                                                            .build()));
                        }
                        assertTrue(
                                messages.stream()
                                        .anyMatch(
                                                m ->
                                                        m.getTextContent()
                                                                .contains("new requirement")),
                                () ->
                                        messages.stream()
                                                .map(Msg::getTextContent)
                                                .toList()
                                                .toString());
                        return response(TextBlock.builder().text("updated answer").build());
                    }
                };
        try (var agent = agent(model, new Toolkit(), new InMemorySessionLogStore())) {
            var session = session(agent);
            var task = session.submit("begin");
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var running = session.task(task.commandId());
            session.steer(task, "new requirement");
            finish.tryEmitEmpty();
            var done = session.await(task).block(WAIT);
            assertEquals("completed", done.status(), session.executionError());
            assertEquals(running.runId(), done.runId());
            assertEquals(2, calls.get());
            assertEquals(1, session.tasks().size());
        }
    }

    @Test
    void callerModelIdDoesNotStickToDurableSessionTurns() {
        var calls = new AtomicInteger();
        Model sessionModel =
                new Model() {
                    public String getModelName() {
                        return "session-default";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.incrementAndGet();
                        return response(TextBlock.builder().text("done").build());
                    }
                };
        Model sticky =
                new Model() {
                    public String getModelName() {
                        return "sticky-per-call";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.error(
                                new AssertionError(
                                        "session turns must not inherit the caller's pinned"
                                                + " model"));
                    }
                };
        try (var agent = agent(sessionModel, new Toolkit(), new InMemorySessionLogStore())) {
            var callerCtx = RuntimeContext.builder().userId("u").sessionId("s").build();
            callerCtx.put(Model.class, sticky);
            var session = agent.session(callerCtx);
            var done = session.await(session.submit("begin")).block(WAIT);
            assertEquals("completed", done.status(), session.executionError());
            assertEquals(1, calls.get());
        }
    }

    private static Flux<ChatResponse> response(ContentBlock content) {
        return Flux.just(ChatResponse.builder().content(List.of(content)).build());
    }

    private AgentSession session(HarnessAgent agent) {
        return agent.session(RuntimeContext.builder().userId("u").sessionId("s").build());
    }

    private HarnessAgent agent(Model model, Toolkit toolkit, InMemorySessionLogStore store) {
        return agentBuilder(model, toolkit, store).disableAtPathExpansion().build();
    }

    private HarnessAgent.Builder agentBuilder(
            Model model, Toolkit toolkit, InMemorySessionLogStore store) {
        return HarnessAgent.builder()
                .name("session-test")
                .agentId("session-test")
                .workspace(workspace)
                .model(model)
                .toolkit(toolkit)
                .sessionLogStore(store)
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
                .disableWorkspaceContext();
    }
}
