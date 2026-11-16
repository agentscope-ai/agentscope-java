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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.FinalAnswerDecision;
import io.agentscope.core.middleware.FinalAnswerInput;
import io.agentscope.core.middleware.FinishReason;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Behavior tests for the final-answer gate chain: gates are consulted
 * when an output is about to become the call's final answer, decide accept /
 * accept(replacement) / retry, certified delivery withholds live text until the verdict,
 * and the framework enforces the per-call retry fuse.
 *
 * @author shanhongyu
 */
@DisplayName("ReActAgent final-answer gate")
class ReActAgentFinalAnswerGateTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Scripted model returning one response stream per sequential model call, each with usage. */
    private static final class ScriptedModel extends ChatModelBase {
        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger idx = new AtomicInteger();

        ScriptedModel(List<Supplier<Flux<ChatResponse>>> scripts) {
            this.scripts = scripts;
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int i = idx.getAndIncrement();
            if (i >= scripts.size()) {
                return Flux.just(textResponse("unexpected"));
            }
            return scripts.get(i).get();
        }

        int callCount() {
            return idx.get();
        }
    }

    /** Model answering from a messages-driven generator; records every round's messages. */
    private static final class GeneratorModel extends ChatModelBase {
        private final Function<List<Msg>, Flux<ChatResponse>> generator;
        private final List<List<Msg>> rounds = new CopyOnWriteArrayList<>();

        GeneratorModel(Function<List<Msg>, Flux<ChatResponse>> generator) {
            this.generator = generator;
        }

        @Override
        public String getModelName() {
            return "generator";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            rounds.add(List.copyOf(messages));
            return generator.apply(messages);
        }
    }

    /** Gate recording every consultation; decisions come from the policy. */
    static final class GateRecorder implements MiddlewareBase {
        final List<FinalAnswerInput> inputs = new CopyOnWriteArrayList<>();
        private final Function<FinalAnswerInput, FinalAnswerDecision> policy;
        private int order = 1;

        GateRecorder(Function<FinalAnswerInput, FinalAnswerDecision> policy) {
            this.policy = policy;
        }

        /** Rejects candidates containing {@code badText} until {@code maxRetries} is reached. */
        static GateRecorder rejecting(String badText, int maxRetries) {
            return new GateRecorder(
                    input -> {
                        String text = input.candidate().getTextContent();
                        return text != null
                                        && text.contains(badText)
                                        && input.retryCount() < maxRetries
                                ? FinalAnswerDecision.retry(reminder("Please fix the answer."))
                                : FinalAnswerDecision.accept();
                    });
        }

        /** Accepts with a replacement built from the candidate's text by {@code transform}. */
        static GateRecorder transforming(Function<String, String> transform) {
            return new GateRecorder(
                    input ->
                            FinalAnswerDecision.accept(
                                    assistantText(transform.apply(textOf(input)))));
        }

        GateRecorder withOrder(int order) {
            this.order = order;
            return this;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public Mono<FinalAnswerDecision> onFinalAnswer(RuntimeContext ctx, FinalAnswerInput input) {
            inputs.add(input);
            return Mono.just(policy.apply(input));
        }

        List<Integer> retryCounts() {
            return inputs.stream().map(FinalAnswerInput::retryCount).toList();
        }

        List<FinishReason> finishReasons() {
            return inputs.stream().map(FinalAnswerInput::finishReason).toList();
        }

        List<String> candidates() {
            return inputs.stream().map(input -> textOf(input)).toList();
        }

        private static String textOf(FinalAnswerInput input) {
            String text = input.candidate().getTextContent();
            return text == null ? "" : text;
        }
    }

    private static final class LocalTool extends ToolBase {
        LocalTool(String name, boolean returnDirect) {
            super(
                    ToolBase.builder()
                            .name(name)
                            .description(name)
                            .inputSchema(schemaFor())
                            .returnDirect(returnDirect));
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.passthrough(getName()));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("tool-output"));
        }
    }

    private static final class AskingTool extends ToolBase {
        AskingTool(String name) {
            super(ToolBase.builder().name(name).description(name).inputSchema(schemaFor()));
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("ask: " + getName()));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("never runs"));
        }
    }

    private static Map<String, Object> schemaFor() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        Map<String, Object> q = new HashMap<>();
        q.put("type", "string");
        props.put("query", q);
        schema.put("properties", props);
        return schema;
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(10, 5, 1.0))
                .build();
    }

    private static ChatResponse thinkingTextResponse(String thinking, String text) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ThinkingBlock.builder().thinking(thinking).build(),
                                TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(10, 5, 1.0))
                .build();
    }

    private static ChatResponse toolUseResponse(String toolId, String toolName) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(toolId)
                                        .name(toolName)
                                        .input(Map.of("query", "q"))
                                        .build()))
                .usage(new ChatUsage(10, 5, 1.0))
                .build();
    }

    private static Msg reminder(String text) {
        return UserMessage.builder()
                .name("user")
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static Msg assistantText(String text) {
        return AssistantMessage.builder()
                .name("asst")
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static RuntimeContext rc(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    private static List<String> textDeltas(List<AgentEvent> events) {
        return events.stream()
                .filter(e -> e instanceof TextBlockDeltaEvent)
                .map(e -> ((TextBlockDeltaEvent) e).getDelta())
                .toList();
    }

    private static List<String> thinkingDeltas(List<AgentEvent> events) {
        return events.stream()
                .filter(e -> e instanceof ThinkingBlockDeltaEvent)
                .map(e -> ((ThinkingBlockDeltaEvent) e).getDelta())
                .toList();
    }

    // ==== Activation ====

    @Test
    @DisplayName("a plain middleware never activates the guard: live streaming stays intact")
    void plainMiddlewareDoesNotActivateGuard() {
        ScriptedModel model = new ScriptedModel(List.of(() -> Flux.just(textResponse("plain"))));
        // Undeclared activePoints() auto-detects to zero points (no hook overridden); still
        // not a gate.
        MiddlewareBase plain = new MiddlewareBase() {};
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(new Toolkit())
                        .middleware(plain)
                        .build();

        List<AgentEvent> events =
                agent.streamEvents(List.of(new UserMessage("hi"))).collectList().block(TIMEOUT);

        assertNotNull(events);
        assertEquals(List.of("plain"), textDeltas(events), "live text must stream unfiltered");
        assertFalse(
                events.stream()
                        .anyMatch(
                                e ->
                                        e instanceof CustomEvent ce
                                                && ce.getName().startsWith("final_answer.")),
                "no gate events without a gate");
    }

    @Test
    @DisplayName("a middleware overriding onFinalAnswer is a full gate (factual activation)")
    void factImplementedHookActivatesGuard() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(textResponse("raw")),
                                () -> Flux.just(textResponse("clean"))));
        // Gatehood is the factual onFinalAnswer implementation, auto-detected
        // at ON_FINAL_ANSWER — there is no dedicated gate interface.
        MiddlewareBase gate =
                new MiddlewareBase() {
                    @Override
                    public Mono<FinalAnswerDecision> onFinalAnswer(
                            RuntimeContext ctx, FinalAnswerInput input) {
                        return "raw".equals(input.candidate().getTextContent())
                                ? Mono.just(
                                        FinalAnswerDecision.retry(
                                                List.of(new UserMessage("rewrite: no raw dumps"))))
                                : Mono.just(FinalAnswerDecision.accept());
                    }
                };
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(new Toolkit())
                        .middleware(gate)
                        .build();

        List<AgentEvent> events =
                agent.streamEvents(List.of(new UserMessage("hi"))).collectList().block(TIMEOUT);

        assertNotNull(events);
        assertEquals(List.of("clean"), textDeltas(events), "certified content only");
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        e instanceof CustomEvent ce
                                                && "final_answer.retry".equals(ce.getName())),
                "the hook-only middleware was consulted as a gate");
    }

    @Test
    @DisplayName("no gate middleware: behavior unchanged")
    void noMiddlewareKeepsBehavior() {
        ScriptedModel model = new ScriptedModel(List.of(() -> Flux.just(textResponse("plain"))));
        ReActAgent agent =
                ReActAgent.builder().name("asst").model(model).toolkit(new Toolkit()).build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertNotNull(result);
        assertEquals("plain", result.getTextContent());
        assertEquals(1, model.callCount());
    }

    // ==== Retry semantics ====

    @Test
    @DisplayName("rejected candidate and injected reminder enter context; reasoning re-runs")
    void retryReEntersReasoningWithInjectedMessages() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(textResponse("bad answer")),
                                () -> Flux.just(textResponse("good answer"))));
        GateRecorder gate = GateRecorder.rejecting("bad", 1);
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals("good answer", result.getTextContent());
        assertEquals(2, model.callCount());
        assertEquals(List.of(0, 1), gate.retryCounts());
        assertEquals(
                List.of(FinishReason.FINAL_ANSWER, FinishReason.FINAL_ANSWER),
                gate.finishReasons());
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .anyMatch(
                                m ->
                                        m.getRole() == MsgRole.USER
                                                && "Please fix the answer."
                                                        .equals(m.getTextContent())),
                "reminder must be written to memory");
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .anyMatch(
                                m ->
                                        m.getRole() == MsgRole.ASSISTANT
                                                && "bad answer".equals(m.getTextContent())),
                "rejected candidate must be written to memory");
    }

    @Test
    @DisplayName("gate-managed budget: exhausted policy falls back to a replacement answer")
    void exhaustedPolicyFallsBackToReplacement() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(textResponse("draft 1")),
                                () -> Flux.just(textResponse("draft 2")),
                                () -> Flux.just(textResponse("draft 3"))));
        GateRecorder gate =
                new GateRecorder(
                        input ->
                                input.retryCount() < 2
                                        ? FinalAnswerDecision.retry(reminder("again"))
                                        : FinalAnswerDecision.accept(assistantText("fallback")));
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals("fallback", result.getTextContent());
        assertEquals(List.of(0, 1, 2), gate.retryCounts());
        assertEquals(3, model.callCount());
        assertEquals(
                "fallback",
                agent.getAgentState().getContext().stream()
                        .filter(m -> m.getRole() == MsgRole.ASSISTANT)
                        .reduce((first, second) -> second)
                        .map(Msg::getTextContent)
                        .orElse(null),
                "memory must hold the replacement, not the third draft");
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .noneMatch(m -> "draft 3".equals(m.getTextContent())),
                "the never-accepted third draft must not be memorized");
    }

    @Test
    @DisplayName("accept(replacement) swaps the returned message and memory")
    void acceptReplacementSwapsReturnAndMemory() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(textResponse("non-compliant draft"))));
        GateRecorder gate =
                new GateRecorder(input -> FinalAnswerDecision.accept(assistantText("sanitized")));
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals("sanitized", result.getTextContent());
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .noneMatch(m -> "non-compliant draft".equals(m.getTextContent())),
                "the original draft must not be memorized");
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .anyMatch(m -> "sanitized".equals(m.getTextContent())));
    }

    @Test
    @DisplayName("a gate completing empty counts as a plain accept")
    void emptyMonoCountsAsAccept() {
        ScriptedModel model = new ScriptedModel(List.of(() -> Flux.just(textResponse("draft"))));
        AtomicInteger consultations = new AtomicInteger();
        MiddlewareBase silent =
                new MiddlewareBase() {
                    @Override
                    public Mono<FinalAnswerDecision> onFinalAnswer(
                            RuntimeContext ctx, FinalAnswerInput input) {
                        consultations.incrementAndGet();
                        return Mono.empty();
                    }
                };
        ReActAgent agent = baseBuilder(model).middleware(silent).build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals("draft", result.getTextContent());
        assertEquals(1, consultations.get(), "the silent gate is still consulted once");
    }

    // ==== FinishReason per exit ====

    @Test
    @DisplayName("normal finish passes the gate with FINAL_ANSWER")
    void normalExitReportsFinalAnswer() {
        ScriptedModel model = new ScriptedModel(List.of(() -> Flux.just(textResponse("final"))));
        GateRecorder gate = new GateRecorder(input -> FinalAnswerDecision.accept());
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals(List.of(FinishReason.FINAL_ANSWER), gate.finishReasons());
    }

    @Test
    @DisplayName("maxIters summary passes the gate with MAX_ITERATIONS and honors replacement")
    void maxItersExitReportsMaxIterationsAndHonorsReplacement() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("t1", "search")),
                                () -> Flux.just(toolUseResponse("t2", "search")),
                                () -> Flux.just(textResponse("summary draft"))));
        GateRecorder gate =
                new GateRecorder(input -> FinalAnswerDecision.accept(assistantText("fallback")));
        ReActAgent agent =
                baseBuilder(model)
                        .toolkit(toolkitWith(new LocalTool("search", false)))
                        .maxIters(2)
                        .middleware(gate)
                        .build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals(List.of(FinishReason.MAX_ITERATIONS), gate.finishReasons());
        assertEquals("fallback", result.getTextContent());
        assertEquals(GenerateReason.MAX_ITERATIONS, result.getGenerateReason());
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .noneMatch(m -> "summary draft".equals(m.getTextContent())),
                "the replaced summary draft must not be memorized");
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .anyMatch(m -> "fallback".equals(m.getTextContent())),
                "the replacement must be memorized");
        assertEquals(
                30,
                gate.inputs.get(0).cumulativeUsage().getInputTokens(),
                "two tool rounds plus the summary round accumulate usage");
    }

    @Test
    @DisplayName("returnDirect exit passes the gate with RETURN_DIRECT")
    void returnDirectExitReportsReturnDirect() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUseResponse("t1", "direct"))));
        GateRecorder gate = new GateRecorder(input -> FinalAnswerDecision.accept());
        ReActAgent agent =
                baseBuilder(model)
                        .toolkit(toolkitWith(new LocalTool("direct", true)))
                        .middleware(gate)
                        .build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals(List.of(FinishReason.RETURN_DIRECT), gate.finishReasons());
        assertEquals("tool-output", result.getTextContent());
        assertEquals(GenerateReason.TOOL_RETURN_DIRECT, result.getGenerateReason());
    }

    @Test
    @DisplayName(
            "returnDirect: replacement swaps the closing message, placeholder tool_result"
                    + " untouched")
    void returnDirectReplacementSwapsClosingMessageOnly() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUseResponse("t1", "direct"))));
        GateRecorder gate =
                new GateRecorder(
                        input -> FinalAnswerDecision.accept(assistantText("sanitized tool")));
        ReActAgent agent =
                baseBuilder(model)
                        .toolkit(toolkitWith(new LocalTool("direct", true)))
                        .middleware(gate)
                        .build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals("sanitized tool", result.getTextContent());
        assertEquals(
                GenerateReason.TOOL_RETURN_DIRECT,
                result.getGenerateReason(),
                "a replacement at the returnDirect exit keeps the exit's reason");
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .anyMatch(m -> "sanitized tool".equals(m.getTextContent())),
                "the replacement must be memorized");
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .noneMatch(m -> "tool-output".equals(m.getTextContent())),
                "the original closing message must not be memorized");
        assertEquals(
                1,
                agent.getAgentState().getContext().stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .filter(
                                b ->
                                        b.getOutput().stream()
                                                .anyMatch(
                                                        t ->
                                                                t instanceof TextBlock tb
                                                                        && tb.getText()
                                                                                .startsWith(
                                                                                        "Tool call"
                                                                                            + " completed")))
                        .count(),
                "the pairing-slot placeholder must stay untouched");
    }

    @Test
    @DisplayName("returnDirect: retry re-enters reasoning and the model sees the closing candidate")
    void returnDirectRetryReEntersReasoning() {
        GeneratorModel model =
                new GeneratorModel(
                        messages ->
                                messages.size() == 1
                                        ? Flux.just(toolUseResponse("t1", "direct"))
                                        : Flux.just(textResponse("regenerated")));
        GateRecorder gate =
                new GateRecorder(
                        input ->
                                "tool-output".equals(input.candidate().getTextContent())
                                        ? FinalAnswerDecision.retry(reminder("rewrite please"))
                                        : FinalAnswerDecision.accept());
        ReActAgent agent =
                baseBuilder(model)
                        .toolkit(toolkitWith(new LocalTool("direct", true)))
                        .middleware(gate)
                        .build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals("regenerated", result.getTextContent());
        assertEquals(2, model.rounds.size());
        assertEquals(
                List.of(FinishReason.RETURN_DIRECT, FinishReason.FINAL_ANSWER),
                gate.finishReasons());
        assertTrue(
                model.rounds.get(1).stream()
                        .anyMatch(m -> "tool-output".equals(m.getTextContent())),
                "the closing candidate must be replayed to the model");
        assertTrue(
                model.rounds.get(1).stream()
                        .anyMatch(m -> "rewrite please".equals(m.getTextContent())),
                "the injected reminder must be replayed to the model");
    }

    // ==== Interrupt-like exits never pass the gate ====

    @Test
    @DisplayName("permission HITL suspension does not pass the gate")
    void hitlSuspensionSkipsGate() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUseResponse("t1", "guarded"))));
        GateRecorder gate = new GateRecorder(input -> FinalAnswerDecision.accept());
        ReActAgent agent =
                baseBuilder(model)
                        .toolkit(toolkitWith(new AskingTool("guarded")))
                        .middleware(gate)
                        .build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals(GenerateReason.PERMISSION_ASKING, result.getGenerateReason());
        assertTrue(gate.inputs.isEmpty(), "a suspension is not a final answer");
    }

    // ==== Interaction with maxIters and structured output ====

    @Test
    @DisplayName("retry re-entry ignores maxIters")
    void retryReEntryIgnoresMaxIters() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(textResponse("v1")),
                                () -> Flux.just(textResponse("v2"))));
        GateRecorder gate = GateRecorder.rejecting("v1", 1);
        ReActAgent agent = baseBuilder(model).maxIters(1).middleware(gate).build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals("v2", result.getTextContent());
        assertEquals(2, model.callCount(), "the retry round must not be cut short by maxIters");
    }

    @Test
    @DisplayName("gate fires only after the structured-output forced retries settle")
    void gateFiresAfterStructuredOutputForcedRetries() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(textResponse("r1")),
                                () -> Flux.just(textResponse("r2")),
                                () -> Flux.just(textResponse("r3")),
                                () -> Flux.just(textResponse("final text"))));
        GateRecorder gate = new GateRecorder(input -> FinalAnswerDecision.accept());
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        Msg result =
                agent.call(List.of(new UserMessage("hi")), WeatherResponse.class).block(TIMEOUT);

        assertEquals("final text", result.getTextContent());
        assertEquals(4, model.callCount(), "1 initial + 3 forced generate_response retries");
        assertEquals(List.of(0), gate.retryCounts(), "gate consulted exactly once, at the end");
        assertEquals(List.of(FinishReason.FINAL_ANSWER), gate.finishReasons());
        // Every forced-retry round's refusal stays in memory, like any finished round.
        for (String refusal : List.of("r1", "r2", "r3")) {
            assertTrue(
                    agent.getAgentState().getContext().stream()
                            .anyMatch(m -> refusal.equals(m.getTextContent())),
                    "refusal round '" + refusal + "' must be memorized");
        }
    }

    // ==== Chain semantics ====

    @Test
    @DisplayName("transforms compose: each gate receives its predecessor's output")
    void transformsComposeThroughTheChain() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(textResponse("raw draft"))));
        GateRecorder sanitizer = GateRecorder.transforming(text -> "sanitized<" + text + ">");
        GateRecorder signer = GateRecorder.transforming(text -> text + "<signed>");
        ReActAgent agent =
                baseBuilder(model)
                        .middlewares(List.of(sanitizer.withOrder(5), signer.withOrder(1)))
                        .build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals("sanitized<raw draft><signed>", result.getTextContent());
        assertEquals(List.of("raw draft"), sanitizer.candidates());
        assertEquals(
                List.of("sanitized<raw draft>"),
                signer.candidates(),
                "the tail gate sees the head gate's replacement");
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .noneMatch(m -> "raw draft".equals(m.getTextContent())),
                "the untransformed draft must not be memorized");
    }

    @Test
    @DisplayName("vetoes do not short-circuit: one round, all feedback merged in chain order")
    void vetoesMergeFeedbackInOneRound() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(textResponse("draft")),
                                () -> Flux.just(textResponse("fixed"))));
        GateRecorder head =
                new GateRecorder(
                        input ->
                                "draft".equals(input.candidate().getTextContent())
                                        ? FinalAnswerDecision.retry(reminder("fix-A"))
                                        : FinalAnswerDecision.accept());
        GateRecorder tail =
                new GateRecorder(
                        input ->
                                "draft".equals(input.candidate().getTextContent())
                                        ? FinalAnswerDecision.retry(reminder("fix-B"))
                                        : FinalAnswerDecision.accept());
        ReActAgent agent =
                baseBuilder(model)
                        .middlewares(List.of(head.withOrder(5), tail.withOrder(1)))
                        .build();

        List<AgentEvent> events =
                agent.streamEvents(List.of(new UserMessage("hi"))).collectList().block(TIMEOUT);

        assertNotNull(events);
        Msg result =
                events.stream()
                        .filter(e -> e instanceof AgentResultEvent)
                        .map(e -> ((AgentResultEvent) e).getResult())
                        .findFirst()
                        .orElse(null);
        assertNotNull(result);
        assertEquals("fixed", result.getTextContent());
        assertEquals(1, head.retryCounts().stream().filter(c -> c > 0).count());
        assertEquals(
                1,
                events.stream()
                        .filter(
                                e ->
                                        e instanceof CustomEvent ce
                                                && "final_answer.retry".equals(ce.getName()))
                        .count(),
                "one round with two vetoes counts as a single retry");
        List<Msg> reminders =
                agent.getAgentState().getContext().stream()
                        .filter(
                                m ->
                                        m.getRole() == MsgRole.USER
                                                && m.getTextContent() != null
                                                && m.getTextContent().startsWith("fix-"))
                        .toList();
        assertEquals(
                List.of("fix-A", "fix-B"),
                reminders.stream().map(Msg::getTextContent).toList(),
                "both gates' feedback enters memory in chain order in the same round");
    }

    @Test
    @DisplayName(
            "dead round: a veto after a transform discards the transform and retries the original")
    void deadRoundDiscardsTransformAndRetriesOriginal() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(textResponse("bad draft")),
                                () -> Flux.just(textResponse("good draft"))));
        GateRecorder sanitizer = GateRecorder.transforming(text -> "sanitized<" + text + ">");
        GateRecorder checker =
                new GateRecorder(
                        input ->
                                input.retryCount() == 0
                                        ? FinalAnswerDecision.retry(reminder("rewrite"))
                                        : FinalAnswerDecision.accept());
        ReActAgent agent =
                baseBuilder(model)
                        .middlewares(List.of(sanitizer.withOrder(5), checker.withOrder(1)))
                        .build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals("sanitized<good draft>", result.getTextContent());
        assertEquals(
                List.of("sanitized<bad draft>", "sanitized<good draft>"),
                checker.candidates(),
                "the veto gate judges the transformed working draft");
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .anyMatch(m -> "bad draft".equals(m.getTextContent())),
                "the retry writes the model's original draft to memory");
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .noneMatch(m -> "sanitized<bad draft>".equals(m.getTextContent())),
                "the dead round's transform must not be memorized");
        assertEquals(2, model.callCount(), "one retry round only");
    }

    @Test
    @DisplayName("ordering: veto-first judges the model's original words")
    void vetoFirstJudgesTheOriginal() {
        ScriptedModel model = new ScriptedModel(List.of(() -> Flux.just(textResponse("raw"))));
        GateRecorder checker = new GateRecorder(input -> FinalAnswerDecision.accept());
        GateRecorder sanitizer = GateRecorder.transforming(text -> "sanitized<" + text + ">");
        ReActAgent agent =
                baseBuilder(model)
                        .middlewares(List.of(checker.withOrder(5), sanitizer.withOrder(1)))
                        .build();

        agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals(List.of("raw"), checker.candidates(), "the head veto sees the original");
    }

    // ==== Fuse ====

    @Test
    @DisplayName("the 11th veto trips the fuse: the call fails closed with nothing delivered")
    void eleventhVetoFailsClosed() {
        List<Supplier<Flux<ChatResponse>>> scripts = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            scripts.add(() -> Flux.just(textResponse("draft")));
        }
        ScriptedModel model = new ScriptedModel(scripts);
        GateRecorder gate = new GateRecorder(input -> FinalAnswerDecision.retry(reminder("more")));
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT));
        assertTrue(error.getMessage().contains("retry limit"));
        assertEquals(11, gate.inputs.size(), "10 honored retries, then the 11th veto trips");
        assertEquals(10, gate.inputs.get(10).retryCount());
        assertEquals(11, model.callCount(), "no 12th round after the fuse");
    }

    @Test
    @DisplayName("fused call streams zero text and no result event")
    void fusedCallStreamsZeroText() {
        List<Supplier<Flux<ChatResponse>>> scripts = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            scripts.add(() -> Flux.just(textResponse("draft")));
        }
        ScriptedModel model = new ScriptedModel(scripts);
        GateRecorder gate = new GateRecorder(input -> FinalAnswerDecision.retry(reminder("more")));
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        List<AgentEvent> events = new ArrayList<>();
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                agent.streamEvents(List.of(new UserMessage("hi")))
                                        .doOnNext(events::add)
                                        .collectList()
                                        .block(TIMEOUT));
        assertTrue(error.getMessage().contains("retry limit"));
        assertTrue(textDeltas(events).isEmpty(), "no uncertified text may ever leave");
        assertTrue(thinkingDeltas(events).isEmpty());
        assertFalse(
                events.stream().anyMatch(e -> e instanceof AgentResultEvent),
                "a fused call has no result event");
    }

    // ==== Certified delivery over the stream ====

    @Test
    @DisplayName("certified delivery: zero live text, then reviewing → certified burst → result")
    void certifiedDeliveryWithholdsLiveText() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(thinkingTextResponse("pondering", "the answer"))));
        GateRecorder gate = new GateRecorder(input -> FinalAnswerDecision.accept());
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        List<AgentEvent> events =
                agent.streamEvents(List.of(new UserMessage("hi"))).collectList().block(TIMEOUT);

        assertNotNull(events);
        int reviewingIdx = indexOfCustom(events, "final_answer.reviewing");
        int endIdx = indexOf(events, ModelCallEndEvent.class);
        int textStartIdx = indexOf(events, TextBlockStartEvent.class);
        int thinkingIdx = indexOf(events, ThinkingBlockDeltaEvent.class);
        int textIdx = indexOf(events, TextBlockDeltaEvent.class);
        int resultIdx = indexOf(events, AgentResultEvent.class);
        assertTrue(reviewingIdx >= 0, "a reviewing placeholder must precede the verdict");
        assertTrue(endIdx < reviewingIdx, "ModelCallEnd still fires live");
        assertTrue(reviewingIdx < thinkingIdx, "certified thinking projects after reviewing");
        assertTrue(thinkingIdx < textIdx, "certified thinking precedes certified text");
        assertTrue(textIdx < resultIdx, "certified text precedes the result event");
        assertTrue(textStartIdx > reviewingIdx, "no live text may precede the verdict");
        // Four-way consistency: stream thinking/text ≡ result ≡ memory tail.
        Msg result = ((AgentResultEvent) events.get(resultIdx)).getResult();
        assertEquals(List.of("pondering"), thinkingDeltas(events));
        assertEquals(List.of("the answer"), textDeltas(events));
        assertEquals("the answer", result.getTextContent());
        assertEquals(
                "the answer",
                agent.getAgentState().getContext().stream()
                        .filter(m -> m.getRole() == MsgRole.ASSISTANT)
                        .reduce((first, second) -> second)
                        .map(Msg::getTextContent)
                        .orElse(null));
    }

    @Test
    @DisplayName("certified thinking projects the accepted message's thinking, not the original")
    void certifiedThinkingProjectsAcceptedContent() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(thinkingTextResponse("secret thoughts", "draft"))));
        GateRecorder gate =
                new GateRecorder(input -> FinalAnswerDecision.accept(assistantText("clean")));
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        List<AgentEvent> events =
                agent.streamEvents(List.of(new UserMessage("hi"))).collectList().block(TIMEOUT);

        assertNotNull(events);
        List<String> thinking = thinkingDeltas(events);
        List<String> text = textDeltas(events);
        assertTrue(
                thinking.stream().noneMatch(t -> t.contains("secret")),
                "the original thinking must never leave");
        assertTrue(text.stream().noneMatch(t -> t.contains("draft")));
        assertTrue(text.contains("clean"), "the certified text projects instead");
    }

    @Test
    @DisplayName("reject round streams no text or thinking, only the retry marker")
    void rejectRoundStreamsNoText() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(thinkingTextResponse("musing", "draft")),
                                () -> Flux.just(textResponse("fixed"))));
        GateRecorder gate = GateRecorder.rejecting("draft", 1);
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        List<AgentEvent> events =
                agent.streamEvents(List.of(new UserMessage("hi"))).collectList().block(TIMEOUT);

        assertNotNull(events);
        assertEquals(
                List.of("fixed"),
                textDeltas(events),
                "only the certified second round reaches the stream");
        assertTrue(thinkingDeltas(events).isEmpty(), "reject-round thinking stays internal");
        CustomEvent retryEvent =
                (CustomEvent) events.get(indexOfCustom(events, "final_answer.retry"));
        assertEquals(1, retryEvent.getValue().get("retryCount"));
        assertEquals("FINAL_ANSWER", retryEvent.getValue().get("finishReason"));
        assertFalse(
                retryEvent.getValue().containsKey("text"),
                "the retry telemetry must not carry candidate text");
    }

    @Test
    @DisplayName("returnDirect closing text publishes only after the gate, gate or not")
    void returnDirectClosingTextPublishesPostGate() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUseResponse("t1", "direct"))));
        GateRecorder gate =
                new GateRecorder(
                        input -> FinalAnswerDecision.accept(assistantText("certified tool")));
        ReActAgent agent =
                baseBuilder(model)
                        .toolkit(toolkitWith(new LocalTool("direct", true)))
                        .middleware(gate)
                        .build();

        List<AgentEvent> events =
                agent.streamEvents(List.of(new UserMessage("hi"))).collectList().block(TIMEOUT);

        assertNotNull(events);
        int reviewingIdx = indexOfCustom(events, "final_answer.reviewing");
        int textIdx = indexOf(events, TextBlockDeltaEvent.class);
        int resultIdx = indexOf(events, AgentResultEvent.class);
        assertTrue(reviewingIdx >= 0 && reviewingIdx < textIdx, "closing text waits for the gate");
        assertEquals(List.of("certified tool"), textDeltas(events));
        assertTrue(textIdx < resultIdx);
        TextBlockDeltaEvent delta = (TextBlockDeltaEvent) events.get(textIdx);
        assertEquals(
                GenerateReason.TOOL_RETURN_DIRECT.name(),
                delta.getMetadata().get(AgentEvent.METADATA_GENERATE_REASON),
                "the projection keeps the TOOL_RETURN_DIRECT audit marker");
    }

    @Test
    @DisplayName("without a gate the returnDirect closing text streams exactly as before")
    void returnDirectWithoutGateStreamsAsBefore() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUseResponse("t1", "direct"))));
        ReActAgent agent =
                baseBuilder(model).toolkit(toolkitWith(new LocalTool("direct", true))).build();

        List<AgentEvent> events =
                agent.streamEvents(List.of(new UserMessage("hi"))).collectList().block(TIMEOUT);

        assertNotNull(events);
        assertEquals(List.of("tool-output"), textDeltas(events));
    }

    // ==== Usage accumulation ====

    @Test
    @DisplayName("cumulativeUsage sums every attempt and retryCount advances")
    void cumulativeUsageSumsAcrossRetries() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(textResponse("draft 1")),
                                () -> Flux.just(textResponse("draft 2")),
                                () -> Flux.just(textResponse("draft 3"))));
        GateRecorder gate = GateRecorder.rejecting("draft", 2);
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals(List.of(0, 1, 2), gate.retryCounts());
        assertEquals(10, gate.inputs.get(0).cumulativeUsage().getInputTokens());
        assertEquals(20, gate.inputs.get(1).cumulativeUsage().getInputTokens());
        assertEquals(30, gate.inputs.get(2).cumulativeUsage().getInputTokens());
    }

    // ==== Concurrency isolation ====

    @Test
    @DisplayName("parallel calls on one agent keep their retry budgets separate")
    void parallelCallsIsolateRetryBudgets() throws Exception {
        GeneratorModel model =
                new GeneratorModel(
                        messages ->
                                Flux.just(
                                        textResponse(
                                                messages.stream()
                                                                .map(Msg::getTextContent)
                                                                .anyMatch(
                                                                        t ->
                                                                                t != null
                                                                                        && t
                                                                                                .startsWith(
                                                                                                        "Please"
                                                                                                            + " fix"))
                                                        ? "good"
                                                        : "draft")));
        GateRecorder gate =
                new GateRecorder(
                        input ->
                                "draft".equals(input.candidate().getTextContent())
                                        ? FinalAnswerDecision.retry(reminder("Please fix it."))
                                        : FinalAnswerDecision.accept());
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        CompletableFuture<Msg> first =
                agent.call(List.of(new UserMessage("a")), rc("u1", "s1"))
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        CompletableFuture<Msg> second =
                agent.call(List.of(new UserMessage("b")), rc("u2", "s2"))
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        Msg firstResult = first.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        Msg secondResult = second.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertEquals("good", firstResult.getTextContent());
        assertEquals("good", secondResult.getTextContent());
        assertEquals(4, gate.inputs.size(), "two consultations per call");
        assertEquals(2, gate.inputs.stream().filter(i -> i.retryCount() == 0).count());
        // Each call saw its own retry counter go 0 → 1; no cross-call leakage.
        assertEquals(2, gate.inputs.stream().filter(i -> i.retryCount() == 1).count());
    }

    @Test
    @DisplayName("one shared gate instance serves two different agents concurrently in isolation")
    void sharedGateInstanceServesTwoAgentsConcurrently() throws Exception {
        // Middleware-level singleton: the same gate instance, registered on two different
        // agents (at different chain positions), consulted by concurrent calls — the
        // hook carries no agent, so nothing can leak across agents by construction.
        GeneratorModel modelA =
                new GeneratorModel(
                        messages ->
                                Flux.just(
                                        textResponse(
                                                messages.stream()
                                                                .map(Msg::getTextContent)
                                                                .anyMatch(
                                                                        t ->
                                                                                t != null
                                                                                        && t
                                                                                                .startsWith(
                                                                                                        "Please"
                                                                                                            + " fix"))
                                                        ? "good"
                                                        : "draft")));
        GeneratorModel modelB =
                new GeneratorModel(
                        messages ->
                                Flux.just(
                                        textResponse(
                                                messages.stream()
                                                                .map(Msg::getTextContent)
                                                                .anyMatch(
                                                                        t ->
                                                                                t != null
                                                                                        && t
                                                                                                .startsWith(
                                                                                                        "Please"
                                                                                                            + " fix"))
                                                        ? "good"
                                                        : "draft")));
        GateRecorder gate =
                new GateRecorder(
                        input ->
                                "draft".equals(input.candidate().getTextContent())
                                        ? FinalAnswerDecision.retry(reminder("Please fix it."))
                                        : FinalAnswerDecision.accept());
        ReActAgent agentA = baseBuilder(modelA).middleware(gate).build();
        ReActAgent agentB =
                baseBuilder(modelB).middleware(new MiddlewareBase() {}).middleware(gate).build();

        CompletableFuture<Msg> first =
                agentA.call(List.of(new UserMessage("a")), rc("u1", "s1"))
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        CompletableFuture<Msg> second =
                agentB.call(List.of(new UserMessage("b")), rc("u2", "s2"))
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        Msg firstResult = first.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        Msg secondResult = second.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertEquals("good", firstResult.getTextContent());
        assertEquals("good", secondResult.getTextContent());
        assertEquals(4, gate.inputs.size(), "two consultations per agent's call");
        assertEquals(2, gate.inputs.stream().filter(i -> i.retryCount() == 0).count());
        assertEquals(2, gate.inputs.stream().filter(i -> i.retryCount() == 1).count());
    }

    // ==== Server-tool embedded candidates ====

    @Test
    @DisplayName("candidate with embedded server-tool results replays through retry")
    void serverToolEmbeddedCandidateReplaysThroughRetry() {
        ToolUseBlock serverUse =
                ToolUseBlock.builder()
                        .id("srv1")
                        .name("web_search")
                        .input(Map.of("query", "q"))
                        .metadata(Map.of(ToolUseBlock.METADATA_SERVER_TOOL, true))
                        .build();
        ToolResultBlock serverResult =
                ToolResultBlock.builder()
                        .id("srv1")
                        .name("web_search")
                        .output(List.of(TextBlock.builder().text("search hit").build()))
                        .metadata(Map.of(ToolResultBlock.METADATA_SERVER_TOOL, true))
                        .state(ToolResultState.SUCCESS)
                        .build();
        GeneratorModel model =
                new GeneratorModel(
                        messages ->
                                messages.size() == 1
                                        ? Flux.just(
                                                ChatResponse.builder()
                                                        .content(
                                                                List.of(
                                                                        serverUse,
                                                                        serverResult,
                                                                        TextBlock.builder()
                                                                                .text("draft")
                                                                                .build()))
                                                        .usage(new ChatUsage(10, 5, 1.0))
                                                        .build())
                                        : Flux.just(textResponse("good")));
        GateRecorder gate = GateRecorder.rejecting("draft", 1);
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals("good", result.getTextContent());
        assertEquals(List.of(0, 1), gate.retryCounts());
        Msg replayed =
                model.rounds.get(1).stream()
                        .filter(m -> m.getContentBlocks(ToolUseBlock.class).size() == 1)
                        .findFirst()
                        .orElse(null);
        assertNotNull(replayed, "the embedded candidate must be replayed to the model");
        assertEquals(1, replayed.getContentBlocks(ToolResultBlock.class).size());
        assertTrue(
                replayed.getContentBlocks(ToolUseBlock.class).get(0).isServerTool(),
                "the replayed candidate keeps its server-tool call");
        assertEquals(
                "draft",
                replayed.getTextContent(),
                "the replayed candidate keeps its text block alongside the tool blocks");
    }

    // ==== Loop shape and edge cases ====

    @Test
    @DisplayName("summary-exit retry that produces tool calls re-hits maxIters and re-summarizes")
    void summaryRetryLoopShapeReSummarizes() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("t1", "search")),
                                () -> Flux.just(toolUseResponse("t2", "search")),
                                () -> Flux.just(textResponse("bad summary")),
                                () -> Flux.just(toolUseResponse("t3", "search")),
                                () -> Flux.just(textResponse("final summary"))));
        GateRecorder gate = GateRecorder.rejecting("bad", 1);
        ReActAgent agent =
                baseBuilder(model)
                        .toolkit(toolkitWith(new LocalTool("search", false)))
                        .maxIters(2)
                        .middleware(gate)
                        .build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals("final summary", result.getTextContent());
        assertEquals(5, model.callCount());
        assertEquals(
                List.of(FinishReason.MAX_ITERATIONS, FinishReason.MAX_ITERATIONS),
                gate.finishReasons(),
                "the re-entered round acted, hit maxIters again, and passed the gate as a new"
                        + " summary");
        assertEquals(List.of(0, 1), gate.retryCounts());
    }

    @Test
    @DisplayName("a gate middleware error fails the call without memorizing the candidate")
    void gateErrorFailsTheCall() {
        ScriptedModel model = new ScriptedModel(List.of(() -> Flux.just(textResponse("draft"))));
        IllegalStateException boom = new IllegalStateException("checker down");
        GateRecorder gate =
                new GateRecorder(
                        input -> {
                            throw boom;
                        });
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT));
        assertEquals("checker down", error.getMessage());
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .noneMatch(m -> "draft".equals(m.getTextContent())),
                "the candidate must not be memorized when the gate fails");
    }

    @Test
    @DisplayName("a gate middleware error at the MAX_ITERATIONS exit fails the call")
    void gateErrorAtSummaryExitFailsCall() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("t1", "search")),
                                () -> Flux.just(textResponse("summary draft"))));
        GateRecorder gate =
                new GateRecorder(
                        input -> {
                            throw new IllegalStateException("checker down");
                        });
        ReActAgent agent =
                baseBuilder(model)
                        .toolkit(toolkitWith(new LocalTool("search", false)))
                        .maxIters(1)
                        .middleware(gate)
                        .build();

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT));
        assertEquals("checker down", error.getMessage());
        assertEquals(
                List.of(FinishReason.MAX_ITERATIONS),
                gate.finishReasons(),
                "the gate must have been consulted on the summary candidate");
    }

    @Test
    @DisplayName(
            "a model failure in a summary-exit retry round fails the call, not an error summary")
    void modelErrorInSummaryRetryRoundFailsCall() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("t1", "search")),
                                () -> Flux.just(textResponse("bad summary")),
                                () -> Flux.error(new IllegalStateException("model down"))));
        GateRecorder gate = GateRecorder.rejecting("bad", 1);
        ReActAgent agent =
                baseBuilder(model)
                        .toolkit(toolkitWith(new LocalTool("search", false)))
                        .maxIters(1)
                        .middleware(gate)
                        .build();

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT));
        assertEquals("model down", error.getMessage());
    }

    @Test
    @DisplayName("retry with an empty list re-enters reasoning without an injected message")
    void emptyRetryListReEntersWithoutReminder() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(textResponse("draft")),
                                () -> Flux.just(textResponse("fixed"))));
        GateRecorder gate =
                new GateRecorder(
                        input ->
                                "draft".equals(input.candidate().getTextContent())
                                        ? FinalAnswerDecision.retry(List.of())
                                        : FinalAnswerDecision.accept());
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals("fixed", result.getTextContent());
        assertEquals(2, model.callCount());
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .noneMatch(
                                m ->
                                        m.getRole() == MsgRole.USER
                                                && !"hi".equals(m.getTextContent())),
                "no reminder is injected on an empty retry");
    }

    // ==== Legacy hook interplay ====

    @Test
    @DisplayName("legacy PostReasoning rewrite reaches the gate and memory")
    void legacyHookRewriteReachesGate() {
        ScriptedModel model = new ScriptedModel(List.of(() -> Flux.just(textResponse("draft"))));
        GateRecorder gate = new GateRecorder(input -> FinalAnswerDecision.accept());
        ReActAgent agent =
                baseBuilder(model)
                        .middleware(gate)
                        .hook(
                                new Hook() {
                                    @Override
                                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                                        if (event instanceof PostReasoningEvent pre
                                                && pre.getReasoningMessage() != null
                                                && "draft"
                                                        .equals(
                                                                pre.getReasoningMessage()
                                                                        .getTextContent())) {
                                            pre.setReasoningMessage(assistantText("rewritten"));
                                        }
                                        return Mono.just(event);
                                    }
                                })
                        .build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals("rewritten", result.getTextContent());
        assertEquals("rewritten", gate.inputs.get(0).candidate().getTextContent());
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .noneMatch(m -> "draft".equals(m.getTextContent())),
                "the pre-gate rewrite wins over the raw draft");
    }

    @Test
    @DisplayName("legacy gotoReasoning takes precedence over the gate on a terminal round")
    void legacyGotoReasoningTakesPrecedenceOverGate() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(textResponse("draft")),
                                () -> Flux.just(textResponse("fixed"))));
        GateRecorder gate = new GateRecorder(input -> FinalAnswerDecision.accept());
        ReActAgent agent =
                baseBuilder(model)
                        .middleware(gate)
                        .hook(
                                new Hook() {
                                    @Override
                                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                                        if (event instanceof PostReasoningEvent pre
                                                && "draft"
                                                        .equals(
                                                                pre.getReasoningMessage()
                                                                        .getTextContent())) {
                                            pre.gotoReasoning(reminder("legacy reroute"));
                                        }
                                        return Mono.just(event);
                                    }
                                })
                        .build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals("fixed", result.getTextContent());
        assertEquals(2, model.callCount());
        assertEquals(
                1,
                gate.inputs.size(),
                "the goto branch bypasses the gate for that candidate; only the re-entry's"
                        + " final answer passes it");
        assertEquals("fixed", gate.inputs.get(0).candidate().getTextContent());
    }

    // ==== Helpers ====

    private static int indexOf(List<AgentEvent> events, Class<?> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfCustom(List<AgentEvent> events, String name) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof CustomEvent ce && name.equals(ce.getName())) {
                return i;
            }
        }
        return -1;
    }

    private static Toolkit toolkitWith(ToolBase... tools) {
        Toolkit tk = new Toolkit();
        for (ToolBase t : tools) {
            tk.registerAgentTool(t);
        }
        return tk;
    }

    private static ReActAgent.Builder baseBuilder(ChatModelBase model) {
        return ReActAgent.builder().name("asst").model(model).toolkit(new Toolkit()).maxIters(10);
    }

    // ==== Regression anchors for the audit-fix waves ====

    @Test
    @DisplayName("fallback structured-output completion passes the gate (tool-success path)")
    void fallbackStructuredCompletionPassesTheGate() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                soToolResponse(
                                                        "so1",
                                                        Map.of(
                                                                "response",
                                                                Map.of(
                                                                        "location", "Paris",
                                                                        "temperature", "21C")))),
                                () -> Flux.just(textResponse("fixed answer"))));
        GateRecorder gate =
                new GateRecorder(
                        input ->
                                input.retryCount() == 0
                                        ? FinalAnswerDecision.retry(reminder("Please fix it."))
                                        : FinalAnswerDecision.accept());
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        Msg result =
                agent.call(List.of(new UserMessage("hi")), WeatherResponse.class).block(TIMEOUT);

        assertNotNull(result);
        assertEquals(
                "fixed answer",
                result.getTextContent(),
                "consultations="
                        + gate.inputs.stream()
                                .map(
                                        i ->
                                                i.retryCount()
                                                        + ":"
                                                        + i.candidate().getTextContent()
                                                        + ":"
                                                        + i.finishReason())
                                .toList());
        assertEquals(2, gate.inputs.size(), "completion + retry-round consultations");
        assertEquals(List.of(0, 1), gate.retryCounts(), "the completion path consulted the gate");
        assertEquals(FinishReason.FINAL_ANSWER, gate.finishReasons().get(0));
    }

    @Test
    @DisplayName(
            "native structured output: wrap failure falls back through the gate, no early landing")
    void nativeWrapFailureFallsBackThroughTheGate() {
        NativeScriptedModel model =
                new NativeScriptedModel(
                        List.of(
                                () -> Flux.just(textResponse("not json")),
                                () ->
                                        Flux.just(
                                                soToolResponse(
                                                        "so1",
                                                        Map.of(
                                                                "response",
                                                                Map.of(
                                                                        "location", "Paris",
                                                                        "temperature", "21C"))))));
        GateRecorder gate = new GateRecorder(input -> FinalAnswerDecision.accept());
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        Msg result =
                agent.call(List.of(new UserMessage("hi")), WeatherResponse.class).block(TIMEOUT);

        assertNotNull(result, "the fallback re-run delivers a gated answer");
        assertEquals(2, gate.inputs.size(), "native attempt + fallback completion consultations");
        assertEquals(
                "not json",
                gate.inputs.get(0).candidate().getTextContent(),
                "the gate sees the raw native candidate before the wrap fails");
    }

    @Test
    @DisplayName("PostSummary rewrites BEFORE the verdict: the gate certifies the hook's summary")
    void postSummaryRewritePassesTheGate() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("t1", "search")),
                                () -> Flux.just(toolUseResponse("t2", "search")),
                                () -> Flux.just(textResponse("summary draft")),
                                () -> Flux.just(textResponse("summary draft 2"))));
        Hook rewriteHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostSummaryEvent post) {
                            post.setSummaryMessage(
                                    assistantText(
                                            "hooked: "
                                                    + post.getSummaryMessage().getTextContent()));
                        }
                        return Mono.just(event);
                    }
                };
        GateRecorder gate =
                new GateRecorder(
                        input ->
                                input.candidate().getTextContent().startsWith("hooked:")
                                        ? FinalAnswerDecision.retry(reminder("Please fix it."))
                                        : FinalAnswerDecision.accept());
        ReActAgent agent =
                baseBuilder(model)
                        .toolkit(toolkitWith(new LocalTool("search", false)))
                        .maxIters(2)
                        .middleware(gate)
                        .hook(rewriteHook)
                        .build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertNotNull(result);
        assertEquals(2, gate.inputs.size());
        assertTrue(
                gate.inputs.get(0).candidate().getTextContent().startsWith("hooked:"),
                "the gate's first candidate is the PostSummary rewrite, not the raw summary");
        assertEquals(FinishReason.MAX_ITERATIONS, gate.finishReasons().get(0));
        assertEquals("summary draft 2", result.getTextContent());
    }

    @Test
    @DisplayName("an interrupt issued while a gate deliberates is honored before anything ships")
    void interruptDuringConsultationIsHonoredBeforeDelivery() throws Exception {
        CountDownLatch consulted = new CountDownLatch(1);
        MiddlewareBase slowGate =
                new MiddlewareBase() {
                    @Override
                    public Mono<FinalAnswerDecision> onFinalAnswer(
                            RuntimeContext ctx, FinalAnswerInput input) {
                        consulted.countDown();
                        return Mono.just(FinalAnswerDecision.accept())
                                .delayElement(Duration.ofMillis(400));
                    }
                };
        ScriptedModel model = new ScriptedModel(List.of(() -> Flux.just(textResponse("secret"))));
        ReActAgent agent = baseBuilder(model).middleware(slowGate).build();

        RuntimeContext ctx = rc("u1", "s1");
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> terminal = new AtomicReference<>();
        CountDownLatch terminated = new CountDownLatch(1);
        agent.streamEvents(List.of(new UserMessage("hi")), ctx)
                .subscribe(
                        events::add,
                        error -> {
                            terminal.set(error);
                            terminated.countDown();
                        },
                        terminated::countDown);
        assertTrue(consulted.await(2, TimeUnit.SECONDS), "gate consulted");
        agent.interrupt(ctx);

        assertTrue(
                terminated.await(2, TimeUnit.SECONDS),
                "the interrupted stream terminates instead of hanging on the gate");
        // The security property: whatever way the interrupt is honored (upstream
        // cancellation or the engine's verdict-boundary check), nothing ships.
        assertEquals(
                0,
                liveAnswerContentEvents(events),
                "nothing ships: the interrupt beats the delayed accept");
        if (terminal.get() != null) {
            assertTrue(
                    terminal.get() instanceof InterruptedException,
                    "when the stream errors, it errors as an interrupt: " + terminal.get());
        }
    }

    // ==== Coverage anchors (audit findings) ====

    @Test
    @DisplayName("cancelling mid-consultation leaks no answer content of any event type")
    void cancellationMidConsultationLeaksNothing() throws Exception {
        ScriptedModel model = new ScriptedModel(List.of(() -> Flux.just(textResponse("secret"))));
        ReActAgent agent =
                baseBuilder(model)
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Mono<FinalAnswerDecision> onFinalAnswer(
                                            RuntimeContext ctx, FinalAnswerInput input) {
                                        return Mono.never();
                                    }
                                })
                        .build();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch reviewing = new CountDownLatch(1);
        reactor.core.Disposable subscription =
                agent.streamEvents(List.of(new UserMessage("hi")))
                        .doOnNext(
                                e -> {
                                    events.add(e);
                                    if (e instanceof CustomEvent ce
                                            && "final_answer.reviewing".equals(ce.getName())) {
                                        reviewing.countDown();
                                    }
                                })
                        .subscribe();
        assertTrue(reviewing.await(2, TimeUnit.SECONDS), "consultation started");
        subscription.dispose();
        assertEquals(
                0,
                liveAnswerContentEvents(events),
                "dropped-not-buffered: zero Text/Thinking events of all six types after cancel");
    }

    @Test
    @DisplayName("maxIters exit over the stream: certified-only delivery with documented ordering")
    void maxItersExitCertifiedDeliveryOverStream() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("t1", "search")),
                                () -> Flux.just(toolUseResponse("t2", "search")),
                                () -> Flux.just(textResponse("summary draft"))));
        GateRecorder gate =
                new GateRecorder(input -> FinalAnswerDecision.accept(assistantText("fallback")));
        ReActAgent agent =
                baseBuilder(model)
                        .toolkit(toolkitWith(new LocalTool("search", false)))
                        .maxIters(2)
                        .middleware(gate)
                        .build();

        List<AgentEvent> events =
                agent.streamEvents(List.of(new UserMessage("hi"))).collectList().block(TIMEOUT);

        assertNotNull(events);
        assertEquals(List.of("fallback"), textDeltas(events), "certified replacement only");
        assertTrue(
                events.stream().noneMatch(e -> e.toString().contains("summary draft")),
                "the live summary never reaches the public stream");
        assertTrue(
                events.stream().anyMatch(e -> e instanceof ExceedMaxItersEvent),
                "max-iters marker still streams");
        int modelCallEnd = -1;
        int reviewing = -1;
        int firstTextStart = -1;
        for (int i = 0; i < events.size(); i++) {
            AgentEvent e = events.get(i);
            if (e instanceof ModelCallEndEvent && modelCallEnd < 0) modelCallEnd = i;
            if (e instanceof CustomEvent ce
                    && "final_answer.reviewing".equals(ce.getName())
                    && reviewing < 0) reviewing = i;
            if (e instanceof TextBlockStartEvent && firstTextStart < 0) firstTextStart = i;
        }
        assertTrue(modelCallEnd >= 0 && reviewing >= 0 && firstTextStart >= 0);
        assertTrue(
                modelCallEnd < reviewing && reviewing < firstTextStart,
                "ModelCallEnd → reviewing → certified text ordering");
    }

    @Test
    @DisplayName("rejected drafts reach the session recording and no public channel")
    void rejectedDraftReachesSessionRecordingOnly() {
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        SessionLog log = store.open(new SessionKey("u1", "asst", "s1"), RuntimeContext.empty());
        SessionRecorder recorder = new SessionRecorder(log, "turn-1");
        RuntimeContext ctx = rc("u1", "s1");
        ctx.put(SessionRecorder.CONTEXT_KEY, recorder);
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(textResponse("raw draft")),
                                () -> Flux.just(textResponse("clean"))));
        GateRecorder gate =
                new GateRecorder(
                        input ->
                                "raw draft".equals(input.candidate().getTextContent())
                                        ? FinalAnswerDecision.retry(reminder("Please fix it."))
                                        : FinalAnswerDecision.accept());
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        Msg result = agent.call(List.of(new UserMessage("hi")), ctx).block(TIMEOUT);

        assertNotNull(result);
        assertEquals("clean", result.getTextContent());
        StringBuilder recorded = new StringBuilder();
        for (var event : log.scan(0, log.head().seq())) {
            recorded.append(event.toString());
        }
        assertTrue(
                recorded.toString().contains("final_answer.rejected"),
                "the rejected original is audited to the session recording");
        assertTrue(
                recorded.toString().contains("raw draft"), "the audit carries the rejected text");
    }

    @Test
    @DisplayName("an explicit activePoints() declaration activates the guard with default accept")
    void explicitDeclarationActivatesGuardWithDefaultAccept() {
        ScriptedModel model = new ScriptedModel(List.of(() -> Flux.just(textResponse("plain"))));
        MiddlewareBase allOf =
                new MiddlewareBase() {
                    @Override
                    public Set<MiddlewareBase.ExtensionPoint> activePoints() {
                        return EnumSet.allOf(MiddlewareBase.ExtensionPoint.class);
                    }
                };
        ReActAgent agent = baseBuilder(model).middleware(allOf).build();

        List<AgentEvent> events =
                agent.streamEvents(List.of(new UserMessage("hi"))).collectList().block(TIMEOUT);

        assertNotNull(events);
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        e instanceof CustomEvent ce
                                                && "final_answer.reviewing".equals(ce.getName())),
                "the declaration lights the guard");
        assertEquals(List.of("plain"), textDeltas(events), "default accept delivers as-is");
    }

    @Test
    @DisplayName("a middleware hooking another point never activates the guard")
    void otherHookMiddlewareDoesNotActivateGuard() {
        ScriptedModel model = new ScriptedModel(List.of(() -> Flux.just(textResponse("plain"))));
        MiddlewareBase onAgentOnly =
                new MiddlewareBase() {
                    @Override
                    public Set<MiddlewareBase.ExtensionPoint> activePoints() {
                        return EnumSet.of(MiddlewareBase.ExtensionPoint.ON_AGENT);
                    }

                    @Override
                    public Flux<AgentEvent> onAgent(
                            io.agentscope.core.agent.Agent a,
                            RuntimeContext c,
                            AgentInput in,
                            Function<AgentInput, Flux<AgentEvent>> next) {
                        return next.apply(in);
                    }
                };
        ReActAgent agent = baseBuilder(model).middleware(onAgentOnly).build();

        List<AgentEvent> events =
                agent.streamEvents(List.of(new UserMessage("hi"))).collectList().block(TIMEOUT);

        assertNotNull(events);
        assertTrue(
                events.stream()
                        .noneMatch(
                                e ->
                                        e instanceof CustomEvent ce
                                                && ce.getName().startsWith("final_answer.")),
                "no gate events from an ordinary middleware");
        assertEquals(List.of("plain"), textDeltas(events), "live streaming intact");
    }

    @Test
    @DisplayName("a gate error at the returnDirect exit fails the call")
    void gateErrorAtReturnDirectExitFailsCall() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUseResponse("t1", "direct"))));
        MiddlewareBase bomb =
                new MiddlewareBase() {
                    @Override
                    public Mono<FinalAnswerDecision> onFinalAnswer(
                            RuntimeContext ctx, FinalAnswerInput input) {
                        return Mono.error(new IllegalStateException("gate down"));
                    }
                };
        ReActAgent agent =
                baseBuilder(model)
                        .toolkit(toolkitWith(new LocalTool("direct", true)))
                        .middleware(bomb)
                        .build();

        assertThrows(
                IllegalStateException.class,
                () -> agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT),
                "fail closed at the returnDirect exit too");
    }

    @Test
    @DisplayName("reviewing fires once per consultation, retry re-entry rounds included")
    void reviewingFiresOncePerConsultationIncludingRetryRounds() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(textResponse("draft")),
                                () -> Flux.just(textResponse("good"))));
        GateRecorder gate =
                new GateRecorder(
                        input ->
                                "draft".equals(input.candidate().getTextContent())
                                        ? FinalAnswerDecision.retry(reminder("Please fix it."))
                                        : FinalAnswerDecision.accept());
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        List<AgentEvent> events =
                agent.streamEvents(List.of(new UserMessage("hi"))).collectList().block(TIMEOUT);

        assertNotNull(events);
        long reviewing =
                events.stream()
                        .filter(
                                e ->
                                        e instanceof CustomEvent ce
                                                && "final_answer.reviewing".equals(ce.getName()))
                        .count();
        assertEquals(2, reviewing, "once per consultation round, reject re-entry included");
    }

    @Test
    @DisplayName("gates with equal order() keep registration order")
    void sameOrderGatesKeepRegistrationOrder() {
        ScriptedModel model = new ScriptedModel(List.of(() -> Flux.just(textResponse("draft"))));
        GateRecorder head =
                new GateRecorder(input -> FinalAnswerDecision.accept(assistantText("A-out")));
        GateRecorder tail = new GateRecorder(input -> FinalAnswerDecision.accept());
        ReActAgent agent = baseBuilder(model).middleware(head).middleware(tail).build();

        agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertEquals(
                "A-out",
                tail.inputs.get(0).candidate().getTextContent(),
                "the earlier-registered gate ran first and fed its replacement forward");
    }

    @Test
    @DisplayName("the 10th retry is still honored before the fuse trips")
    void tenthRetryStillHonoredThenAccepts() {
        List<Supplier<Flux<ChatResponse>>> scripts = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            scripts.add(() -> Flux.just(textResponse("draft")));
        }
        ScriptedModel model = new ScriptedModel(scripts);
        GateRecorder gate = GateRecorder.rejecting("draft", 10);
        ReActAgent agent = baseBuilder(model).middleware(gate).build();

        Msg result = agent.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertNotNull(result, "the limit is exactly 10 — the 11th consultation accepts");
        assertEquals(11, gate.inputs.size(), "11 consultations, retryCount 0..10");
        assertEquals(11, model.callCount());
    }

    @Test
    @DisplayName("an accepting gate delivers exactly what a gateless agent delivers (parity)")
    void acceptingGateMatchesGatelessDelivery() {
        ScriptedModel gatelessModel =
                new ScriptedModel(List.of(() -> Flux.just(textResponse("clean answer"))));
        ReActAgent gateless = baseBuilder(gatelessModel).build();
        ScriptedModel gatedModel =
                new ScriptedModel(List.of(() -> Flux.just(textResponse("clean answer"))));
        GateRecorder gate = new GateRecorder(input -> FinalAnswerDecision.accept());
        ReActAgent gated = baseBuilder(gatedModel).middleware(gate).build();

        List<AgentEvent> gatelessEvents =
                gateless.streamEvents(List.of(new UserMessage("hi"))).collectList().block(TIMEOUT);
        Msg gatelessResult = gateless.call(List.of(new UserMessage("hi"))).block(TIMEOUT);
        List<AgentEvent> gatedEvents =
                gated.streamEvents(List.of(new UserMessage("hi"))).collectList().block(TIMEOUT);
        Msg gatedResult = gated.call(List.of(new UserMessage("hi"))).block(TIMEOUT);

        assertNotNull(gatelessResult);
        assertNotNull(gatedResult);
        assertEquals(textDeltas(gatelessEvents), textDeltas(gatedEvents));
        assertEquals(gatelessResult.getTextContent(), gatedResult.getTextContent());
        assertEquals(
                gateless.getAgentState().getContext().stream()
                        .filter(m -> m instanceof AssistantMessage)
                        .reduce((first, second) -> second)
                        .map(Msg::getTextContent)
                        .orElse(null),
                gated.getAgentState().getContext().stream()
                        .filter(m -> m instanceof AssistantMessage)
                        .reduce((first, second) -> second)
                        .map(Msg::getTextContent)
                        .orElse(null),
                "memory tails agree: certified delivery with an accepting gate is live delivery");
    }

    /** Tool-use response calling the fallback structured-output tool with arbitrary input. */
    private static ChatResponse soToolResponse(String toolId, Map<String, Object> input) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(toolId)
                                        .name("generate_response")
                                        .input(input)
                                        .content(JsonUtils.getJsonCodec().toJson(input))
                                        .build()))
                .usage(new ChatUsage(10, 5, 1.0))
                .build();
    }

    /** Counts all six live answer-content event types (Text/Thinking × Start/Delta/End). */
    private static long liveAnswerContentEvents(List<AgentEvent> events) {
        return events.stream()
                .filter(
                        e ->
                                e instanceof TextBlockStartEvent
                                        || e instanceof TextBlockDeltaEvent
                                        || e instanceof TextBlockEndEvent
                                        || e instanceof ThinkingBlockStartEvent
                                        || e instanceof ThinkingBlockDeltaEvent
                                        || e instanceof ThinkingBlockEndEvent)
                .count();
    }

    /** Scripted model that advertises native structured-output support. */
    private static final class NativeScriptedModel extends ChatModelBase {
        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger idx = new AtomicInteger();

        NativeScriptedModel(List<Supplier<Flux<ChatResponse>>> scripts) {
            this.scripts = scripts;
        }

        @Override
        public String getModelName() {
            return "native-scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int i = idx.getAndIncrement();
            return i >= scripts.size()
                    ? Flux.just(textResponse("unexpected"))
                    : scripts.get(i).get();
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return true;
        }
    }

    static class WeatherResponse {
        public String location;
        public String temperature;
    }
}
