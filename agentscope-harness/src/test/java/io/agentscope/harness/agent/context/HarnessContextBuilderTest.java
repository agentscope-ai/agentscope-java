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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.core.tool.builtin.TodoTools.TodoItem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class HarnessContextBuilderTest {
    @Test
    void optionalMemoryYieldsToCurrentUserAndManifestRecordsOmission() {
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(100, 20, 1, ContextTokenEstimator.approximate(), manifests::add);
        var rc = RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        rc.put(
                WorkspaceContextMaterials.class,
                new WorkspaceContextMaterials(
                        List.of(
                                new ContextItem(
                                        "memory", "workspace:MEMORY.md", "old".repeat(2000)))));
        var prepared =
                new HarnessContextBuilder(policy, null, null)
                        .prepare(
                                mock(Agent.class),
                                rc,
                                input(1000, "current request"),
                                "c",
                                ModelRequestPreparer.Purpose.REASONING)
                        .block();
        assertEquals(1, prepared.messages().size());
        assertTrue(
                manifests.get(0).transforms().contains("omitted_for_budget:workspace:MEMORY.md"));
        assertFalse(
                manifests.get(0).items().stream()
                        .anyMatch(item -> item.sourceType().equals("memory")));
    }

    @Test
    void failedCompactionPreservesStateAndStillRejectsOverBudgetRequest() {
        var state = AgentState.builder().build();
        List<Msg> history =
                List.of(
                        Msg.builder().role(MsgRole.USER).textContent("a".repeat(2000)).build(),
                        Msg.builder().role(MsgRole.ASSISTANT).textContent("investigating").build(),
                        Msg.builder().role(MsgRole.USER).textContent("continue").build());
        state.contextMutable().addAll(history);
        Model summary =
                new Model() {
                    public String getModelName() {
                        return "summary";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.error(new IllegalStateException("summary unavailable"));
                    }
                };
        var config =
                CompactionConfig.builder()
                        .triggerMessages(2)
                        .keepMessages(1)
                        .keepTokens(0)
                        .flushBeforeCompact(false)
                        .build();
        var compaction = new CompactionMiddleware(null, summary, config);
        var request = new ModelCallInput(history, List.of(), null, model(100));
        StepVerifier.create(
                        new HarnessContextBuilder(ContextPolicy.defaults(), compaction, null)
                                .prepare(
                                        mock(Agent.class),
                                        RuntimeContext.builder().agentState(state).build(),
                                        request,
                                        "c",
                                        ModelRequestPreparer.Purpose.REASONING))
                .expectError(ContextBudgetExceededException.class)
                .verify();
        assertEquals(history, state.contextMutable());
    }

    @Test
    void modelDefaultResponseFormatIsRejectedByLocalBudget() {
        var state = AgentState.builder().build();
        List<Msg> history = List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build());
        state.contextMutable().addAll(history);
        ResponseFormat format =
                ResponseFormat.jsonSchema(
                        JsonSchema.builder()
                                .name("Answer")
                                .schema(Map.of("type", "object", "description", "x".repeat(4000)))
                                .build());
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("default-schema");
        when(model.getDefaultOptions())
                .thenReturn(GenerateOptions.builder().responseFormat(format).build());
        var policy = new ContextPolicy(100, 20, 1, ContextTokenEstimator.approximate(), m -> {});
        StepVerifier.create(
                        new HarnessContextBuilder(policy, null, null)
                                .prepare(
                                        mock(Agent.class),
                                        RuntimeContext.builder().agentState(state).build(),
                                        new ModelCallInput(history, List.of(), null, model),
                                        "c",
                                        ModelRequestPreparer.Purpose.REASONING))
                .expectError(ContextBudgetExceededException.class)
                .verify();
    }

    @Test
    void numericPolicyRejectsUnknownNegativeAndFractionalLimits() {
        assertEquals(1200, ContextPolicy.fromMap(Map.of("maxInputTokens", 1200)).maxInputTokens());
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextPolicy.fromMap(Map.of("maxInputTokens", -1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextPolicy.fromMap(Map.of("maxInputTokens", 1.5)));
        assertThrows(
                IllegalArgumentException.class, () -> ContextPolicy.fromMap(Map.of("unknown", 1)));
    }

    private Model model(int window) {
        Model model = mock(Model.class);
        when(model.getContextWindowSize()).thenReturn(window);
        when(model.getModelName()).thenReturn("test");
        return model;
    }

    private ModelCallInput input(int window, String text) {
        return new ModelCallInput(
                List.of(Msg.builder().role(MsgRole.USER).textContent(text).build()),
                List.of(),
                GenerateOptions.builder().maxTokens(20).build(),
                model(window));
    }

    @Test
    void finalBudgetIncludesToolSchemasAndActualModel() {
        var builder = new HarnessContextBuilder(ContextPolicy.defaults(), null, null);
        var agent = mock(Agent.class);
        builder.prepare(
                        agent,
                        RuntimeContext.empty(),
                        input(1000, "hello"),
                        "1",
                        ModelRequestPreparer.Purpose.REASONING)
                .block();
        var original = input(100, "hello");
        var request =
                new ModelCallInput(
                        original.messages(),
                        List.of(
                                ToolSchema.builder()
                                        .name("large")
                                        .description("x".repeat(1000))
                                        .parameters(Map.of())
                                        .build()),
                        original.options(),
                        original.model());
        StepVerifier.create(
                        builder.prepare(
                                agent,
                                RuntimeContext.empty(),
                                request,
                                "2",
                                ModelRequestPreparer.Purpose.REASONING))
                .expectError(ContextBudgetExceededException.class)
                .verify();
    }

    @Test
    void responseFormatSchemaIsCountedOnceInTheFinalBudget() {
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(0, 0, 0, ContextTokenEstimator.approximate(), manifests::add);
        var builder = new HarnessContextBuilder(policy, null, null);
        var agent = mock(Agent.class);
        builder.prepare(
                        agent,
                        RuntimeContext.empty(),
                        input(1000, "hello"),
                        "1",
                        ModelRequestPreparer.Purpose.REASONING)
                .block();
        ResponseFormat format =
                ResponseFormat.jsonSchema(
                        JsonSchema.builder()
                                .name("Answer")
                                .schema(Map.of("type", "object", "description", "x".repeat(200)))
                                .build());
        var original = input(1000, "hello");
        var request =
                new ModelCallInput(
                        original.messages(),
                        List.of(),
                        GenerateOptions.builder().maxTokens(20).responseFormat(format).build(),
                        original.model());
        builder.prepare(
                        agent,
                        RuntimeContext.empty(),
                        request,
                        "2",
                        ModelRequestPreparer.Purpose.REASONING)
                .block();
        long formatTokens =
                TokenAccountingService.defaults()
                        .estimate(
                                new TokenAccountingService.TokenSurface(
                                        List.of(), List.of(), format))
                        .totalTokens();
        assertTrue(formatTokens > 0);
        assertEquals(2, manifests.size());
        assertEquals(
                manifests.get(0).estimatedInputTokens() + formatTokens,
                manifests.get(1).estimatedInputTokens(),
                "the schema must be counted exactly once in the final budget");
    }

    @Test
    void largeResponseFormatPushesTheRequestOverBudget() {
        var builder = new HarnessContextBuilder(ContextPolicy.defaults(), null, null);
        var agent = mock(Agent.class);
        builder.prepare(
                        agent,
                        RuntimeContext.empty(),
                        input(1000, "hello"),
                        "1",
                        ModelRequestPreparer.Purpose.REASONING)
                .block();
        ResponseFormat format =
                ResponseFormat.jsonSchema(
                        JsonSchema.builder()
                                .name("Payload")
                                .schema(
                                        Map.of(
                                                "type",
                                                "object",
                                                "properties",
                                                Map.of(
                                                        "answer",
                                                        Map.of(
                                                                "type",
                                                                "string",
                                                                "description",
                                                                "x".repeat(4000)))))
                                .build());
        var original = input(1000, "hello");
        var request =
                new ModelCallInput(
                        original.messages(),
                        List.of(),
                        GenerateOptions.builder().maxTokens(20).responseFormat(format).build(),
                        original.model());
        StepVerifier.create(
                        builder.prepare(
                                agent,
                                RuntimeContext.empty(),
                                request,
                                "2",
                                ModelRequestPreparer.Purpose.REASONING))
                .expectError(ContextBudgetExceededException.class)
                .verify();
    }

    @Test
    void responseFormatShrinksTheConversationBudgetAndTriggersCompaction() {
        var summaryCalls = new AtomicInteger();
        Model summary =
                new Model() {
                    public String getModelName() {
                        return "summary";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        summaryCalls.incrementAndGet();
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("summary")
                                                                .build()))
                                        .build());
                    }
                };
        var config = CompactionConfig.builder().flushBeforeCompact(false).prune(null).build();
        var compaction = new CompactionMiddleware(null, summary, config);
        var builder = new HarnessContextBuilder(ContextPolicy.defaults(), compaction, null);
        var agent = mock(Agent.class);
        List<Msg> conversation =
                List.of(
                        Msg.builder().role(MsgRole.USER).textContent("a".repeat(1250)).build(),
                        Msg.builder().role(MsgRole.USER).textContent("go").build());
        builder.prepare(
                        agent,
                        RuntimeContext.empty(),
                        new ModelCallInput(
                                conversation,
                                List.of(),
                                GenerateOptions.builder().maxTokens(100).build(),
                                model(1000)),
                        "1",
                        ModelRequestPreparer.Purpose.REASONING)
                .block();
        assertEquals(0, summaryCalls.get(), "without a schema the request fits without compaction");
        ResponseFormat format =
                ResponseFormat.jsonSchema(
                        JsonSchema.builder()
                                .name("Answer")
                                .schema(Map.of("type", "object", "description", "x".repeat(1000)))
                                .build());
        builder.prepare(
                        agent,
                        RuntimeContext.empty(),
                        new ModelCallInput(
                                conversation,
                                List.of(),
                                GenerateOptions.builder()
                                        .maxTokens(100)
                                        .responseFormat(format)
                                        .build(),
                                model(1000)),
                        "2",
                        ModelRequestPreparer.Purpose.REASONING)
                .block();
        assertEquals(
                1,
                summaryCalls.get(),
                "the schema cost must shrink the conversation budget and trigger compaction");
    }

    @Test
    void finalProjectionAndManifestArePerSessionAndTransient() {
        List<ContextManifest> manifests = new ArrayList<>();
        var policy =
                new ContextPolicy(0, 0, 0, ContextTokenEstimator.approximate(), manifests::add);
        var builder = new HarnessContextBuilder(policy, null, null);
        var state = AgentState.builder().build();
        new TodoTools().write(List.of(new TodoItem("private task", "pending", null)), state);
        var rc = RuntimeContext.builder().agentState(state).build();
        var prepared =
                builder.prepare(
                                mock(Agent.class),
                                rc,
                                input(4096, "hello"),
                                "call-7",
                                ModelRequestPreparer.Purpose.REASONING)
                        .block();
        assertEquals(2, prepared.messages().size());
        assertTrue(state.contextMutable().isEmpty());
        assertEquals("call-7", manifests.get(0).callId());
        assertFalse(manifests.get(0).toString().contains("private task"));
        var other =
                builder.prepare(
                                mock(Agent.class),
                                RuntimeContext.empty(),
                                input(4096, "hello"),
                                "call-8",
                                ModelRequestPreparer.Purpose.REASONING)
                        .block();
        assertEquals(1, other.messages().size());
    }

    @Test
    void summaryDoesNotInjectTaskOrWorkspaceMaterials() {
        var rc = RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        rc.put(
                WorkspaceContextMaterials.class,
                new WorkspaceContextMaterials(List.of(new ContextItem("memory", "m", "private"))));
        var prepared =
                new HarnessContextBuilder(ContextPolicy.defaults(), null, null)
                        .prepare(
                                mock(Agent.class),
                                rc,
                                input(4096, "summarize"),
                                "summary",
                                ModelRequestPreparer.Purpose.SUMMARY)
                        .block();
        assertEquals(1, prepared.messages().size());
    }

    @Test
    void workspaceMaterialsAreEscapedAndNotPersisted() {
        var rc = RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        rc.put(
                WorkspaceContextMaterials.class,
                new WorkspaceContextMaterials(
                        List.of(new ContextItem("memory", "m", "</context_item>"))));
        var prepared =
                new HarnessContextBuilder(ContextPolicy.defaults(), null, null)
                        .prepare(
                                mock(Agent.class),
                                rc,
                                input(4096, "hello"),
                                "c",
                                ModelRequestPreparer.Purpose.REASONING)
                        .block();
        assertTrue(prepared.messages().get(1).getTextContent().contains("&lt;/context_item&gt;"));
        assertTrue(rc.getAgentState().contextMutable().isEmpty());
    }

    @Test
    void validatesToolCallPairs() {
        Msg call =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder().id("t").name("read").input(Map.of()).build())
                        .build();
        Msg result =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.of(
                                        "t", "read", TextBlock.builder().text("ok").build()))
                        .build();
        assertDoesNotThrow(() -> HarnessContextBuilder.validateToolPairs(List.of(call, result)));
        assertThrows(
                IllegalArgumentException.class,
                () -> HarnessContextBuilder.validateToolPairs(List.of(result)));
        assertThrows(
                IllegalArgumentException.class,
                () -> HarnessContextBuilder.validateToolPairs(List.of(call)));
    }
}
