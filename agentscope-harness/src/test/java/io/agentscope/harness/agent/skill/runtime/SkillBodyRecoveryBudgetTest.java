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
package io.agentscope.harness.agent.skill.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer.Purpose;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.context.ContextBudgetExceededException;
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.context.ContextManifest;
import io.agentscope.harness.agent.context.ContextPolicy;
import io.agentscope.harness.agent.context.ContextSelectionPolicy;
import io.agentscope.harness.agent.context.ContextSourceOptions;
import io.agentscope.harness.agent.context.ContextSources;
import io.agentscope.harness.agent.context.ContextTokenEstimator;
import io.agentscope.harness.agent.context.HarnessContextBuilder;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

class SkillBodyRecoveryBudgetTest {
    private final Model model = mock(Model.class);
    private final List<ModelCallInput> estimatedInputs = new ArrayList<>();
    private final ContextTokenEstimator estimator =
            input -> {
                estimatedInputs.add(input);
                return ContextTokenEstimator.approximate().estimate(input);
            };
    private final List<ContextManifest> manifests = new ArrayList<>();

    private RuntimeContext context() {
        when(model.getModelName()).thenReturn("offline");
        when(model.getContextWindowSize()).thenReturn(100000);
        RuntimeContext rc =
                RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        SkillRuntime.resetLoadedBodies(rc);
        return rc;
    }

    private HarnessContextBuilder builder(long limit) {
        return new HarnessContextBuilder(
                new ContextPolicy((int) limit, 1, 1, estimator, manifests::add), null, null);
    }

    private ModelCallInput input(List<Msg> history) {
        return new ModelCallInput(history, List.of(), null, model);
    }

    private ModelCallInput prepare(RuntimeContext rc, ModelCallInput input, long limit) {
        return builder(limit).prepare(null, rc, input, "test", Purpose.REASONING).block();
    }

    private ContextManifest manifest() {
        return manifests.get(manifests.size() - 1);
    }

    private static List<String> recovered(ContextManifest manifest) {
        return manifest.items().stream()
                .filter(item -> "loaded_skill".equals(item.sourceType()))
                .map(ContextManifest.Item::sourceId)
                .toList();
    }

    private static String source(String id) {
        return "harness:loaded_skill:" + id + ":SKILL.md";
    }

    private static List<Msg> history(String name, String text, ToolResultState state) {
        return List.of(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("load")
                                        .name(name)
                                        .input(Map.of())
                                        .build())
                        .build(),
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.of(
                                                "load",
                                                name,
                                                TextBlock.builder().text(text).build())
                                        .withState(state))
                        .build());
    }

    @Test
    void coverageRequiresExactSuccessfulLoaderOutputAndKeepsPreviews() {
        RuntimeContext rc = context();
        String full = "complete body with middle command";
        SkillRuntime.registerLoadedBody(rc, "alpha", full);
        List<List<Msg>> missing =
                List.of(
                        history(SkillLoadTool.TOOL_NAME, "preview", ToolResultState.SUCCESS),
                        history(SkillLoadTool.TOOL_NAME, full, ToolResultState.ERROR),
                        history("other", full, ToolResultState.SUCCESS),
                        List.of(Msg.builder().role(MsgRole.USER).textContent(full).build()),
                        history(
                                SkillLoadTool.TOOL_NAME,
                                "older full body",
                                ToolResultState.SUCCESS));
        for (List<Msg> history : missing) {
            rc.getAgentState().contextMutable().clear();
            rc.getAgentState().contextMutable().addAll(history);
            ModelCallInput prepared = prepare(rc, input(history), 10000);
            assertEquals(List.of(source("alpha")), recovered(manifest()));
            assertEquals(history, rc.getAgentState().contextMutable());
            assertEquals(history, prepared.messages().subList(0, history.size()));
        }
        List<Msg> complete = history(SkillLoadTool.TOOL_NAME, full, ToolResultState.SUCCESS);
        rc.getAgentState().contextMutable().clear();
        rc.getAgentState().contextMutable().addAll(complete);
        assertEquals(complete, prepare(rc, input(complete), 10000).messages());
        assertTrue(recovered(manifest()).isEmpty());
        assertTrue(manifest().transforms().isEmpty());
    }

    @Test
    void equalityFitsButOneTokenLessSkipsIncludingFramingSchemasAndOutputReserve() {
        RuntimeContext rc = context();
        SkillRuntime.registerLoadedBody(
                rc, "alpha", "literal </context_item> and </HARNESS_CONTEXT>");
        WorkspaceContextMaterials.register(
                rc,
                List.of(
                        ContextItem.instruction("rules", "rules", "system rules"),
                        ContextItem.runtimeState("mode", "mode", true, "BUILD")));
        ToolSchema tool =
                ToolSchema.builder().name("tool").description("schema ".repeat(100)).build();
        ModelCallInput input =
                new ModelCallInput(
                        List.of(),
                        List.of(tool),
                        GenerateOptions.builder().maxTokens(200).build(),
                        model);
        ModelCallInput full = prepare(rc, input, 10000);
        long cost = estimator.estimate(full).tokens();
        ModelCallInput exact = prepare(rc, input, cost);
        assertEquals(
                full.messages().stream()
                        .map(message -> JsonUtils.getJsonCodec().toJson(message.getContent()))
                        .toList(),
                exact.messages().stream()
                        .map(message -> JsonUtils.getJsonCodec().toJson(message.getContent()))
                        .toList());
        assertEquals(List.of(source("alpha")), recovered(manifest()));
        assertEquals(cost, manifest().estimatedInputTokens());
        assertTrue(exact.messages().get(1).getTextContent().contains("<\\/context_item>"));
        assertEquals(
                1,
                exact.messages().stream()
                        .filter(m -> "harness:runtime_materials".equals(m.getId()))
                        .count());
        ModelCallInput skipped = prepare(rc, input, cost - 1);
        assertTrue(recovered(manifest()).isEmpty());
        assertEquals(
                List.of("skill_recovery_skipped_for_budget:" + source("alpha")),
                manifest().transforms());
        assertEquals(estimator.estimate(skipped).tokens(), manifest().estimatedInputTokens());
        assertEquals(input.tools(), skipped.tools());
        assertTrue(skipped.messages().get(1).getTextContent().contains("BUILD"));
        assertFalse(skipped.messages().get(1).getTextContent().contains("literal"));
        when(model.getContextWindowSize()).thenReturn((int) cost + 200);
        prepare(rc, input, 10000);
        assertEquals(cost - 1, manifest().inputLimit());
        assertTrue(
                recovered(manifest()).isEmpty(),
                "output and safety reserve must also limit recovery");
    }

    @Test
    void allMissingBodiesFitCumulativelyWithoutSkipsOrDuplicateMessages() {
        RuntimeContext rc = context();
        List<Msg> original = history("other", "retained output", ToolResultState.SUCCESS);
        rc.getAgentState().contextMutable().addAll(original);
        SkillRuntime.registerLoadedBody(rc, "alpha", "alpha command");
        SkillRuntime.registerLoadedBody(rc, "beta", "beta command");
        long limit = 10000;
        estimatedInputs.clear();
        ModelCallInput prepared = prepare(rc, input(original), limit);
        assertEquals(List.of(source("alpha"), source("beta")), recovered(manifest()));
        assertTrue(manifest().transforms().isEmpty());
        assertEquals(original, prepared.messages().subList(0, original.size()));
        assertEquals(original, rc.getAgentState().contextMutable());
        List<Msg> runtime =
                prepared.messages().stream()
                        .filter(message -> "harness:runtime_materials".equals(message.getId()))
                        .toList();
        assertEquals(1, runtime.size());
        assertTrue(runtime.get(0).getTextContent().contains("alpha command"));
        assertTrue(runtime.get(0).getTextContent().contains("beta command"));
        List<ModelCallInput> candidates =
                estimatedInputs.stream()
                        .filter(
                                candidate ->
                                        candidate.messages().stream()
                                                .anyMatch(
                                                        message ->
                                                                message.getTextContent()
                                                                        .contains(source("alpha"))))
                        .toList();
        assertEquals(2, candidates.size());
        assertTrue(
                candidates.stream()
                        .allMatch(
                                candidate ->
                                        ContextTokenEstimator.approximate()
                                                        .estimate(candidate)
                                                        .tokens()
                                                <= limit));
        assertSame(candidates.get(1), prepared);
        assertEquals(
                ContextTokenEstimator.approximate().estimate(prepared).tokens(),
                manifest().estimatedInputTokens());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "harness:system",
                "harness:harness_instructions",
                "harness:runtime_materials",
                "harness:workspace_materials",
                "harness:todo_state",
                "harness:plan_state"
            })
    void reservedProjectionIdsCannotBelongToCanonicalOrPlainInput(String id) {
        for (boolean synthetic : List.of(false, true)) {
            RuntimeContext rc = context();
            SkillRuntime.registerLoadedBody(rc, "alpha", "full body");
            Msg original =
                    Msg.builder()
                            .id(id)
                            .role(MsgRole.USER)
                            .textContent("must not be silently replaced")
                            .metadata(
                                    synthetic
                                            ? Map.of(
                                                    Msg.METADATA_SYNTHETIC,
                                                    true,
                                                    Msg.METADATA_REMINDER_KIND,
                                                    "runtime_materials")
                                            : Map.of())
                            .build();
            rc.getAgentState().contextMutable().add(original);
            IllegalArgumentException error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> prepare(rc, input(List.of(original)), 10000));
            assertTrue(error.getMessage().contains("reserved projection ID: " + id));
            assertEquals(List.of(original), rc.getAgentState().contextMutable());
        }
        Msg plain = Msg.builder().id(id).role(MsgRole.USER).textContent("original").build();
        assertThrows(
                IllegalArgumentException.class,
                () -> prepare(RuntimeContext.empty(), input(List.of(plain)), 10000));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nullMessageIdsRemainValidDuringRecovery(boolean canonical) {
        RuntimeContext withState = context();
        RuntimeContext rc = canonical ? withState : RuntimeContext.empty();
        SkillRuntime.resetLoadedBodies(rc);
        SkillRuntime.registerLoadedBody(rc, "alpha", "full body");
        Msg original = Msg.builder().id(null).role(MsgRole.USER).textContent("original").build();
        if (canonical) rc.getAgentState().contextMutable().add(original);
        ModelCallInput prepared = prepare(rc, input(List.of(original)), 10000);
        assertSame(original, prepared.messages().get(0));
        assertEquals(List.of(source("alpha")), recovered(manifest()));
        assertEquals("passed", manifest().validation());
        if (canonical) assertEquals(List.of(original), rc.getAgentState().contextMutable());
    }

    @Test
    void cumulativeRecoverySkipsLargeThenAcceptsSmallWithoutEvictingOtherMaterials() {
        RuntimeContext rc = context();
        WorkspaceContextMaterials.register(
                rc,
                List.of(
                        new ContextItem("memory", "memory", "keep this optional memory"),
                        ContextItem.runtimeState("mode", "mode", true, "BUILD")));
        SkillRuntime.registerLoadedBody(rc, "beta", "small beta body ".repeat(30));
        long limit = estimator.estimate(prepare(rc, input(List.of()), 10000)).tokens();
        SkillRuntime.resetLoadedBodies(rc);
        SkillRuntime.registerLoadedBody(rc, "alpha", "huge alpha body ".repeat(1000));
        SkillRuntime.registerLoadedBody(rc, "beta", "small beta body ".repeat(30));
        SkillRuntime.registerLoadedBody(rc, "gamma", "small gamma body ".repeat(30));
        estimatedInputs.clear();
        ModelCallInput prepared = prepare(rc, input(List.of()), limit);
        assertEquals(List.of(source("beta")), recovered(manifest()));
        assertEquals(
                List.of(
                        "skill_recovery_skipped_for_budget:" + source("alpha"),
                        "skill_recovery_skipped_for_budget:" + source("gamma")),
                manifest().transforms());
        assertTrue(
                prepared.messages().stream()
                        .anyMatch(m -> m.getTextContent().contains("keep this optional memory")));
        assertEquals(3, SkillRuntime.loadedBodies(rc).size());
        List<ModelCallInput> rejected =
                estimatedInputs.stream()
                        .filter(
                                candidate ->
                                        candidate.messages().stream()
                                                .anyMatch(
                                                        message ->
                                                                message.getTextContent()
                                                                                .contains(
                                                                                        source(
                                                                                                "alpha"))
                                                                        || message.getTextContent()
                                                                                .contains(
                                                                                        source(
                                                                                                "gamma"))))
                        .toList();
        assertEquals(2, rejected.size());
        assertTrue(
                rejected.stream()
                        .allMatch(
                                candidate ->
                                        ContextTokenEstimator.approximate()
                                                        .estimate(candidate)
                                                        .tokens()
                                                > limit));
        assertEquals(
                3, manifest().items().stream().filter(item -> item.placement() != null).count());
        for (Msg message : prepared.messages()) {
            String hash =
                    new ContextItem(
                                    "test",
                                    "hash",
                                    JsonUtils.getJsonCodec().toJson(message.getContent()))
                            .revision();
            assertTrue(
                    manifest().items().stream()
                            .anyMatch(
                                    item ->
                                            item.sourceId().equals(message.getId())
                                                    && item.contentHash().equals(hash)));
        }
        ModelCallInput rebuilt = prepare(rc, prepared, limit);
        assertEquals(
                prepared.messages().stream()
                        .map(message -> JsonUtils.getJsonCodec().toJson(message.getContent()))
                        .toList(),
                rebuilt.messages().stream()
                        .map(message -> JsonUtils.getJsonCodec().toJson(message.getContent()))
                        .toList());
        assertEquals(List.of(source("beta")), recovered(manifest()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customCompactionRunsOnceAndRecoveryUsesOnlyRemainingBudget(boolean enoughBudget) {
        RuntimeContext rc = context();
        String body = "full body ".repeat(enoughBudget ? 10 : 1000);
        SkillRuntime.registerLoadedBody(rc, "alpha", body);
        List<Msg> original = history(SkillLoadTool.TOOL_NAME, "preview", ToolResultState.SUCCESS);
        rc.getAgentState().contextMutable().addAll(original);
        WorkspaceContextMaterials.register(
                rc, List.of(new ContextItem("memory", "memory", "retained memory")));
        AtomicInteger compactions = new AtomicInteger();
        List<Msg> summary =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .textContent("summary without skill")
                                .build());
        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerMessages(1)
                        .flushBeforeCompact(false)
                        .strategy(
                                request -> {
                                    compactions.incrementAndGet();
                                    assertEquals(original, request.messages());
                                    return Mono.just(Optional.of(summary));
                                })
                        .build();
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("offline");
        ModelCallInput prepared =
                new HarnessContextBuilder(
                                new ContextPolicy(1000, 1, 1, estimator, manifests::add),
                                new CompactionMiddleware(null, model, config),
                                null)
                        .prepare(agent, rc, input(original), "custom", Purpose.REASONING)
                        .block();
        assertEquals(1, compactions.get());
        verify(model, never()).stream(any(), any(), any());
        assertEquals(summary, rc.getAgentState().contextMutable());
        assertEquals(enoughBudget ? List.of(source("alpha")) : List.of(), recovered(manifest()));
        assertTrue(
                prepared.messages().stream()
                        .anyMatch(message -> message.getTextContent().contains("retained memory")));
        assertFalse(
                manifest().transforms().stream()
                        .anyMatch(transform -> transform.startsWith("omitted_for_budget:")));
        String skip = "skill_recovery_skipped_for_budget:" + source("alpha");
        assertEquals(!enoughBudget, manifest().transforms().contains(skip));
        if (!enoughBudget) {
            List<ModelCallInput> rejected =
                    estimatedInputs.stream()
                            .filter(
                                    candidate ->
                                            candidate.messages().stream()
                                                    .anyMatch(
                                                            message ->
                                                                    message.getTextContent()
                                                                            .contains(
                                                                                    source(
                                                                                            "alpha"))))
                            .toList();
            assertEquals(1, rejected.size());
            assertTrue(
                    ContextTokenEstimator.approximate().estimate(rejected.get(0)).tokens()
                            > manifest().inputLimit());
        }
    }

    @Test
    void repeatedRealFullResultsAndOldBodiesRemainInTheirOriginalPositions() {
        RuntimeContext rc = context();
        SkillRuntime.registerLoadedBody(rc, "alpha", "old");
        SkillRuntime.registerLoadedBody(rc, "alpha", "latest");
        List<Msg> original =
                new ArrayList<>(history(SkillLoadTool.TOOL_NAME, "old", ToolResultState.SUCCESS));
        for (String id : List.of("new-one", "new-two")) {
            List<Msg> pair = history(SkillLoadTool.TOOL_NAME, "latest", ToolResultState.SUCCESS);
            ToolUseBlock use =
                    ToolUseBlock.builder()
                            .id(id)
                            .name(SkillLoadTool.TOOL_NAME)
                            .input(Map.of())
                            .build();
            ToolResultBlock result =
                    ((ToolResultBlock) pair.get(1).getContent().get(0))
                            .withIdAndName(id, SkillLoadTool.TOOL_NAME);
            original.add(pair.get(0).withContent(List.of(use)));
            original.add(pair.get(1).withContent(List.of(result)));
        }
        rc.getAgentState().contextMutable().addAll(original);
        assertEquals(original, prepare(rc, input(original), 10000).messages());
        assertTrue(recovered(manifest()).isEmpty());
        assertEquals(original, rc.getAgentState().contextMutable());
    }

    @Test
    void everyPrepareReevaluatesSkipAndDoesNotPersistRecovery() {
        RuntimeContext rc = context();
        SkillRuntime.registerLoadedBody(rc, "alpha", "body ".repeat(200));
        List<ContextItem> snapshot = SkillRuntime.loadedBodies(rc);
        ModelCallInput full = prepare(rc, input(List.of()), 10000);
        long limit = estimator.estimate(full).tokens();
        Msg extra =
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent("extra conversation ".repeat(10))
                        .build();
        prepare(rc, input(List.of(extra)), limit);
        assertTrue(recovered(manifest()).isEmpty());
        prepare(rc, input(List.of()), limit);
        assertEquals(List.of(source("alpha")), recovered(manifest()));
        assertTrue(manifest().transforms().isEmpty());
        prepare(rc, input(List.of(extra)), limit);
        assertTrue(recovered(manifest()).isEmpty());
        assertSame(snapshot, SkillRuntime.loadedBodies(rc));
        assertTrue(rc.getAgentState().contextMutable().isEmpty());
        assertThrows(
                ContextBudgetExceededException.class, () -> prepare(rc, input(List.of(extra)), 1));
    }

    @Test
    void preparationFreezesUpdatesAndOldFullTextDoesNotCoverTheNextSnapshot() {
        RuntimeContext rc = context();
        SkillRuntime.registerLoadedBody(rc, "alpha", "old body");
        SkillRuntime.registerLoadedBody(rc, "beta", "beta body");
        List<Msg> old = history(SkillLoadTool.TOOL_NAME, "old body", ToolResultState.SUCCESS);
        rc.getAgentState().contextMutable().addAll(old);
        ContextSources sources =
                ContextSources.empty()
                        .withSource(
                                "update",
                                request ->
                                        Mono.fromSupplier(
                                                () -> {
                                                    SkillRuntime.registerLoadedBody(
                                                            rc, "alpha", "new body");
                                                    return List.of();
                                                }),
                                ContextSourceOptions.defaults());
        HarnessContextBuilder builder =
                new HarnessContextBuilder(
                        new ContextPolicy(10000, 1, 1, estimator, manifests::add),
                        null,
                        null,
                        sources,
                        ContextSelectionPolicy.defaults());
        builder.prepare(null, rc, input(old), "old", Purpose.REASONING).block();
        assertEquals(List.of(source("beta")), recovered(manifest()));
        ModelCallInput next =
                builder.prepare(null, rc, input(old), "new", Purpose.REASONING).block();
        assertEquals(List.of(source("alpha"), source("beta")), recovered(manifest()));
        assertTrue(next.messages().stream().anyMatch(m -> m.getTextContent().contains("new body")));
        assertEquals(old, rc.getAgentState().contextMutable());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void controlledParallelRegistrationDefinesTheBudgetOrder(boolean reverse) throws Exception {
        RuntimeContext rc = context();
        List<String> order = reverse ? List.of("beta", "alpha") : List.of("alpha", "beta");
        CountDownLatch firstRegistered = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var second =
                    pool.submit(
                            () -> {
                                assertTrue(firstRegistered.await(5, TimeUnit.SECONDS));
                                SkillRuntime.registerLoadedBody(
                                        RuntimeContext.builder(rc).build(),
                                        order.get(1),
                                        "same body");
                                return null;
                            });
            var first =
                    pool.submit(
                            () -> {
                                SkillRuntime.registerLoadedBody(
                                        RuntimeContext.builder(rc).build(),
                                        order.get(0),
                                        "same body");
                                firstRegistered.countDown();
                            });
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        RuntimeContext single = context();
        SkillRuntime.registerLoadedBody(single, order.get(0), "same body");
        long limit = estimator.estimate(prepare(single, input(List.of()), 10000)).tokens();
        prepare(rc, input(List.of()), limit);
        assertEquals(List.of(source(order.get(0))), recovered(manifest()));
        assertEquals(
                List.of("skill_recovery_skipped_for_budget:" + source(order.get(1))),
                manifest().transforms());
    }
}
