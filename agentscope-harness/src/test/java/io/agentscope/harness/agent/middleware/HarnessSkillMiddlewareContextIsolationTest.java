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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionHistoryMode;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.context.ContextManifest;
import io.agentscope.harness.agent.context.ContextPolicy;
import io.agentscope.harness.agent.context.ContextTokenEstimator;
import io.agentscope.harness.agent.context.HarnessContextBuilder;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import io.agentscope.harness.agent.skill.runtime.SkillRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class HarnessSkillMiddlewareContextIsolationTest {

    @TempDir Path workspace;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oversizedOffloadedBodySkipsRecoveryAndContinues(boolean stream) throws Exception {
        writeSkill("shared", "alpha", "Alpha description", "required-instruction ".repeat(10_000));
        AtomicInteger requests = new AtomicInteger();
        List<ContextManifest> manifests = new CopyOnWriteArrayList<>();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("offline");
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            return Flux.just(
                                    requests.incrementAndGet() == 1
                                            ? loadResponse("load", "alpha_workspace")
                                            : reply("done"));
                        });
        try (HarnessAgent agent =
                testBuilder(model)
                        .skillRepository(repository("shared"))
                        .contextPolicy(
                                new ContextPolicy(
                                        32000,
                                        100,
                                        1,
                                        ContextTokenEstimator.approximate(),
                                        manifests::add))
                        .build()) {
            RuntimeContext context =
                    RuntimeContext.builder().userId("alice").sessionId("oversized").build();
            UserMessage message = new UserMessage("start");
            if (stream) {
                agent.streamEvents(message, context)
                        .doOnNext(events::add)
                        .collectList()
                        .block(Duration.ofSeconds(10));
            } else {
                agent.call(message, context).block(Duration.ofSeconds(10));
            }
            assertEquals(2, requests.get());
            assertEquals(stream, events.stream().anyMatch(AgentEndEvent.class::isInstance));
            assertTrue(
                    manifests
                            .get(manifests.size() - 1)
                            .transforms()
                            .contains(
                                    "skill_recovery_skipped_for_budget:harness:loaded_skill:alpha_workspace:SKILL.md"));
        }
    }

    @ParameterizedTest
    @EnumSource(SessionHistoryMode.class)
    void realCallsKeepBodiesAcrossRequestsButResetOnNextCallAndResubscription(
            SessionHistoryMode mode) throws Exception {
        String body =
                "head\n"
                        + "padding ".repeat(800)
                        + "exact-alpha-command --typed 1\n"
                        + "tail ".repeat(800);
        writeSkill("shared", "alpha", "Alpha description", body);
        writeSkill("shared", "beta", "Beta description", "exact-beta-command --typed 2");
        List<List<Msg>> requests = new CopyOnWriteArrayList<>();
        AtomicInteger summaries = new AtomicInteger();
        Model summary =
                new Model() {
                    public String getModelName() {
                        return "summary";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        summaries.incrementAndGet();
                        return Flux.just(reply("Earlier work"));
                    }
                };
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("offline");
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            requests.add(List.copyOf(invocation.getArgument(0)));
                            int index = requests.size();
                            if (index == 1) {
                                List<ContentBlock> calls =
                                        new ArrayList<>(
                                                loadResponse("load-alpha", "alpha_workspace")
                                                        .getContent());
                                calls.addAll(
                                        loadResponse("load-beta", "beta_workspace").getContent());
                                return Flux.just(ChatResponse.builder().content(calls).build());
                            }
                            return Flux.just(
                                    index <= 2
                                            ? loadResponse("load-" + index, "alpha_workspace")
                                            : reply("reply-" + index));
                        });
        RuntimeContext source = RuntimeContext.builder().userId("alice").sessionId("reuse").build();
        try (HarnessAgent agent =
                testBuilder(model)
                        .sessionHistoryMode(mode)
                        .skillRepository(repository("shared"))
                        .compaction(
                                CompactionConfig.builder()
                                        .model(summary)
                                        .triggerMessages(2)
                                        .keepMessages(1)
                                        .keepTokens(0)
                                        .flushBeforeCompact(false)
                                        .build())
                        .build()) {
            var call = agent.call(new UserMessage("start"), source);
            call.block(Duration.ofSeconds(10));
            assertEquals(3, requests.size());
            assertFalse(projection(requests.get(0)).contains("exact-alpha-command"));
            assertTrue(summaries.get() >= 2, "both follow-up requests must exercise compaction");
            for (List<Msg> request : requests.subList(1, 3)) {
                assertTrue(requestText(request).contains(body));
                assertTrue(requestText(request).contains("exact-beta-command --typed 2"));
            }
            List<Msg> lastToolMessages =
                    requests.get(2).stream()
                            .filter(message -> message.getRole() == MsgRole.TOOL)
                            .toList();
            assertFalse(requestText(lastToolMessages).contains("exact-beta-command --typed 2"));
            assertTrue(projection(requests.get(2)).contains("exact-beta-command --typed 2"));
            call.block(Duration.ofSeconds(10));
            var events = agent.streamEvents(new UserMessage("continue"), source);
            events.collectList().block(Duration.ofSeconds(10));
            events.collectList().block(Duration.ofSeconds(10));
            assertEquals(6, requests.size());
            for (List<Msg> next : requests.subList(3, 6)) {
                assertFalse(projection(next).contains("harness:loaded_skill:"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("deprecation")
    void legacyStreamingOverflowKeepsItsOriginalErrorWithoutOuterRetry(boolean events)
            throws Exception {
        writeSkill("shared", "alpha", "Alpha description", "loaded before overflow");
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger summaries = new AtomicInteger();
        AtomicInteger initializations = new AtomicInteger();
        IllegalStateException overflow = new IllegalStateException("context_length_exceeded");
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("legacy-stream-overflow");
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            List<ToolSchema> tools = invocation.getArgument(1);
                            if (tools == null || tools.isEmpty()) {
                                summaries.incrementAndGet();
                                return Flux.just(reply("summary"));
                            }
                            return requests.incrementAndGet() == 1
                                    ? Flux.just(loadResponse("load", "alpha_workspace"))
                                    : Flux.error(overflow);
                        });
        try (HarnessAgent agent =
                testBuilder(model)
                        .skillRepository(repository("shared"))
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Mono<String> onSystemPrompt(
                                            Agent owner, RuntimeContext rc, String prompt) {
                                        initializations.incrementAndGet();
                                        return Mono.just(prompt);
                                    }
                                })
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(1000)
                                        .keepMessages(1000)
                                        .flushBeforeCompact(false)
                                        .build())
                        .build()) {
            RuntimeContext source =
                    RuntimeContext.builder().userId("alice").sessionId("stream-overflow").build();
            List<Msg> input = List.of(new UserMessage("start"));
            Flux<?> stream =
                    events ? agent.streamEvents(input, source) : agent.stream(input, source);
            StepVerifier.create(stream)
                    .thenConsumeWhile(ignored -> true)
                    .expectErrorMatches(error -> error == overflow)
                    .verify(Duration.ofSeconds(15));
            assertEquals(2, requests.get());
            assertEquals(0, summaries.get());
            assertEquals(1, initializations.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realExecutionSendsOneBodyAndPreservesFullHistoricalToolOutput(boolean stream)
            throws Exception {
        String marker = "SINGLE_BODY_MIDDLE_COMMAND --typed 3";
        writeSkill("shared", "alpha", "Alpha description", marker);
        List<List<Msg>> requests = new CopyOnWriteArrayList<>();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("offline");
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            requests.add(List.copyOf(invocation.getArgument(0)));
                            return Flux.just(
                                    requests.size() == 1
                                            ? loadResponse("load", "alpha_workspace")
                                            : reply("done"));
                        });
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        try (HarnessAgent agent =
                testBuilder(model)
                        .stateStore(store)
                        .skillRepository(repository("shared"))
                        .build()) {
            RuntimeContext context =
                    RuntimeContext.builder().userId("alice").sessionId("dedup").build();
            if (stream)
                agent.streamEvents(new UserMessage("start"), context)
                        .collectList()
                        .block(Duration.ofSeconds(10));
            else agent.call(new UserMessage("start"), context).block(Duration.ofSeconds(10));
            assertEquals(2, requests.size());
            List<Msg> prepared = requests.get(1);
            ToolResultBlock receipt =
                    prepared.stream()
                            .filter(m -> m.getRole() == MsgRole.TOOL)
                            .flatMap(m -> m.getContent().stream())
                            .filter(ToolResultBlock.class::isInstance)
                            .map(ToolResultBlock.class::cast)
                            .findFirst()
                            .orElseThrow();
            assertEquals(ToolResultState.SUCCESS, receipt.getState());
            assertTrue(((TextBlock) receipt.getOutput().get(0)).getText().contains(marker));
            assertEquals(1, occurrences(JsonUtils.getJsonCodec().toJson(prepared), marker));
            AgentState saved =
                    store.get("alice", "dedup", "agent_state", AgentState.class).orElseThrow();
            ToolResultBlock original =
                    saved.getContext().stream()
                            .flatMap(m -> m.getContent().stream())
                            .filter(ToolResultBlock.class::isInstance)
                            .map(ToolResultBlock.class::cast)
                            .findFirst()
                            .orElseThrow();
            String full = ((TextBlock) original.getOutput().get(0)).getText();
            assertTrue(full.contains("Successfully loaded skill: alpha_workspace"));
            assertTrue(full.contains(marker));
            assertFalse(projection(prepared).contains(full));
            assertEquals(full, ((TextBlock) receipt.getOutput().get(0)).getText());
            Msg restored =
                    JsonUtils.getJsonCodec()
                            .fromJson(
                                    JsonUtils.getJsonCodec()
                                            .toJson(
                                                    prepared.stream()
                                                            .filter(
                                                                    m ->
                                                                            m.getRole()
                                                                                    == MsgRole.TOOL)
                                                            .findFirst()
                                                            .orElseThrow()),
                                    Msg.class);
            assertEquals(MsgRole.TOOL, restored.getRole());
            assertEquals(receipt.getId(), ((ToolResultBlock) restored.getContent().get(0)).getId());

            agent.call(new UserMessage("continue"), context).block(Duration.ofSeconds(10));
            List<Msg> next = requests.get(2);
            assertFalse(projection(next).contains("harness:loaded_skill:"));
            assertTrue(JsonUtils.getJsonCodec().toJson(next).contains(marker));
            assertFalse(
                    JsonUtils.getJsonCodec()
                            .toJson(next)
                            .contains("Skill instructions are available in this request"));
        }
    }

    @Test
    void providerOverflowRetryKeepsFullRecentSkillReceipt() throws Exception {
        String marker = "OVERFLOW_BODY_MIDDLE --typed 4";
        writeSkill("shared", "alpha", "Alpha description", marker);
        List<List<Msg>> requests = new CopyOnWriteArrayList<>();
        AtomicInteger summaries = new AtomicInteger();
        AtomicReference<String> fullReply = new AtomicReference<>();
        Hook captureReply =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostActingEvent acting) {
                            fullReply.set(
                                    ((TextBlock) acting.getToolResult().getOutput().get(0))
                                            .getText());
                        }
                        return Mono.just(event);
                    }
                };
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("overflow-offline");
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            requests.add(List.copyOf(invocation.getArgument(0)));
                            return switch (requests.size()) {
                                case 1 -> Flux.just(loadResponse("load", "alpha_workspace"));
                                case 2 ->
                                        Flux.error(
                                                new IllegalStateException(
                                                        "context_length_exceeded"));
                                default -> Flux.just(reply("done"));
                            };
                        });
        Model summary = mock(Model.class);
        when(summary.getModelName()).thenReturn("summary-offline");
        when(summary.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            summaries.incrementAndGet();
                            return Flux.just(reply("earlier progress"));
                        });
        try (HarnessAgent agent =
                testBuilder(model)
                        .sessionHistoryMode(SessionHistoryMode.EVENT_LOG)
                        .skillRepository(repository("shared"))
                        .hook(captureReply)
                        .compaction(
                                CompactionConfig.builder()
                                        .model(summary)
                                        .triggerMessages(1000)
                                        .keepMessages(1000)
                                        .keepTokens(100000)
                                        .flushBeforeCompact(false)
                                        .build())
                        .build()) {
            agent.streamEvents(
                            new UserMessage("old request"),
                            RuntimeContext.builder()
                                    .userId("alice")
                                    .sessionId("overflow-dedup")
                                    .build())
                    .collectList()
                    .block(Duration.ofSeconds(20));
            assertEquals(3, requests.size());
            assertEquals(1, summaries.get());
            assertNotNull(fullReply.get());
            for (List<Msg> request : requests.subList(1, 3)) {
                assertEquals(1, occurrences(JsonUtils.getJsonCodec().toJson(request), marker));
                assertFalse(projection(request).contains("harness:loaded_skill:"));
                List<ToolResultBlock> results =
                        request.stream()
                                .filter(m -> m.getRole() == MsgRole.TOOL)
                                .flatMap(m -> m.getContent().stream())
                                .filter(ToolResultBlock.class::isInstance)
                                .map(ToolResultBlock.class::cast)
                                .filter(
                                        result ->
                                                "load_skill_through_path".equals(result.getName()))
                                .toList();
                assertFalse(results.isEmpty());
                for (ToolResultBlock result : results) {
                    assertEquals(
                            fullReply.get(), ((TextBlock) result.getOutput().get(0)).getText());
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "true,true,success,LEGACY",
        "true,false,success,LEGACY",
        "false,true,success,LEGACY",
        "false,false,success,LEGACY",
        "true,true,retry-failure,LEGACY",
        "true,true,cancel,LEGACY",
        "true,true,success,EVENT_LOG",
        "true,false,success,EVENT_LOG"
    })
    @SuppressWarnings("deprecation")
    void overflowRecoveryKeepsBodiesWithinTheExecution(
            boolean explicitContext, boolean enoughBudget, String outcome, SessionHistoryMode mode)
            throws Exception {
        String marker = "LEGACY_RECOVERY_FULL_BODY";
        writeSkill("shared", "alpha", "Alpha description", marker);
        Path reference = workspace.resolve("tenants/shared/skills/alpha/references/progress.md");
        Files.createDirectories(reference.getParent());
        Files.writeString(reference, "progress");
        List<List<Msg>> requests = new CopyOnWriteArrayList<>();
        List<RuntimeContext> actualContexts = new CopyOnWriteArrayList<>();
        List<ContextManifest> manifests = new CopyOnWriteArrayList<>();
        AtomicInteger summaries = new AtomicInteger();
        AtomicBoolean room = new AtomicBoolean(enoughBudget);
        AtomicBoolean nextCall = new AtomicBoolean();
        CountDownLatch summaryStarted = new CountDownLatch(1);
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("legacy-overflow");
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            List<ToolSchema> tools = invocation.getArgument(1);
                            if (tools == null || tools.isEmpty()) {
                                summaries.incrementAndGet();
                                summaryStarted.countDown();
                                if ("cancel".equals(outcome)) return Flux.never();
                                return Flux.just(reply("earlier progress"));
                            }
                            List<Msg> messages = List.copyOf(invocation.getArgument(0));
                            requests.add(messages);
                            int round = requests.size();
                            if (nextCall.get()) return Flux.just(reply("next-call"));
                            if (round == 1)
                                return Flux.just(loadResponse("load", "alpha_workspace"));
                            if (round == 15)
                                return Flux.error(
                                        new IllegalStateException("context_length_exceeded"));
                            if (round == 16) {
                                assertEquals(
                                        1,
                                        SkillRuntime.loadedBodies(
                                                        actualContexts.get(
                                                                actualContexts.size() - 1))
                                                .size());
                                assertEquals(enoughBudget, projection(messages).contains(marker));
                                room.set(true);
                                if ("retry-failure".equals(outcome))
                                    return Flux.error(
                                            new IllegalStateException(
                                                    "context_length_exceeded on retry"));
                            }
                            return Flux.just(
                                    round < 17
                                            ? toolResponse(
                                                    "progress-" + round,
                                                    "load_skill_through_path",
                                                    Map.of(
                                                            "skillId",
                                                            "alpha_workspace",
                                                            "path",
                                                            "references/progress.md"))
                                            : reply("done"));
                        });
        ContextTokenEstimator approximate = ContextTokenEstimator.approximate();
        ContextTokenEstimator controlled =
                input -> {
                    if (!room.get() && projection(input.messages()).contains(marker))
                        return new ContextTokenEstimator.Estimate(
                                1_000_000, "controlled-recovery-budget", true);
                    return approximate.estimate(input);
                };
        InMemorySessionLogStore logStore = new InMemorySessionLogStore();
        try (HarnessAgent agent =
                testBuilder(model)
                        .sessionHistoryMode(mode)
                        .sessionLogStore(logStore)
                        .skillRepository(repository("shared"))
                        .maxIters(25)
                        .disableToolResultEviction()
                        .contextPolicy(new ContextPolicy(100000, 1, 1, controlled, manifests::add))
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Mono<String> onSystemPrompt(
                                            Agent owner, RuntimeContext rc, String prompt) {
                                        if (nextCall.get())
                                            rc.getAgentState().contextMutable().clear();
                                        actualContexts.add(rc);
                                        return Mono.just(prompt);
                                    }
                                })
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(1000)
                                        .keepMessages(1000)
                                        .keepTokens(0)
                                        .flushBeforeCompact(false)
                                        .build())
                        .build()) {
            RuntimeContext source =
                    RuntimeContext.builder().userId("alice").sessionId("legacy-skill").build();
            UserMessage message = new UserMessage("start");
            Mono<Msg> call =
                    explicitContext ? agent.call(message, source) : agent.call(List.of(message));
            if (!"success".equals(outcome)) {
                if ("cancel".equals(outcome)) {
                    StepVerifier.create(call)
                            .then(
                                    () -> {
                                        try {
                                            assertTrue(summaryStarted.await(10, TimeUnit.SECONDS));
                                        } catch (InterruptedException error) {
                                            Thread.currentThread().interrupt();
                                            throw new AssertionError(error);
                                        }
                                    })
                            .thenCancel()
                            .verify(Duration.ofSeconds(15));
                    assertEquals(
                            15, requests.size(), "cancelling compaction must not start the retry");
                } else {
                    IllegalStateException error =
                            assertThrows(
                                    IllegalStateException.class,
                                    () -> call.block(Duration.ofSeconds(30)));
                    assertEquals("context_length_exceeded on retry", error.getMessage());
                    assertEquals(
                            16, requests.size(), "the existing single retry limit stays unchanged");
                }
                nextCall.set(true);
                call.block(Duration.ofSeconds(15));
                assertFalse(projection(requests.get(requests.size() - 1)).contains(marker));
                return;
            }
            assertNotNull(call.block(Duration.ofSeconds(30)));
            assertEquals(17, requests.size());
            assertEquals(mode == SessionHistoryMode.LEGACY ? 2 : 1, actualContexts.size());
            assertTrue(summaries.get() > 0);
            List<Msg> retry = requests.get(15);
            assertFalse(
                    retry.stream()
                            .flatMap(msg -> msg.getContent().stream())
                            .filter(ToolResultBlock.class::isInstance)
                            .map(ToolResultBlock.class::cast)
                            .anyMatch(result -> "load".equals(result.getId())),
                    "the failed attempt's full TOOL was actually compacted away");
            assertTrue(
                    projection(requests.get(16)).contains(marker),
                    "a skipped body can fit on a later prepare");
            assertEquals(1, occurrences(JsonUtils.getJsonCodec().toJson(requests.get(16)), marker));
            assertFalse(
                    JsonUtils.getJsonCodec()
                            .toJson(
                                    actualContexts
                                            .get(actualContexts.size() - 1)
                                            .getAgentState()
                                            .getContext())
                            .contains(marker),
                    "recovery must remain outside canonical history");
            assertEquals(
                    enoughBudget ? 0 : 1,
                    manifests.stream()
                            .flatMap(m -> m.transforms().stream())
                            .filter(
                                    t ->
                                            t.equals(
                                                    "skill_recovery_skipped_for_budget:harness:loaded_skill:alpha_workspace:SKILL.md"))
                            .count());
            assertEquals(
                    mode == SessionHistoryMode.LEGACY,
                    logStore.list(source).isEmpty(),
                    "only EVENT_LOG activates native logging");
            call.block(Duration.ofSeconds(15));
            assertFalse(
                    projection(requests.get(17)).contains(marker),
                    "resubscription must not inherit recovery");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void postActingChangesArePreservedWithoutRewritingHistoricalResults(boolean editBody)
            throws Exception {
        String marker = "HOOK_BODY_MIDDLE --typed 5";
        writeSkill("shared", "alpha", "Alpha description", marker);
        List<List<Msg>> requests = new CopyOnWriteArrayList<>();
        AtomicReference<String> fullReply = new AtomicReference<>();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("hook-offline");
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            requests.add(List.copyOf(invocation.getArgument(0)));
                            return Flux.just(
                                    requests.size() == 1
                                            ? loadResponse("load", "alpha_workspace")
                                            : reply("done"));
                        });
        Hook hook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostActingEvent acting) {
                            Msg original = acting.getToolResultMsg();
                            ToolResultBlock result = acting.getToolResult();
                            List<ContentBlock> output = result.getOutput();
                            fullReply.set(((TextBlock) output.get(0)).getText());
                            if (editBody) {
                                output =
                                        List.of(
                                                TextBlock.builder()
                                                        .text(
                                                                ((TextBlock) output.get(0))
                                                                                .getText()
                                                                        + "\nHook edited body.")
                                                        .build());
                            }
                            ToolResultBlock rewritten =
                                    new ToolResultBlock(
                                            result.getId(),
                                            result.getName(),
                                            output,
                                            Map.of("hook", "result"),
                                            result.getState(),
                                            result.getExecutionDetails());
                            acting.setToolResult(rewritten);
                            acting.setToolResultMsg(
                                    original.withContent(List.of(rewritten))
                                            .withMetadata(Map.of("hook", "message")));
                        }
                        return Mono.just(event);
                    }
                };
        try (HarnessAgent agent =
                testBuilder(model).skillRepository(repository("shared")).hook(hook).build()) {
            agent.call(new UserMessage("start"), RuntimeContext.empty())
                    .block(Duration.ofSeconds(10));
            assertEquals(2, requests.size());
            Msg message =
                    requests.get(1).stream()
                            .filter(msg -> msg.getRole() == MsgRole.TOOL)
                            .findFirst()
                            .orElseThrow();
            ToolResultBlock result = (ToolResultBlock) message.getContent().get(0);
            String output = ((TextBlock) result.getOutput().get(0)).getText();
            assertEquals("message", message.getMetadata().get("hook"));
            assertEquals("result", result.getMetadata().get("hook"));
            assertTrue(output.contains(marker));
            assertEquals(editBody, output.contains("Hook edited body."));
            assertFalse(output.contains("harness:loaded_skill:alpha_workspace:SKILL.md"));
            assertNotNull(fullReply.get());
            // Active content rewrites retain the PR's existing recovery behavior (Q12).
            if (editBody)
                assertTrue(loadedSkillMaterial(requests.get(1)).contains(fullReply.get()));
            else assertFalse(projection(requests.get(1)).contains(fullReply.get()));
        }
    }

    private static String loadedSkillMaterial(List<Msg> messages) {
        return messages.stream()
                .filter(msg -> msg.getRole() == MsgRole.USER)
                .filter(msg -> Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC)))
                .filter(
                        msg ->
                                "runtime_materials"
                                        .equals(msg.getMetadata().get(Msg.METADATA_REMINDER_KIND)))
                .map(Msg::getTextContent)
                .filter(text -> text.contains("harness:loaded_skill:alpha_workspace:SKILL.md"))
                .findFirst()
                .orElseThrow();
    }

    private static int occurrences(String text, String marker) {
        return text.split(java.util.regex.Pattern.quote(marker), -1).length - 1;
    }

    @Test
    void concurrentRealSessionsOnlyProjectTheirOwnLoadedBody() throws Exception {
        writeSkill("shared", "alpha", "Alpha description", "private-alpha-body");
        writeSkill("shared", "beta", "Beta description", "private-beta-body");
        CyclicBarrier bothLoaded = new CyclicBarrier(2);
        Map<String, AtomicInteger> rounds = new ConcurrentHashMap<>();
        Map<String, String> projections = new ConcurrentHashMap<>();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("offline");
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            List<Msg> messages = invocation.getArgument(0);
                            String skill =
                                    messages.stream()
                                                    .anyMatch(
                                                            msg ->
                                                                    "start-alpha"
                                                                            .equals(
                                                                                    msg
                                                                                            .getTextContent()))
                                            ? "alpha"
                                            : "beta";
                            int round =
                                    rounds.computeIfAbsent(skill, key -> new AtomicInteger())
                                            .incrementAndGet();
                            if (round == 1)
                                return Flux.just(
                                        loadResponse("load-" + skill, skill + "_workspace"));
                            bothLoaded.await(10, TimeUnit.SECONDS);
                            projections.put(skill, JsonUtils.getJsonCodec().toJson(messages));
                            return Flux.just(reply("reply-" + skill));
                        });
        var pool = Executors.newFixedThreadPool(2);
        try (HarnessAgent agent =
                testBuilder(model).skillRepository(repository("shared")).build()) {
            var alpha =
                    pool.submit(
                            () ->
                                    agent.call(
                                                    new UserMessage("start-alpha"),
                                                    RuntimeContext.builder()
                                                            .userId("alice")
                                                            .sessionId("alpha")
                                                            .build())
                                            .block(Duration.ofSeconds(15)));
            var beta =
                    pool.submit(
                            () ->
                                    agent.call(
                                                    new UserMessage("start-beta"),
                                                    RuntimeContext.builder()
                                                            .userId("bob")
                                                            .sessionId("beta")
                                                            .build())
                                            .block(Duration.ofSeconds(15)));
            alpha.get(20, TimeUnit.SECONDS);
            beta.get(20, TimeUnit.SECONDS);
            assertTrue(projections.get("alpha").contains("private-alpha-body"));
            assertFalse(projections.get("alpha").contains("private-beta-body"));
            assertTrue(projections.get("beta").contains("private-beta-body"));
            assertFalse(projections.get("beta").contains("private-alpha-body"));
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualSpawnResetsInheritedBodiesEvenWithoutChildSkillMiddleware(boolean childHasSkills)
            throws Exception {
        writeSkill("shared", "alpha", "Alpha description", "parent-only-body");
        writeSkill("child", "beta", "Beta description", "child-only-body");
        List<List<Msg>> parentRequests = new CopyOnWriteArrayList<>();
        List<List<Msg>> childRequests = new CopyOnWriteArrayList<>();
        Model parentModel = mock(Model.class);
        Model childModel = mock(Model.class);
        when(parentModel.getModelName()).thenReturn("parent-offline");
        when(childModel.getModelName()).thenReturn("child-offline");
        when(parentModel.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            parentRequests.add(List.copyOf(invocation.getArgument(0)));
                            return Flux.just(
                                    switch (parentRequests.size()) {
                                        case 1 -> loadResponse("parent-load", "alpha_workspace");
                                        case 2 ->
                                                toolResponse(
                                                        "spawn",
                                                        "agent_spawn",
                                                        Map.of(
                                                                "agent_id",
                                                                "child",
                                                                "task",
                                                                "child-task",
                                                                "timeout_seconds",
                                                                30));
                                        default -> reply("parent-reply");
                                    });
                        });
        when(childModel.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            childRequests.add(List.copyOf(invocation.getArgument(0)));
                            return Flux.just(
                                    childHasSkills && childRequests.size() == 1
                                            ? loadResponse("child-load", "beta_workspace")
                                            : reply("child-reply"));
                        });
        HarnessAgent.Builder childBuilder = testBuilder(childModel).disableSubagents();
        if (childHasSkills) childBuilder.skillRepository(repository("child"));
        try (HarnessAgent child = childBuilder.build();
                HarnessAgent parent =
                        testBuilder(parentModel)
                                .skillRepository(repository("shared"))
                                .subagentFactory("child", ignored -> child)
                                .build()) {
            assertEquals(
                    childHasSkills,
                    child.getDelegate().getMiddlewares().stream()
                            .anyMatch(HarnessSkillMiddleware.class::isInstance));
            parent.streamEvents(
                            new UserMessage("start-parent"),
                            RuntimeContext.builder().userId("alice").sessionId("parent").build())
                    .collectList()
                    .block(Duration.ofSeconds(20));
            assertEquals(3, parentRequests.size());
            assertEquals(childHasSkills ? 2 : 1, childRequests.size());
            assertFalse(projection(childRequests.get(0)).contains("harness:loaded_skill:"));
            for (List<Msg> request : childRequests) {
                assertFalse(requestText(request).contains("parent-only-body"));
            }
            if (childHasSkills)
                assertTrue(requestText(childRequests.get(1)).contains("child-only-body"));
            assertTrue(requestText(parentRequests.get(2)).contains("parent-only-body"));
            assertFalse(projection(parentRequests.get(2)).contains("child-only-body"));
        }
    }

    private HarnessAgent.Builder testBuilder(Model model) {
        return HarnessAgent.builder()
                .name("offline-agent")
                .model(model)
                .toolkit(
                        new Toolkit(
                                ToolkitConfig.builder()
                                        .defaultContext(
                                                ToolExecutionContext.builder()
                                                        .register("default", "value")
                                                        .build())
                                        .build()))
                .workspace(workspace)
                .stateStore(new InMemoryAgentStateStore())
                .sessionHistoryMode(SessionHistoryMode.LEGACY)
                .disableDefaultWorkspaceSkills()
                .disableDynamicSubagents()
                .disableMemoryHooks()
                .disableMemoryTools()
                .disableWebTools()
                .disableCompaction();
    }

    private WorkspaceSkillRepository repository(String tenant) {
        return new WorkspaceSkillRepository(
                new LocalFilesystem(workspace.resolve("tenants").resolve(tenant)),
                "skills",
                "workspace",
                false);
    }

    private static String requestText(List<Msg> messages) {
        return messages.stream()
                .flatMap(msg -> msg.getContent().stream())
                .flatMap(
                        block ->
                                block instanceof ToolResultBlock result
                                        ? result.getOutput().stream()
                                        : java.util.stream.Stream.of(block))
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String projection(List<Msg> messages) {
        return messages.stream()
                .filter(msg -> Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC)))
                .map(Msg::getTextContent)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static ChatResponse loadResponse(String id, String skillId) {
        return toolResponse(
                id, "load_skill_through_path", Map.of("skillId", skillId, "path", "SKILL.md"));
    }

    private static ChatResponse toolResponse(
            String id, String toolName, Map<String, Object> input) {
        return new ChatResponse(
                id,
                List.of(
                        ToolUseBlock.builder()
                                .id(id)
                                .name(toolName)
                                .input(input)
                                .content(
                                        io.agentscope.core.util.JsonUtils.getJsonCodec()
                                                .toJson(input))
                                .build()),
                null,
                Map.of(),
                "tool_use");
    }

    private static ChatResponse reply(String id) {
        return new ChatResponse(
                id, List.of(TextBlock.builder().text("done").build()), null, Map.of(), "stop");
    }

    @Test
    void usesCallContextWhenSharedActiveContextPointsToAnotherSession() throws IOException {
        RuntimeContext alice =
                RuntimeContext.builder().sessionId("alice-session").put("tenant", "alice").build();
        NamespaceFactory namespaces =
                ctx -> List.of("tenants", ctx != null ? (String) ctx.get("tenant") : "anonymous");
        LocalFilesystem filesystem = new LocalFilesystem(workspace, false, 64, namespaces);
        writeSkill("alice", "alice-skill", "Alice private description", "Alice private body");
        writeSkill("bob", "bob-skill", "Bob private description", "Bob private body");

        // The repository no longer carries a shared "current" context: per-call context flows
        // through mergeRepositories, so alice's catalog never resolves bob's namespace.
        WorkspaceSkillRepository repository =
                new WorkspaceSkillRepository(filesystem, "skills", "workspace", false);
        HarnessSkillMiddleware middleware =
                new HarnessSkillMiddleware(List.of(repository), new Toolkit());

        String prompt = middleware.onSystemPrompt(null, alice, "").block();

        // The system prompt stays untouched: the skills block rides a tail runtime material so
        // the head keeps byte-stable across catalog changes.
        assertEquals("", prompt);
        WorkspaceContextMaterials materials = alice.get(WorkspaceContextMaterials.class);
        assertNotNull(materials);
        ContextItem skills =
                materials.items().stream()
                        .filter(item -> "harness:skills".equals(item.sourceId()))
                        .findFirst()
                        .orElseThrow();
        String skillsBlock = skills.content();
        assertTrue(skillsBlock.contains("<available_skills>"));
        assertTrue(skillsBlock.contains("Alice private description"));
        assertFalse(skillsBlock.contains("Bob private description"));
        assertEquals(ContextItem.Placement.RUNTIME, skills.placement());
        assertTrue(
                middleware.runtime().currentCatalog(alice).ids().stream()
                        .anyMatch(id -> id.startsWith("alice-skill_")));
        assertFalse(
                middleware.runtime().currentCatalog(alice).ids().stream()
                        .anyMatch(id -> id.startsWith("bob-skill_")));
    }

    @Test
    void skillsRemainVisibleInTheFirstRequestAfterCompaction() throws IOException {
        AgentState state = AgentState.builder().build();
        List<Msg> history =
                List.of(
                        Msg.builder().role(MsgRole.USER).textContent("x".repeat(20_000)).build(),
                        Msg.builder().role(MsgRole.ASSISTANT).textContent("investigating").build(),
                        Msg.builder().role(MsgRole.USER).textContent("continue").build());
        state.contextMutable().addAll(history);
        RuntimeContext alice =
                RuntimeContext.builder()
                        .sessionId("alice-session")
                        .agentState(state)
                        .put("tenant", "alice")
                        .build();
        NamespaceFactory namespaces = ctx -> List.of("tenants", (String) ctx.get("tenant"));
        LocalFilesystem filesystem = new LocalFilesystem(workspace, false, 64, namespaces);
        writeSkill("alice", "alice-skill", "Alice private description", "Alice private body");
        var middleware =
                new HarnessSkillMiddleware(
                        List.of(
                                new WorkspaceSkillRepository(
                                        filesystem, "skills", "workspace", false)),
                        new Toolkit());
        new ContextConventionsMiddleware().onSystemPrompt(null, alice, "").block();
        middleware.onSystemPrompt(null, alice, "").block();
        String loadedReply =
                middleware
                        .runtime()
                        .loadTool()
                        .callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(alice)
                                        .input(
                                                Map.of(
                                                        "skillId",
                                                        "alice-skill_workspace",
                                                        "path",
                                                        "SKILL.md"))
                                        .build())
                        .block()
                        .getOutput()
                        .stream()
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .collect(java.util.stream.Collectors.joining());

        Model summary =
                new Model() {
                    public String getModelName() {
                        return "summary";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("Continue the audit.")
                                                                .build()))
                                        .build());
                    }
                };
        var config =
                CompactionConfig.builder()
                        .triggerMessages(2)
                        .keepMessages(1)
                        .keepTokens(0)
                        .flushBeforeCompact(false)
                        .build();
        List<ContextManifest> manifests = new ArrayList<>();
        var policy =
                new ContextPolicy(2000, 20, 1, ContextTokenEstimator.approximate(), manifests::add);
        Model main = mock(Model.class);
        when(main.getModelName()).thenReturn("main");
        when(main.getContextWindowSize()).thenReturn(4000);
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("audit");

        ModelCallInput prepared =
                new HarnessContextBuilder(
                                policy, new CompactionMiddleware(null, summary, config), null)
                        .prepare(
                                agent,
                                alice,
                                new ModelCallInput(history, List.of(), null, main),
                                "first",
                                ModelRequestPreparer.Purpose.REASONING)
                        .block();

        assertTrue(manifests.get(0).transforms().contains("history_compaction"));
        assertTrue(
                prepared.messages().stream()
                        .anyMatch(msg -> msg.getTextContent().contains(loadedReply)),
                "the first request after compaction must retain the complete loaded body");
        assertTrue(
                prepared.messages().stream()
                        .anyMatch(
                                msg -> msg.getTextContent().contains("Alice private description")),
                "the first request after compaction must still advertise the available skill");
    }

    private void writeSkill(String tenant, String name, String description, String body)
            throws IOException {
        Path directory =
                workspace.resolve("tenants").resolve(tenant).resolve("skills").resolve(name);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n# " + body + "\n");
    }
}
