/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Verifies that memory maintenance started on the agent stream's completion signal inherits the
 * subscriber's Reactor context. Context-scoped instrumentation (token metering, tracing) attached
 * to the model relies on it, and a fresh background subscription would otherwise start empty.
 */
class MemoryBackgroundContextPropagationTest {

    private static final String CONTEXT_KEY = "test-session";

    @TempDir Path workspace;

    @Test
    void flushModelSeesTheCompletingTurnContext() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        Model capturingModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.deferContextual(
                                context -> {
                                    seen.set(context.getOrDefault(CONTEXT_KEY, "missing"));
                                    return Flux.just(response("NO_REPLY"));
                                });
                    }

                    @Override
                    public String getModelName() {
                        return "capturing-model";
                    }
                };
        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent("remember this").build();
        RuntimeContext rc =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("session-1")
                        .agentState(AgentState.builder().addMessage(userMsg).build())
                        .build();
        MemoryFlushMiddleware middleware = new MemoryFlushMiddleware(null, capturingModel);

        middleware
                .onAgent(
                        null,
                        rc,
                        new AgentInput(List.of(userMsg)),
                        input -> Flux.just(new AgentEndEvent("reply-1")))
                .contextWrite(context -> context.put(CONTEXT_KEY, "session-1"))
                .blockLast(Duration.ofSeconds(2));

        assertTrue(
                MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                "background flush must finish");
        assertEquals("session-1", seen.get(), "flush model must see the turn's Reactor context");
    }

    @Test
    void consolidationModelSeesTheCompletingTurnContext() throws Exception {
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        Files.createDirectories(workspace.resolve("memory"));
        Files.writeString(workspace.resolve("memory/" + LocalDate.now() + ".md"), "- fresh fact");
        WorkspaceManager workspaceManager =
                new WorkspaceManager(workspace, new LocalFilesystem(workspace));
        Model capturingModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.deferContextual(
                                context -> {
                                    seen.set(context.getOrDefault(CONTEXT_KEY, null));
                                    return Flux.just(response("# Curated\n- fresh fact"));
                                });
                    }

                    @Override
                    public String getModelName() {
                        return "capturing-consolidation-model";
                    }
                };
        MemoryConsolidator consolidator = new MemoryConsolidator(workspaceManager, capturingModel);
        MemoryMaintenanceMiddleware middleware =
                new MemoryMaintenanceMiddleware(
                        workspaceManager,
                        consolidator,
                        90,
                        180,
                        Duration.ofMinutes(30),
                        IsolationScope.USER,
                        (name, minGap) -> true);
        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        RuntimeContext rc =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("session-1")
                        .agentState(AgentState.builder().addMessage(userMsg).build())
                        .build();

        middleware
                .onAgent(
                        null,
                        rc,
                        new AgentInput(List.of(userMsg)),
                        input -> Flux.just(new AgentEndEvent("reply-1")))
                .contextWrite(context -> context.put(CONTEXT_KEY, rc))
                .blockLast(Duration.ofSeconds(2));

        assertTrue(
                MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                "background maintenance must finish");
        assertSame(rc, seen.get(), "actual consolidation model must see the turn identity");
        assertTrue(workspaceManager.readMemoryMd(rc).contains("fresh fact"));
        workspaceManager.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sessionPruningStillRunsAfterConsolidationFailure(boolean synchronous) throws Exception {
        Files.createDirectories(workspace.resolve("agents"));
        Path oldSession = workspace.resolve("agents/old.log.jsonl");
        Files.writeString(oldSession, "old session");
        Files.setLastModifiedTime(
                oldSession, FileTime.from(Instant.now().minus(Duration.ofDays(200))));
        try (WorkspaceManager wm =
                new WorkspaceManager(workspace, new LocalFilesystem(workspace))) {
            MemoryConsolidator consolidator =
                    new MemoryConsolidator(wm, null) {
                        @Override
                        public Mono<Void> consolidate(RuntimeContext rc) {
                            if (synchronous) {
                                throw new IllegalStateException(
                                        "failed before returning a publisher");
                            }
                            return Mono.error(
                                    new IllegalStateException("failed during consolidation"));
                        }
                    };
            MemoryMaintenanceMiddleware middleware =
                    new MemoryMaintenanceMiddleware(
                            wm,
                            consolidator,
                            90,
                            180,
                            Duration.ZERO,
                            IsolationScope.USER,
                            (name, minGap) -> true);
            middleware
                    .onAgent(
                            null,
                            RuntimeContext.empty(),
                            new AgentInput(List.of()),
                            input -> Flux.just(new AgentEndEvent("reply-1")))
                    .blockLast(Duration.ofSeconds(2));
            assertTrue(MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS));
            assertFalse(
                    Files.exists(oldSession), "legacy sessionRetentionDays pruning must still run");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void asynchronousConsolidationCompletionPrunesOffCallbackThread(boolean fail) throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        RuntimeContext caller =
                RuntimeContext.builder().userId("alice").sessionId("shared").build();
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        Files.createDirectories(workspace.resolve("agents"));
        Path oldSession = workspace.resolve("agents/old.log.jsonl");
        Files.writeString(oldSession, "old session");
        Files.setLastModifiedTime(
                oldSession, FileTime.from(Instant.now().minus(Duration.ofDays(200))));
        LocalFilesystem fs =
                new LocalFilesystem(workspace) {
                    @Override
                    public GlobResult glob(RuntimeContext rc, String pattern, String path) {
                        if (path.equals("agents")) {
                            assertSame(caller, rc);
                            assertFalse(Schedulers.isInNonBlockingThread());
                            assertTrue(
                                    Thread.currentThread().getName().startsWith("boundedElastic-"));
                            order.add("prune");
                        }
                        return super.glob(rc, pattern, path);
                    }
                };
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs)) {
            MemoryConsolidator consolidator =
                    new MemoryConsolidator(wm, null) {
                        @Override
                        public Mono<Void> consolidate(RuntimeContext rc) {
                            return Mono.deferContextual(
                                            context -> {
                                                seen.set(context.get(CONTEXT_KEY));
                                                assertTrue(Schedulers.isInNonBlockingThread());
                                                order.add(fail ? "error" : "success");
                                                return fail
                                                        ? Mono.<Void>error(
                                                                new IllegalStateException(
                                                                        "async failure"))
                                                        : Mono.<Void>empty();
                                            })
                                    .subscribeOn(Schedulers.parallel());
                        }
                    };
            MemoryMaintenanceMiddleware middleware =
                    new MemoryMaintenanceMiddleware(
                            wm,
                            consolidator,
                            90,
                            180,
                            Duration.ZERO,
                            IsolationScope.USER,
                            (name, gap) -> true);
            middleware
                    .onAgent(
                            null,
                            caller,
                            new AgentInput(List.of()),
                            input -> Flux.just(new AgentEndEvent("reply-1")))
                    .contextWrite(context -> context.put(CONTEXT_KEY, caller))
                    .blockLast(Duration.ofSeconds(2));
            assertTrue(MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS));
            assertSame(caller, seen.get());
            assertEquals(List.of(fail ? "error" : "success", "prune"), order);
            assertFalse(Files.exists(oldSession));
        }
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(
                "stub-id", List.of(TextBlock.builder().text(text).build()), null, Map.of(), "stop");
    }
}
