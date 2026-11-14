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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RunOutcome;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.journal.ThreadHistory;
import io.agentscope.core.journal.ThreadInteractions;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
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

class HarnessInvocationRecoveryTest {
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

    private HarnessAgent agent(Model model, Toolkit toolkit, InMemoryThreadJournalStore store) {
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
