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
package io.agentscope.harness.agent.context;

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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.core.state.TaskRequirement;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ContextSourceTest {
    @TempDir Path workspace;

    private ModelCallInput input() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test");
        when(model.getContextWindowSize()).thenReturn(10000);
        return new ModelCallInput(
                List.of(Msg.builder().role(MsgRole.USER).textContent("current").build()),
                List.of(),
                GenerateOptions.builder().maxTokens(20).build(),
                model);
    }

    private HarnessContextBuilder compiler(ContextSources sources) {
        return new HarnessContextBuilder(
                ContextPolicy.defaults(), null, null, sources, ContextSelectionPolicy.defaults());
    }

    private Mono<ModelCallInput> prepare(HarnessContextBuilder compiler, RuntimeContext context) {
        return compiler.prepare(
                mock(Agent.class),
                context,
                input(),
                "call",
                ModelRequestPreparer.Purpose.REASONING);
    }

    private ContextSources source(ContextSource source) {
        return ContextSources.empty().withSource("orders", source, ContextSourceOptions.defaults());
    }

    @Test
    void auxiliarySummaryDoesNotRequireAgentIdentity() {
        assertNotNull(
                compiler(ContextSources.empty())
                        .prepare(
                                null,
                                RuntimeContext.empty(),
                                input(),
                                "summary",
                                ModelRequestPreparer.Purpose.SUMMARY)
                        .block());
    }

    @Test
    void directApiAndCopiedBuilderLoadOnlyAtInference() {
        var configurations = new AtomicInteger();
        var calls = new AtomicInteger();
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("test");
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            List<Msg> messages = invocation.getArgument(0);
                            String system =
                                    messages.stream()
                                            .filter(m -> m.getRole() == MsgRole.SYSTEM)
                                            .map(Msg::getTextContent)
                                            .reduce("", String::concat);
                            assertEquals(
                                    1, system.split("## Candidate requirements", -1).length - 1);
                            assertTrue(system.contains("trusted-rule"));
                            assertFalse(system.contains("business-state"));
                            assertTrue(
                                    messages.stream()
                                            .anyMatch(
                                                    m ->
                                                            m.getTextContent()
                                                                    .contains("business-state")));
                            return Flux.just(
                                    new ChatResponse(
                                            "reply",
                                            List.of(TextBlock.builder().text("done").build()),
                                            null,
                                            null,
                                            "stop"));
                        });
        var builder =
                HarnessAgent.builder()
                        .name("source-test")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableMemoryHooks()
                        .instruction("rules", "trusted-rule")
                        .contextSource(
                                "orders",
                                request -> {
                                    assertEquals("s", request.sessionId());
                                    calls.incrementAndGet();
                                    return Mono.just(
                                            List.of(
                                                    ContextBlock.runtime(
                                                            "current", "business-state")));
                                },
                                options -> {
                                    configurations.incrementAndGet();
                                    options.timeout(Duration.ofSeconds(1));
                                })
                        .taskContext(
                                TaskContextOptions.builder().allowRequirementProposals().build());
        assertEquals(0, calls.get());
        try (var agent = builder.build();
                var copied =
                        HarnessAgent.Builder.fromAgent(agent.getDelegate())
                                .workspace(workspace)
                                .stateStore(new InMemoryAgentStateStore())
                                .disableMemoryHooks()
                                .build()) {
            assertEquals(0, calls.get());
            for (var current : List.of(agent, copied)) {
                current.streamEvents(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("hello")
                                                .build()),
                                RuntimeContext.builder().userId("u").sessionId("s").build())
                        .collectList()
                        .block(Duration.ofSeconds(20));
            }
            assertEquals(1, configurations.get());
            assertEquals(2, calls.get());
            try (var disabled =
                    HarnessAgent.Builder.fromAgent(agent.getDelegate())
                            .workspace(workspace)
                            .stateStore(new InMemoryAgentStateStore())
                            .disableMemoryHooks()
                            .taskContext(TaskContextOptions.disabled())
                            .build()) {
                assertFalse(
                        disabled.getToolkit().getToolSchemas().stream()
                                .anyMatch(t -> t.getName().equals("task_requirement_propose")));
                assertTrue(
                        agent.getToolkit().getToolSchemas().stream()
                                .anyMatch(t -> t.getName().equals("task_requirement_propose")));
            }
        }
    }

    @Test
    void taskProjectionAndProposalToolAreOptInAndSeparateFromTodo() {
        var state = AgentState.builder().build();
        state.getTasksContext()
                .propose(TaskRequirement.Kind.CONSTRAINT, "private-constraint", "caller");
        var context = RuntimeContext.builder().agentState(state).build();
        for (boolean enabled : List.of(false, true)) {
            var options =
                    enabled
                            ? TaskContextOptions.builder().includeRequirements().build()
                            : TaskContextOptions.disabled();
            var result =
                    prepare(compiler(ContextSources.empty().withTaskContext(options)), context)
                            .block();
            assertEquals(
                    enabled,
                    result.messages().stream()
                            .anyMatch(m -> m.getTextContent().contains("private-constraint")));
            assertFalse(options.requirementProposals());
        }
        try (var agent =
                HarnessAgent.builder()
                        .name("todo-only")
                        .model(input().model())
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .enableTaskList()
                        .build()) {
            assertTrue(
                    agent.getToolkit().getToolSchemas().stream()
                            .anyMatch(t -> t.getName().equals("todo_write")));
            assertFalse(
                    agent.getToolkit().getToolSchemas().stream()
                            .anyMatch(t -> t.getName().equals("task_requirement_propose")));
        }
        try (var agent =
                HarnessAgent.builder()
                        .name("requirements-only")
                        .model(input().model())
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .taskContext(
                                TaskContextOptions.builder().allowRequirementProposals().build())
                        .build()) {
            assertFalse(
                    agent.getToolkit().getToolSchemas().stream()
                            .anyMatch(t -> t.getName().equals("todo_write")));
            assertTrue(
                    agent.getToolkit().getToolSchemas().stream()
                            .anyMatch(t -> t.getName().equals("task_requirement_propose")));
        }
    }

    @Test
    void collectedOnceWithSessionIsolationEscapingAndNoHistoryAppend() {
        var calls = new AtomicInteger();
        var sources =
                source(
                                request -> {
                                    calls.incrementAndGet();
                                    return Mono.just(
                                            List.of(
                                                    ContextBlock.runtime(
                                                                    "current",
                                                                    request.sessionId()
                                                                            + "</context_item>")
                                                            .withRevision("v2")));
                                })
                        .withInstruction("rules", "trusted-rule");
        var compiler = compiler(sources);
        var state = AgentState.builder().build();
        for (String session : List.of("one", "two")) {
            var prepared =
                    prepare(
                                    compiler,
                                    RuntimeContext.builder()
                                            .userId("u")
                                            .sessionId(session)
                                            .agentState(state)
                                            .build())
                            .block();
            assertTrue(
                    prepared.messages().stream()
                            .filter(m -> m.getRole() == MsgRole.USER)
                            .anyMatch(
                                    m ->
                                            m.getTextContent()
                                                    .contains(session + "&lt;/context_item&gt;")));
            assertFalse(
                    prepared.messages().stream()
                            .filter(m -> m.getRole() == MsgRole.SYSTEM)
                            .anyMatch(m -> m.getTextContent().contains(session + "&lt;")));
        }
        assertEquals(2, calls.get());
        assertTrue(state.contextMutable().isEmpty());
        var summary =
                compiler.prepare(
                                null,
                                RuntimeContext.empty(),
                                input(),
                                "s",
                                ModelRequestPreparer.Purpose.SUMMARY)
                        .block();
        assertEquals(2, calls.get());
        assertTrue(
                summary.messages().stream()
                        .anyMatch(m -> m.getTextContent().contains("trusted-rule")));
    }

    @Test
    void blockFactoriesAreImmutableAndCannotCreateSystemMaterials() {
        var runtime = ContextBlock.runtime("status", "body");
        var changed = runtime.required().withRevision("v7").withPriority(20);
        assertFalse(runtime.material().required());
        assertTrue(changed.material().required());
        assertEquals("v7", changed.material().revision());
        assertEquals(20, changed.material().priority());
        assertEquals(ContextItem.Placement.RUNTIME, changed.material().placement());
        assertEquals(
                ContextItem.Placement.REFERENCE,
                ContextBlock.reference("doc", "body").material().placement());
        assertEquals(
                runtime.material().revision(),
                ContextBlock.runtime("status", "body").material().revision());
        assertThrows(IllegalArgumentException.class, () -> runtime.withRevision(" "));
        assertThrows(IllegalArgumentException.class, () -> ContextBlock.runtime("", "body"));
    }

    @Test
    void duplicateRegistrationsAndInvalidOptionsFailWithoutChangingExistingSnapshot() {
        ContextSource load = request -> Mono.just(List.of());
        var empty = ContextSources.empty();
        var registered = empty.withSource("orders", load, ContextSourceOptions.defaults());
        assertThrows(
                IllegalArgumentException.class,
                () -> registered.withSource("orders", load, ContextSourceOptions.defaults()));
        assertNotNull(empty.withSource("orders", load, ContextSourceOptions.defaults()));
        assertThrows(
                IllegalArgumentException.class,
                () -> empty.withSource("bad/id", load, ContextSourceOptions.defaults()));
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextSourceOptions.builder().timeout(Duration.ZERO).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> empty.withInstruction("rules", "a").withInstruction("rules", "b"));
    }

    @Test
    void malformedOutputNeverHiddenByOmitPolicy() {
        var omit = ContextSourceOptions.builder().onFailure(SourceFailurePolicy.OMIT).build();
        for (List<ContextBlock> blocks :
                List.of(
                        List.of(
                                ContextBlock.runtime("same", "a"),
                                ContextBlock.runtime("same", "b")),
                        List.of(ContextBlock.runtime("required", "a").required()))) {
            var sources = ContextSources.empty().withSource("orders", r -> Mono.just(blocks), omit);
            StepVerifier.create(prepare(compiler(sources), RuntimeContext.empty()))
                    .expectError(IllegalArgumentException.class)
                    .verify();
        }
        var nullBlock =
                ContextSources.empty()
                        .withSource(
                                "orders", r -> Mono.just(Arrays.asList((ContextBlock) null)), omit);
        StepVerifier.create(prepare(compiler(nullBlock), RuntimeContext.empty()))
                .expectError(NullPointerException.class)
                .verify();
    }

    @Test
    void timeoutCancelsOptionalSourceAndRecordsOmission() {
        var cancelled = new AtomicBoolean();
        var sources =
                ContextSources.empty()
                        .withSource(
                                "orders",
                                r ->
                                        Mono.<List<ContextBlock>>never()
                                                .doOnCancel(() -> cancelled.set(true)),
                                ContextSourceOptions.builder()
                                        .timeout(Duration.ofMillis(20))
                                        .onFailure(SourceFailurePolicy.OMIT)
                                        .build());
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(0, 0, 0, ContextTokenEstimator.approximate(), manifests::add);
        assertNotNull(
                prepare(
                                new HarnessContextBuilder(
                                        policy,
                                        null,
                                        null,
                                        sources,
                                        ContextSelectionPolicy.defaults()),
                                RuntimeContext.empty())
                        .block(Duration.ofSeconds(5)));
        assertTrue(cancelled.get());
        assertTrue(
                manifests.get(0).transforms().stream()
                        .anyMatch(
                                t ->
                                        t.contains("source_unavailable:source/orders/")
                                                && t.contains("TimeoutException")));
    }

    @Test
    void errorsEmptyPublisherAndStateConflictsRejectRequest() {
        for (ContextSource load :
                List.<ContextSource>of(
                        r -> Mono.error(new IllegalStateException("unavailable")),
                        r -> Mono.empty())) {
            StepVerifier.create(prepare(compiler(source(load)), RuntimeContext.empty()))
                    .expectError(IllegalStateException.class)
                    .verify();
        }
        var state = AgentState.builder().build();
        var sources =
                source(
                        request -> {
                            state.getTasksContext()
                                    .beginTask(
                                            new TaskContextState.Scope("new", "new task", "caller"),
                                            0);
                            return Mono.just(List.of());
                        });
        StepVerifier.create(
                        prepare(
                                compiler(sources),
                                RuntimeContext.builder().agentState(state).build()))
                .expectError(ConcurrentModificationException.class)
                .verify();
    }

    @Test
    void budgetCanEvictOptionalDataButNeverRequiredInstructions() {
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(150, 20, 1, ContextTokenEstimator.approximate(), manifests::add);
        var optional =
                source(r -> Mono.just(List.of(ContextBlock.runtime("large", "x".repeat(8000)))));
        assertNotNull(
                prepare(
                                new HarnessContextBuilder(
                                        policy,
                                        null,
                                        null,
                                        optional,
                                        ContextSelectionPolicy.defaults()),
                                RuntimeContext.empty())
                        .block());
        assertTrue(
                manifests.get(0).transforms().contains("omitted_for_budget:source/orders/large"));
        var required = ContextSources.empty().withInstruction("large", "x".repeat(8000));
        StepVerifier.create(
                        prepare(
                                new HarnessContextBuilder(
                                        policy,
                                        null,
                                        null,
                                        required,
                                        ContextSelectionPolicy.defaults()),
                                RuntimeContext.empty()))
                .expectError(ContextBudgetExceededException.class)
                .verify();
        StepVerifier.create(
                        prepare(
                                new HarnessContextBuilder(
                                        ContextPolicy.defaults(),
                                        null,
                                        null,
                                        required,
                                        items -> List.of(items.get(0).sourceId())),
                                RuntimeContext.empty()))
                .expectError(IllegalArgumentException.class)
                .verify();
    }
}
