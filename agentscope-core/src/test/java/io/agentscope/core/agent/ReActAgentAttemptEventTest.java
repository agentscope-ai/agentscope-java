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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallAttemptEndEvent;
import io.agentscope.core.event.ModelCallAttemptFailedEvent;
import io.agentscope.core.event.ModelCallAttemptNextAction;
import io.agentscope.core.event.ModelCallAttemptRole;
import io.agentscope.core.event.ModelCallAttemptStartEvent;
import io.agentscope.core.event.ModelFallbackActivatedEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.AttemptEventContext;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * End-to-end tests verifying that {@link ReActAgent} emits model call attempt lifecycle events
 * during real agent execution through the {@code streamEvents()} / {@code call()} paths.
 *
 * <p>These tests use a {@link RetryingScriptedModel} that internally calls
 * {@link ModelUtils#applyTimeoutAndRetry} to simulate Provider-layer retry/timeout behavior,
 * matching the production call chain: {@code ReActAgent.modelCallStream()} →
 * {@code injectAttemptTracking()} → {@code model.stream()} → Provider's
 * {@code applyTimeoutAndRetry()} → {@code ModelCallAttemptTracker.wrap()}.
 *
 * <p>Test scenarios:
 * <ol>
 *   <li>Retry then success — verifies MODEL_ATTEMPT_START/FAILED/END events
 *   <li>Fallback activation — verifies MODEL_FALLBACK_ACTIVATED event
 *   <li>Fallback role — verifies FALLBACK role in attempt events
 *   <li>call() path — verifies events emitted through shared buildAgentStream core
 *   <li>No tool replay after fallback — verifies no tool events from failed primary
 *   <li>HITL + fallback coexist — verifies both mechanisms work together
 *   <li>All attempts fail — verifies no assistant message on total failure
 *   <li>AttemptEventContext propagation — verifies context reaches model options
 * </ol>
 */
class ReActAgentAttemptEventTest {

    private static final String PRIMARY_NAME = "test-primary";
    private static final String FALLBACK_NAME = "test-fallback";
    private static final String PROVIDER = "test-provider";

    private static final ExecutionConfig TEST_EXEC_CONFIG =
            ExecutionConfig.builder()
                    .maxAttempts(3)
                    .initialBackoff(Duration.ofMillis(10))
                    .maxBackoff(Duration.ofMillis(50))
                    .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
                    .timeout(Duration.ofSeconds(5))
                    .build();

    // ==================== Test Models ====================

    /**
     * A scripted model that wraps its flux with {@link ModelUtils#applyTimeoutAndRetry} to
     * simulate Provider-layer retry/timeout/tracking behavior. Each call to {@code doStream}
     * advances the script index, and the returned flux is wrapped with the full retry pipeline
     * so that {@link AttemptEventContext} injected by {@link ReActAgent#modelCallStream} is
     * consumed and attempt lifecycle events are emitted.
     */
    private static final class RetryingScriptedModel extends ChatModelBase {
        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger idx = new AtomicInteger(0);
        private final String modelName;
        private final String provider;
        private final AtomicReference<GenerateOptions> lastOptions = new AtomicReference<>();

        RetryingScriptedModel(
                String modelName, String provider, List<Supplier<Flux<ChatResponse>>> scripts) {
            this.modelName = modelName;
            this.provider = provider;
            this.scripts = scripts;
        }

        @Override
        public String getModelName() {
            return modelName;
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            lastOptions.set(options);
            int i = idx.getAndIncrement();
            Flux<ChatResponse> raw;
            if (i >= scripts.size()) {
                raw = Flux.just(textResponse(""));
            } else {
                raw = scripts.get(i).get();
            }
            return ModelUtils.applyTimeoutAndRetry(raw, options, null, modelName, provider);
        }

        GenerateOptions getLastOptions() {
            return lastOptions.get();
        }
    }

    /** A tool that requires user confirmation before execution. */
    private static final class AskingTool extends ToolBase {
        AskingTool(String name) {
            super(name, "asks for permission", schemaFor(), false, true, false, null, false, false);
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

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("ask: " + getName()));
        }

        @Override
        public Mono<io.agentscope.core.message.ToolResultBlock> callAsync(ToolCallParam param) {
            Object q = param.getInput() == null ? "" : param.getInput().get("query");
            return Mono.just(io.agentscope.core.message.ToolResultBlock.text("executed:" + q));
        }
    }

    // ==================== Helper methods ====================

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static ChatResponse toolUseResponse(String toolId, String toolName, String query) {
        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        return ChatResponse.builder()
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder()
                                        .id(toolId)
                                        .name(toolName)
                                        .input(input)
                                        .build()))
                .build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder().name("user").role(MsgRole.USER).textContent(text).build();
    }

    private static Toolkit toolkitWith(ToolBase... tools) {
        Toolkit tk = new Toolkit();
        for (ToolBase t : tools) {
            tk.registerAgentTool(t);
        }
        return tk;
    }

    private static ReActAgent buildAgent(RetryingScriptedModel model) {
        return ReActAgent.builder()
                .name("asst")
                .model(model)
                .modelExecutionConfig(TEST_EXEC_CONFIG)
                .build();
    }

    private static ReActAgent buildAgentWithFallback(
            RetryingScriptedModel primary, RetryingScriptedModel fallback) {
        return ReActAgent.builder()
                .name("asst")
                .model(primary)
                .fallbackModel(fallback)
                .modelExecutionConfig(TEST_EXEC_CONFIG)
                .build();
    }

    private static int indexOf(List<AgentEvent> events, Class<?> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int countOf(List<AgentEvent> events, Class<?> type) {
        int c = 0;
        for (AgentEvent e : events) {
            if (type.isInstance(e)) {
                c++;
            }
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    private static <T extends AgentEvent> List<T> eventsOf(List<AgentEvent> events, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (AgentEvent e : events) {
            if (type.isInstance(e)) {
                result.add((T) e);
            }
        }
        return result;
    }

    // ========================================================================
    // 1. Retry then success — verifies MODEL_ATTEMPT_START/FAILED/END events
    // ========================================================================

    @Test
    void retryThenSuccess_emitsAttemptLifecycleEvents() {
        AtomicInteger callCount = new AtomicInteger(0);
        RetryingScriptedModel model =
                new RetryingScriptedModel(
                        PRIMARY_NAME,
                        PROVIDER,
                        List.of(
                                () ->
                                        Flux.defer(
                                                () -> {
                                                    int n = callCount.getAndIncrement();
                                                    if (n == 0) {
                                                        return Flux.error(
                                                                new HttpTransportException(
                                                                        "rate limited", 429, ""));
                                                    }
                                                    return Flux.just(textResponse("recovered"));
                                                })));
        ReActAgent agent = buildAgent(model);

        List<AgentEvent> events = agent.streamEvents(List.of(userMsg("hi"))).collectList().block();
        assertNotNull(events);

        List<ModelCallAttemptStartEvent> starts =
                eventsOf(events, ModelCallAttemptStartEvent.class);
        List<ModelCallAttemptFailedEvent> failures =
                eventsOf(events, ModelCallAttemptFailedEvent.class);
        List<ModelCallAttemptEndEvent> ends = eventsOf(events, ModelCallAttemptEndEvent.class);

        // Two starts: attempt 1 (fails) + attempt 2 (succeeds)
        assertEquals(2, starts.size(), "Should have 2 attempt starts (fail then succeed)");
        assertEquals(1, starts.get(0).getAttemptIndex());
        assertEquals(2, starts.get(1).getAttemptIndex());
        assertEquals(PRIMARY_NAME, starts.get(0).getModelName());
        assertEquals(PROVIDER, starts.get(0).getProvider());
        assertEquals(ModelCallAttemptRole.PRIMARY, starts.get(0).getRole());

        // One failure: attempt 1 with RETRY nextAction
        assertEquals(1, failures.size(), "Should have 1 attempt failure");
        ModelCallAttemptFailedEvent failed = failures.get(0);
        assertEquals(1, failed.getAttemptIndex());
        assertTrue(failed.isRetryable(), "429 should be retryable");
        assertEquals(
                ModelCallAttemptNextAction.RETRY,
                failed.getNextAction(),
                "First failure with attempts remaining should trigger RETRY");

        // One successful end: attempt 2
        assertEquals(1, ends.size(), "Should have 1 attempt end (success)");
        ModelCallAttemptEndEvent end = ends.get(0);
        assertEquals(2, end.getAttemptIndex());
        assertTrue(end.isSuccess(), "Second attempt should succeed");
        assertEquals(ModelCallAttemptRole.PRIMARY, end.getRole());
    }

    // ========================================================================
    // 2. Fallback activation — verifies MODEL_FALLBACK_ACTIVATED event
    // ========================================================================

    @Test
    void fallbackActivation_emitsModelFallbackActivatedEvent() {
        RetryingScriptedModel primary =
                new RetryingScriptedModel(
                        PRIMARY_NAME,
                        PROVIDER,
                        List.of(
                                () ->
                                        Flux.error(
                                                new HttpTransportException(
                                                        "rate limited", 429, ""))));
        RetryingScriptedModel fallback =
                new RetryingScriptedModel(
                        FALLBACK_NAME,
                        PROVIDER,
                        List.of(() -> Flux.just(textResponse("fallback success"))));
        ReActAgent agent = buildAgentWithFallback(primary, fallback);

        List<AgentEvent> events = agent.streamEvents(List.of(userMsg("hi"))).collectList().block();
        assertNotNull(events);

        List<ModelFallbackActivatedEvent> fallbackEvents =
                eventsOf(events, ModelFallbackActivatedEvent.class);
        assertEquals(1, fallbackEvents.size(), "Should emit exactly one fallback activated event");

        ModelFallbackActivatedEvent fba = fallbackEvents.get(0);
        assertEquals(PRIMARY_NAME, fba.getFromModel());
        assertEquals(FALLBACK_NAME, fba.getToModel());

        // Fallback should occur after primary attempt failures
        int lastPrimaryFailedIdx = -1;
        int fallbackIdx = indexOf(events, ModelFallbackActivatedEvent.class);
        for (int i = 0; i < fallbackIdx; i++) {
            if (events.get(i) instanceof ModelCallAttemptFailedEvent mfe
                    && mfe.getRole() == ModelCallAttemptRole.PRIMARY) {
                lastPrimaryFailedIdx = i;
            }
        }
        assertTrue(
                lastPrimaryFailedIdx >= 0 && lastPrimaryFailedIdx < fallbackIdx,
                "Fallback activation must follow primary attempt failures");
    }

    // ========================================================================
    // 3. Fallback role — verifies FALLBACK role in attempt events after switch
    // ========================================================================

    @Test
    void fallbackModel_emitsAttemptEventsWithFallbackRole() {
        RetryingScriptedModel primary =
                new RetryingScriptedModel(
                        PRIMARY_NAME,
                        PROVIDER,
                        List.of(
                                () ->
                                        Flux.error(
                                                new HttpTransportException(
                                                        "rate limited", 429, ""))));
        RetryingScriptedModel fallback =
                new RetryingScriptedModel(
                        FALLBACK_NAME,
                        PROVIDER,
                        List.of(() -> Flux.just(textResponse("fallback ok"))));
        ReActAgent agent = buildAgentWithFallback(primary, fallback);

        List<AgentEvent> events = agent.streamEvents(List.of(userMsg("hi"))).collectList().block();
        assertNotNull(events);

        List<ModelCallAttemptStartEvent> starts =
                eventsOf(events, ModelCallAttemptStartEvent.class);
        List<ModelCallAttemptEndEvent> ends = eventsOf(events, ModelCallAttemptEndEvent.class);

        // Verify at least one FALLBACK role start event exists
        boolean hasFallbackStart =
                starts.stream().anyMatch(s -> s.getRole() == ModelCallAttemptRole.FALLBACK);
        assertTrue(hasFallbackStart, "Should have at least one FALLBACK role attempt start");

        // Verify a successful FALLBACK role end event exists
        boolean hasFallbackEnd =
                ends.stream()
                        .anyMatch(
                                e -> e.getRole() == ModelCallAttemptRole.FALLBACK && e.isSuccess());
        assertTrue(hasFallbackEnd, "Should have a successful FALLBACK role attempt end");

        // Verify the fallback model name appears in start events
        boolean hasFallbackModelName =
                starts.stream().anyMatch(s -> FALLBACK_NAME.equals(s.getModelName()));
        assertTrue(
                hasFallbackModelName, "Fallback start events should carry the fallback model name");

        // Verify primary events also exist (before fallback)
        boolean hasPrimaryStart =
                starts.stream().anyMatch(s -> s.getRole() == ModelCallAttemptRole.PRIMARY);
        assertTrue(hasPrimaryStart, "Should have PRIMARY role attempt starts before fallback");
    }

    // ========================================================================
    // 4. call() path — verifies events emitted through shared buildAgentStream core
    // ========================================================================

    @Test
    void callPath_emitsAttemptEvents() {
        AtomicInteger callCount = new AtomicInteger(0);
        RetryingScriptedModel model =
                new RetryingScriptedModel(
                        PRIMARY_NAME,
                        PROVIDER,
                        List.of(
                                () ->
                                        Flux.defer(
                                                () -> {
                                                    int n = callCount.getAndIncrement();
                                                    if (n == 0) {
                                                        return Flux.error(
                                                                new HttpTransportException(
                                                                        "rate limited", 429, ""));
                                                    }
                                                    return Flux.just(textResponse("ok"));
                                                })));

        // streamEvents() shares the same buildAgentStream core as call()
        ReActAgent agent = buildAgent(model);
        List<AgentEvent> events = agent.streamEvents(List.of(userMsg("hi"))).collectList().block();
        assertNotNull(events);
        assertTrue(
                countOf(events, ModelCallAttemptStartEvent.class) > 0,
                "Attempt events must be emitted through the shared call/streamEvents core");

        // Also verify call() path produces a result on a fresh agent
        AtomicInteger callCount2 = new AtomicInteger(0);
        RetryingScriptedModel model2 =
                new RetryingScriptedModel(
                        PRIMARY_NAME,
                        PROVIDER,
                        List.of(
                                () ->
                                        Flux.defer(
                                                () -> {
                                                    int n = callCount2.getAndIncrement();
                                                    if (n == 0) {
                                                        return Flux.error(
                                                                new HttpTransportException(
                                                                        "rate limited", 429, ""));
                                                    }
                                                    return Flux.just(textResponse("via call"));
                                                })));
        ReActAgent agent2 = buildAgent(model2);
        Msg result = agent2.call(List.of(userMsg("hi"))).block();
        assertNotNull(result, "call() should return a result after retry recovery");

        // Verify result contains the expected text
        boolean hasText =
                result.getContent().stream()
                        .anyMatch(
                                b ->
                                        b instanceof TextBlock tb
                                                && tb.getText().contains("via call"));
        assertTrue(hasText, "Result should contain text from the recovered model call");
    }

    // ========================================================================
    // 5. No tool replay after fallback — verifies no tool events from failed primary
    // ========================================================================

    @Test
    void fallbackDoesNotReplayToolsFromFailedPrimary() {
        RetryingScriptedModel primary =
                new RetryingScriptedModel(
                        PRIMARY_NAME,
                        PROVIDER,
                        List.of(
                                () ->
                                        Flux.error(
                                                new HttpTransportException(
                                                        "rate limited", 429, ""))));
        RetryingScriptedModel fallback =
                new RetryingScriptedModel(
                        FALLBACK_NAME,
                        PROVIDER,
                        List.of(() -> Flux.just(textResponse("text only"))));
        ReActAgent agent = buildAgentWithFallback(primary, fallback);

        List<AgentEvent> events = agent.streamEvents(List.of(userMsg("hi"))).collectList().block();
        assertNotNull(events);

        // Primary failed without emitting any content; fallback returned text only.
        // No tool call events should appear in the stream.
        assertEquals(
                0,
                countOf(events, ToolCallStartEvent.class),
                "No tool calls should be emitted from the failed primary attempt");
    }

    // ========================================================================
    // 6. HITL + fallback coexist — verifies both mechanisms work together
    // ========================================================================

    @Test
    void hitlAndFallbackCoexist() {
        RetryingScriptedModel primary =
                new RetryingScriptedModel(
                        PRIMARY_NAME,
                        PROVIDER,
                        List.of(
                                () ->
                                        Flux.error(
                                                new HttpTransportException(
                                                        "rate limited", 429, ""))));
        RetryingScriptedModel fallback =
                new RetryingScriptedModel(
                        FALLBACK_NAME,
                        PROVIDER,
                        List.of(() -> Flux.just(toolUseResponse("tc1", "ask", "x"))));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(primary)
                        .fallbackModel(fallback)
                        .modelExecutionConfig(TEST_EXEC_CONFIG)
                        .toolkit(toolkitWith(new AskingTool("ask")))
                        .build();

        List<AgentEvent> events = agent.streamEvents(List.of(userMsg("hi"))).collectList().block();
        assertNotNull(events);

        // Both fallback activation and HITL confirmation events should be present
        assertTrue(
                countOf(events, ModelFallbackActivatedEvent.class) > 0,
                "Fallback activation event should be emitted when primary fails");
        assertTrue(
                countOf(events, RequireUserConfirmEvent.class) > 0,
                "HITL confirmation event should be emitted after fallback returns asking tool");

        // Fallback must occur before HITL
        int fallbackIdx = indexOf(events, ModelFallbackActivatedEvent.class);
        int hitlIdx = indexOf(events, RequireUserConfirmEvent.class);
        assertTrue(
                fallbackIdx >= 0 && hitlIdx > fallbackIdx,
                "Fallback activation must precede the HITL confirmation event");
    }

    // ========================================================================
    // 7. All attempts fail — verifies no assistant message on total failure
    // ========================================================================

    @Test
    void allAttemptsFail_noAssistantMessageAddedToContext() {
        RetryingScriptedModel model =
                new RetryingScriptedModel(
                        PRIMARY_NAME,
                        PROVIDER,
                        List.of(
                                () ->
                                        Flux.error(
                                                new HttpTransportException(
                                                        "rate limited", 429, ""))));
        ReActAgent agent = buildAgent(model);

        List<AgentEvent> events = new ArrayList<>();
        try {
            agent.streamEvents(List.of(userMsg("hi"))).doOnNext(events::add).blockLast();
        } catch (Throwable expected) {
            // All attempts failed — error propagation is expected
        }

        // Attempt events should have been emitted before the error propagated
        assertTrue(
                countOf(events, ModelCallAttemptStartEvent.class) > 0,
                "Attempt start events should be emitted before total failure");
        assertTrue(
                countOf(events, ModelCallAttemptFailedEvent.class) > 0,
                "Attempt failed events should be emitted before total failure");

        // The last FAILED event should have nextAction=FAIL (no fallback configured)
        List<ModelCallAttemptFailedEvent> failures =
                eventsOf(events, ModelCallAttemptFailedEvent.class);
        assertFalse(failures.isEmpty(), "Should have at least one failure event");
        ModelCallAttemptFailedEvent lastFailure = failures.get(failures.size() - 1);
        assertEquals(
                ModelCallAttemptNextAction.FAIL,
                lastFailure.getNextAction(),
                "Last failure with no fallback should have nextAction=FAIL");

        // No assistant message should be in the agent's context
        boolean hasAssistant =
                agent.getAgentState().getContext().stream()
                        .anyMatch(m -> m.getRole() == MsgRole.ASSISTANT);
        assertFalse(
                hasAssistant,
                "No assistant message should be added to context when all attempts fail");
    }

    // ========================================================================
    // 8. AttemptEventContext propagation — verifies context reaches model options
    // ========================================================================

    @Test
    void attemptEventContextPropagatedToModelOptions() {
        AtomicInteger callCount = new AtomicInteger(0);
        RetryingScriptedModel model =
                new RetryingScriptedModel(
                        PRIMARY_NAME,
                        PROVIDER,
                        List.of(
                                () ->
                                        Flux.defer(
                                                () -> {
                                                    int n = callCount.getAndIncrement();
                                                    if (n == 0) {
                                                        return Flux.error(
                                                                new HttpTransportException(
                                                                        "rate limited", 429, ""));
                                                    }
                                                    return Flux.just(textResponse("ok"));
                                                })));
        ReActAgent agent = buildAgent(model);

        agent.streamEvents(List.of(userMsg("hi"))).collectList().block();

        GenerateOptions lastOptions = model.getLastOptions();
        assertNotNull(lastOptions, "Model should have received GenerateOptions");
        assertNotNull(lastOptions.getExecutionConfig(), "Options should contain ExecutionConfig");

        AttemptEventContext ctx = lastOptions.getExecutionConfig().getAttemptEventContext();
        assertNotNull(
                ctx, "ExecutionConfig should contain AttemptEventContext injected by ReActAgent");
        assertTrue(ctx.isComplete(), "AttemptEventContext should have all fields populated");
        assertNotNull(ctx.getReplyId(), "ReplyId should be set");
        assertEquals(
                ModelCallAttemptRole.PRIMARY,
                ctx.getRole(),
                "Primary model call should have PRIMARY role");
        assertNotNull(ctx.getEmitter(), "Emitter consumer should be set");
    }
}
