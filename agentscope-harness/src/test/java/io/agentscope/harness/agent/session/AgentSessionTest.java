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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class AgentSessionTest {
    @TempDir Path workspace;
    private static final Duration WAIT = Duration.ofSeconds(15);

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

    private static Flux<ChatResponse> response(ContentBlock content) {
        return Flux.just(ChatResponse.builder().content(List.of(content)).build());
    }

    private AgentSession session(HarnessAgent agent) {
        return agent.session(RuntimeContext.builder().userId("u").sessionId("s").build());
    }

    private HarnessAgent agent(Model model, Toolkit toolkit, InMemorySessionLogStore store) {
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
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .build();
    }
}
