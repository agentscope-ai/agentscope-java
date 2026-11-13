/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** Verifies replacement semantics for builder-provided permission contexts. */
class ReActAgentPermissionRulesTest {

    private static final String TOOL = "deploy";

    private static final class DeployTool extends ToolBase {

        private final AtomicInteger calls = new AtomicInteger();
        private final List<PermissionContextState> checkedContexts = new ArrayList<>();

        DeployTool() {
            super(ToolBase.builder().name(TOOL).description(TOOL).inputSchema(schema()));
        }

        int callCount() {
            return calls.get();
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            checkedContexts.add(context);
            return Mono.just(PermissionDecision.passthrough(getName()));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            calls.incrementAndGet();
            return Mono.just(ToolResultBlock.text("deployed"));
        }
    }

    private static final class ScriptedModel extends ChatModelBase {

        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private int idx;

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
            int i = idx++;
            if (i >= scripts.size()) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("").build()))
                                .build());
            }
            return scripts.get(i).get();
        }
    }

    private static Map<String, Object> schema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("env", Map.of("type", "string"));
        schema.put("properties", props);
        return schema;
    }

    private static ChatResponse toolCall(String toolCallId) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(toolCallId)
                                        .name(TOOL)
                                        .input(Map.of("env", "prod"))
                                        .content("{}")
                                        .build()))
                .build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static ScriptedModel model(String... toolCallIds) {
        List<Supplier<Flux<ChatResponse>>> scripts = new ArrayList<>();
        for (String id : toolCallIds) {
            scripts.add(() -> Flux.just(toolCall(id)));
            scripts.add(() -> Flux.just(textResponse("done")));
        }
        return new ScriptedModel(scripts);
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static PermissionContextState rules(PermissionBehavior behavior) {
        PermissionRule rule = new PermissionRule(TOOL, null, behavior, "test");
        PermissionContextState.Builder builder = PermissionContextState.builder();
        return switch (behavior) {
            case ALLOW -> builder.addAllowRule(TOOL, rule).build();
            case ASK -> builder.addAskRule(TOOL, rule).build();
            case DENY -> builder.addDenyRule(TOOL, rule).build();
            case PASSTHROUGH -> builder.build();
        };
    }

    private static ReActAgent agent(
            ChatModelBase model,
            PermissionContextState context,
            AgentStateStore store,
            DeployTool tool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        return ReActAgent.builder()
                .name("asst")
                .model(model)
                .toolkit(toolkit)
                .permissionContext(context)
                .stateStore(store)
                .build();
    }

    private static RuntimeContext session(String sessionId) {
        return RuntimeContext.builder().userId("u1").sessionId(sessionId).build();
    }

    static Stream<Arguments> replacementCases() {
        PermissionContextState trivial = PermissionContextState.builder().build();
        PermissionContextState deny = rules(PermissionBehavior.DENY);
        PermissionContextState dontAsk =
                PermissionContextState.builder().mode(PermissionMode.DONT_ASK).build();
        return Stream.of(false, true)
                .flatMap(
                        store ->
                                Stream.of(
                                        Arguments.of(deny, trivial, true, false, store, false),
                                        Arguments.of(trivial, deny, false, true, store, false),
                                        Arguments.of(dontAsk, trivial, true, false, store, true),
                                        Arguments.of(trivial, dontAsk, false, true, store, true)));
    }

    @ParameterizedTest
    @MethodSource("replacementCases")
    void admittedCallKeepsPermissionsWhileReplacementAppliesToNextCall(
            PermissionContextState initial,
            PermissionContextState replacement,
            boolean admittedDenied,
            boolean nextDenied,
            boolean withStore,
            boolean modeOnly) {
        CountDownLatch admitted = new CountDownLatch(1);
        Sinks.One<ChatResponse> response = Sinks.one();
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.defer(
                                                () -> {
                                                    admitted.countDown();
                                                    return response.asMono().flux();
                                                }),
                                () -> Flux.just(textResponse("done")),
                                () -> Flux.just(toolCall("next")),
                                () -> Flux.just(textResponse("done"))));
        DeployTool tool = new DeployTool();
        RuntimeContext ctx = session("admission");
        try (ReActAgent agent =
                agent(model, initial, withStore ? new InMemoryAgentStateStore() : null, tool)) {
            StepVerifier.create(agent.streamEvents(List.of(userMsg("deploy?")), ctx).collectList())
                    .then(
                            () -> {
                                try {
                                    assertTrue(admitted.await(10, TimeUnit.SECONDS));
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(e);
                                }
                                if (modeOnly) {
                                    agent.setPermissionMode(ctx, replacement.getMode());
                                } else {
                                    agent.replacePermissionContext("u1", "admission", replacement);
                                }
                                assertEquals(
                                        replacement,
                                        agent.getAgentState(ctx).getPermissionContext());
                                assertEquals(
                                        Sinks.EmitResult.OK,
                                        response.tryEmitValue(toolCall("admitted")));
                            })
                    .assertNext(
                            events -> {
                                assertEquals(
                                        admittedDenied,
                                        events.stream()
                                                .anyMatch(
                                                        event ->
                                                                event
                                                                                instanceof
                                                                                ToolResultEndEvent
                                                                                        end
                                                                        && "admitted"
                                                                                .equals(
                                                                                        end
                                                                                                .getToolCallId())
                                                                        && end.getState()
                                                                                == ToolResultState
                                                                                        .DENIED));
                                assertEquals(admittedDenied ? 0 : 1, tool.callCount());
                                assertTrue(
                                        tool.checkedContexts.stream().allMatch(initial::equals),
                                        "tool self-checks must use the admitted context");
                            })
                    .expectComplete()
                    .verify(Duration.ofSeconds(10));
            assertEquals(replacement, agent.getAgentState(ctx).getPermissionContext());
            StepVerifier.create(agent.streamEvents(List.of(userMsg("again")), ctx).collectList())
                    .assertNext(
                            events -> {
                                assertEquals(
                                        nextDenied,
                                        events.stream()
                                                .anyMatch(
                                                        event ->
                                                                event
                                                                                instanceof
                                                                                ToolResultEndEvent
                                                                                        end
                                                                        && "next"
                                                                                .equals(
                                                                                        end
                                                                                                .getToolCallId())
                                                                        && end.getState()
                                                                                == ToolResultState
                                                                                        .DENIED));
                                assertEquals(
                                        (admittedDenied ? 0 : 1) + (nextDenied ? 0 : 1),
                                        tool.callCount());
                            })
                    .expectComplete()
                    .verify(Duration.ofSeconds(10));
            assertEquals(replacement, agent.getAgentState(ctx).getPermissionContext());
        }
    }

    static Stream<Arguments> confirmedRuleCases() {
        return Stream.of(PermissionBehavior.DENY, PermissionBehavior.ASK)
                .flatMap(
                        behavior ->
                                Stream.of(false, true).map(store -> Arguments.of(behavior, store)));
    }

    @ParameterizedTest
    @MethodSource("confirmedRuleCases")
    void trivialStartHonorsConfirmedRulesAndKeepsSlotsIsolated(
            PermissionBehavior behavior, boolean withStore) {
        DeployTool deploy = new DeployTool();
        AtomicInteger confirmedCalls = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(deploy);
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("confirm")
                                .description("confirm")
                                .inputSchema(schema())) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState context) {
                        return Mono.just(PermissionDecision.ask("confirm"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        confirmedCalls.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("confirmed"));
                    }
                });
        ChatResponse ask =
                ChatResponse.builder()
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("ask-1")
                                                .name("confirm")
                                                .input(Map.of())
                                                .build()))
                        .build();
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(ask),
                                () -> Flux.just(toolCall("after-confirm")),
                                () -> Flux.just(textResponse("done")),
                                () -> Flux.just(toolCall("other-session")),
                                () -> Flux.just(textResponse("done")),
                                () -> Flux.just(toolCall("other-user")),
                                () -> Flux.just(textResponse("done")),
                                () -> Flux.just(toolCall("later-same-session")),
                                () -> Flux.just(textResponse("done"))));
        PermissionContextState initial = PermissionContextState.builder().build();
        RuntimeContext original = session("confirmed-session");
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkit)
                        .permissionContext(initial)
                        .stateStore(withStore ? new InMemoryAgentStateStore() : null)
                        .build()) {
            Msg prompt =
                    agent.call(List.of(userMsg("confirm?")), original)
                            .block(Duration.ofSeconds(10));
            assertNotNull(prompt);
            assertEquals(GenerateReason.PERMISSION_ASKING, prompt.getGenerateReason());
            assertTrue(agent.getAgentState(original).getPermissionContext().isTrivial());
            Msg approval =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("")
                            .metadata(
                                    Map.of(
                                            Msg.METADATA_CONFIRM_RESULTS,
                                            List.of(
                                                    new ConfirmResult(
                                                            true,
                                                            prompt.getContentBlocks(
                                                                            ToolUseBlock.class)
                                                                    .get(0),
                                                            List.of(
                                                                    new PermissionRule(
                                                                            TOOL,
                                                                            null,
                                                                            behavior,
                                                                            "user_confirm"))))))
                            .build();
            StepVerifier.create(agent.streamEvents(List.of(approval), original).collectList())
                    .assertNext(
                            events -> {
                                assertEquals(1, confirmedCalls.get());
                                assertEquals(
                                        0,
                                        deploy.callCount(),
                                        "the later tool must not execute in the confirming call");
                                if (behavior == PermissionBehavior.DENY) {
                                    assertTrue(
                                            events.stream()
                                                    .anyMatch(
                                                            event ->
                                                                    event
                                                                                    instanceof
                                                                                    ToolResultEndEvent
                                                                                            end
                                                                            && "after-confirm"
                                                                                    .equals(
                                                                                            end
                                                                                                    .getToolCallId())
                                                                            && end.getState()
                                                                                    == ToolResultState
                                                                                            .DENIED));
                                } else {
                                    assertTrue(
                                            events.stream()
                                                    .anyMatch(
                                                            event ->
                                                                    event
                                                                                    instanceof
                                                                                    RequireUserConfirmEvent
                                                                                            request
                                                                            && "after-confirm"
                                                                                    .equals(
                                                                                            request.getToolCalls()
                                                                                                    .get(
                                                                                                            0)
                                                                                                    .getId())));
                                }
                            })
                    .expectComplete()
                    .verify(Duration.ofSeconds(10));
            if (behavior == PermissionBehavior.ASK) {
                // Reject the second prompt so the model consumes its normal completion response.
                Msg rejection =
                        Msg.builder()
                                .role(MsgRole.USER)
                                .textContent("")
                                .metadata(
                                        Map.of(
                                                Msg.METADATA_CONFIRM_RESULTS,
                                                List.of(
                                                        new ConfirmResult(
                                                                false,
                                                                ToolUseBlock.builder()
                                                                        .id("after-confirm")
                                                                        .name(TOOL)
                                                                        .input(
                                                                                Map.of(
                                                                                        "env",
                                                                                        "prod"))
                                                                        .build()))))
                                .build();
                agent.call(List.of(rejection), original).block(Duration.ofSeconds(10));
            }
            agent.call(List.of(userMsg("isolated")), session("other-session"))
                    .block(Duration.ofSeconds(10));
            RuntimeContext otherUser =
                    RuntimeContext.builder().userId("u2").sessionId("confirmed-session").build();
            agent.call(List.of(userMsg("isolated")), otherUser).block(Duration.ofSeconds(10));
            assertEquals(2, deploy.callCount(), "rules must not leak to another user or session");
            assertTrue(initial.isTrivial(), "the builder context must not be mutated");
            Msg later =
                    agent.call(List.of(userMsg("again")), original).block(Duration.ofSeconds(10));
            assertNotNull(later);
            assertEquals(
                    behavior == PermissionBehavior.ASK
                            ? GenerateReason.PERMISSION_ASKING
                            : GenerateReason.MODEL_STOP,
                    later.getGenerateReason());
            assertEquals(2, deploy.callCount(), "confirmed rules must survive the next call");
        }
    }

    static Stream<Arguments> adminConfirmationCases() {
        PermissionContextState trivial = PermissionContextState.builder().build();
        PermissionContextState bypass = trivial.withMode(PermissionMode.BYPASS);
        PermissionContextState admin =
                PermissionContextState.builder()
                        .mode(PermissionMode.BYPASS)
                        .addWorkingDirectory(
                                "admin",
                                new AdditionalWorkingDirectory(
                                        System.getProperty("java.io.tmpdir"), "admin"))
                        .addDenyRule(
                                "audit",
                                new PermissionRule("audit", null, PermissionBehavior.DENY, "admin"))
                        .build();
        PermissionRule deny = new PermissionRule(TOOL, null, PermissionBehavior.DENY, "confirm");
        PermissionRule ask = new PermissionRule(TOOL, null, PermissionBehavior.ASK, "confirm");
        PermissionRule allow = new PermissionRule(TOOL, null, PermissionBehavior.ALLOW, "confirm");
        return Stream.of(false, true)
                .flatMap(
                        store ->
                                Stream.of(
                                        Arguments.of(
                                                trivial,
                                                false,
                                                store,
                                                true,
                                                List.of(deny),
                                                PermissionBehavior.DENY,
                                                PermissionBehavior.DENY),
                                        Arguments.of(
                                                bypass,
                                                true,
                                                store,
                                                true,
                                                List.of(deny),
                                                PermissionBehavior.DENY,
                                                PermissionBehavior.DENY),
                                        Arguments.of(
                                                admin.withAddedRules(List.of(allow)),
                                                false,
                                                store,
                                                true,
                                                List.of(ask),
                                                PermissionBehavior.ASK,
                                                PermissionBehavior.ASK),
                                        Arguments.of(
                                                admin.withMode(PermissionMode.DONT_ASK),
                                                false,
                                                store,
                                                true,
                                                List.of(allow),
                                                PermissionBehavior.ALLOW,
                                                PermissionBehavior.ALLOW),
                                        Arguments.of(
                                                admin.withAddedRules(List.of(deny)),
                                                false,
                                                store,
                                                true,
                                                List.of(allow),
                                                PermissionBehavior.ALLOW,
                                                PermissionBehavior.DENY),
                                        Arguments.of(
                                                admin.withAddedRules(List.of(ask)),
                                                false,
                                                store,
                                                true,
                                                List.of(allow),
                                                PermissionBehavior.ALLOW,
                                                PermissionBehavior.ASK),
                                        Arguments.of(
                                                trivial,
                                                false,
                                                store,
                                                false,
                                                List.of(deny),
                                                PermissionBehavior.ALLOW,
                                                PermissionBehavior.ALLOW),
                                        Arguments.of(
                                                bypass,
                                                true,
                                                store,
                                                true,
                                                null,
                                                PermissionBehavior.ALLOW,
                                                PermissionBehavior.ALLOW),
                                        Arguments.of(
                                                trivial,
                                                false,
                                                store,
                                                true,
                                                Collections.singletonList(null),
                                                PermissionBehavior.ALLOW,
                                                PermissionBehavior.ALLOW)));
    }

    @ParameterizedTest
    @MethodSource("adminConfirmationCases")
    void acceptedRulesReconcileAdminReplacementBeforeNextCall(
            PermissionContextState replacement,
            boolean modeOnly,
            boolean withStore,
            boolean confirmed,
            List<PermissionRule> suppliedRules,
            PermissionBehavior admittedOutcome,
            PermissionBehavior nextOutcome) {
        CountDownLatch admitted = new CountDownLatch(1);
        Sinks.One<Void> release = Sinks.one();
        AtomicInteger preCalls = new AtomicInteger();
        Hook holdConfirmation =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreCallEvent && preCalls.incrementAndGet() == 2) {
                            admitted.countDown();
                            return release.asMono().thenReturn(event);
                        }
                        return Mono.just(event);
                    }
                };
        DeployTool deploy = new DeployTool();
        AtomicInteger confirmCalls = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(deploy);
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("confirm")
                                .description("confirm")
                                .inputSchema(schema())) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState context) {
                        return Mono.just(PermissionDecision.ask("confirm"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        confirmCalls.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("confirmed"));
                    }
                });
        ChatResponse ask =
                ChatResponse.builder()
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("confirm-1")
                                                .name("confirm")
                                                .input(Map.of())
                                                .build()))
                        .build();
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(ask),
                                () -> Flux.just(toolCall("after-confirm")),
                                () -> Flux.just(textResponse("done")),
                                () -> Flux.just(toolCall("next")),
                                () -> Flux.just(textResponse("done")),
                                () -> Flux.just(toolCall("other-session")),
                                () -> Flux.just(textResponse("done")),
                                () -> Flux.just(toolCall("other-user")),
                                () -> Flux.just(textResponse("done"))));
        PermissionContextState initial = PermissionContextState.builder().build();
        RuntimeContext ctx = session("admin-confirmation");
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkit)
                        .permissionContext(initial)
                        .stateStore(withStore ? new InMemoryAgentStateStore() : null)
                        .hook(holdConfirmation)
                        .build()) {
            Msg prompt =
                    agent.call(List.of(userMsg("confirm?")), ctx).block(Duration.ofSeconds(10));
            assertNotNull(prompt);
            assertEquals(GenerateReason.PERMISSION_ASKING, prompt.getGenerateReason());
            assertEquals(initial, agent.getAgentState(ctx).getPermissionContext());
            Msg reply =
                    confirmation(
                            confirmed,
                            prompt.getContentBlocks(ToolUseBlock.class).get(0),
                            suppliedRules);
            PermissionContextState expected =
                    replacement.withAddedRules(confirmed ? suppliedRules : null);
            StepVerifier.create(agent.streamEvents(List.of(reply), ctx).collectList())
                    .then(
                            () -> {
                                try {
                                    assertTrue(admitted.await(10, TimeUnit.SECONDS));
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(e);
                                }
                                // Admission is complete, but confirmation rules have not been
                                // applied.
                                if (modeOnly) {
                                    agent.setPermissionMode(ctx, replacement.getMode());
                                } else {
                                    agent.replacePermissionContext(
                                            "u1", "admin-confirmation", replacement);
                                }
                                assertEquals(
                                        replacement,
                                        agent.getAgentState(ctx).getPermissionContext());
                                assertEquals(Sinks.EmitResult.OK, release.tryEmitEmpty());
                            })
                    .assertNext(
                            events -> {
                                assertOutcome(events, "after-confirm", admittedOutcome);
                                assertEquals(confirmed ? 1 : 0, confirmCalls.get());
                                assertEquals(
                                        admittedOutcome == PermissionBehavior.ALLOW ? 1 : 0,
                                        deploy.callCount());
                                assertTrue(
                                        deploy.checkedContexts.stream().allMatch(initial::equals),
                                        "the administrator context must not replace the admitted"
                                                + " context");
                            })
                    .expectComplete()
                    .verify(Duration.ofSeconds(10));
            assertEquals(expected, agent.getAgentState(ctx).getPermissionContext());
            if (admittedOutcome == PermissionBehavior.ASK) {
                rejectPendingDeploy(agent, ctx, "after-confirm");
            }
            int beforeNext = deploy.callCount();
            StepVerifier.create(agent.streamEvents(List.of(userMsg("next")), ctx).collectList())
                    .assertNext(
                            events -> {
                                assertOutcome(events, "next", nextOutcome);
                                assertEquals(
                                        beforeNext
                                                + (nextOutcome == PermissionBehavior.ALLOW ? 1 : 0),
                                        deploy.callCount(),
                                        "accepted DENY/ASK must prevent the next execution");
                            })
                    .expectComplete()
                    .verify(Duration.ofSeconds(10));
            if (nextOutcome == PermissionBehavior.ASK) {
                rejectPendingDeploy(agent, ctx, "next");
            }
            assertEquals(expected, agent.getAgentState(ctx).getPermissionContext());
            assertEquals("u1", agent.getAgentState(ctx).getUserId());
            assertEquals("admin-confirmation", agent.getAgentState(ctx).getSessionId());
            int beforeIsolation = deploy.callCount();
            RuntimeContext otherSession = session("other-session");
            RuntimeContext otherUser =
                    RuntimeContext.builder().userId("u2").sessionId("admin-confirmation").build();
            agent.call(List.of(userMsg("isolated")), otherSession).block(Duration.ofSeconds(10));
            agent.call(List.of(userMsg("isolated")), otherUser).block(Duration.ofSeconds(10));
            assertEquals(beforeIsolation + 2, deploy.callCount());
            assertEquals(initial, agent.getAgentState(otherSession).getPermissionContext());
            assertEquals(initial, agent.getAgentState(otherUser).getPermissionContext());
            assertEquals(expected, agent.getAgentState(ctx).getPermissionContext());
            assertTrue(initial.isTrivial(), "the builder context must remain untouched");
        }
    }

    private static Msg confirmation(
            boolean confirmed, ToolUseBlock tool, List<PermissionRule> rules) {
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent("")
                .metadata(
                        Map.of(
                                Msg.METADATA_CONFIRM_RESULTS,
                                List.of(new ConfirmResult(confirmed, tool, rules))))
                .build();
    }

    private static void rejectPendingDeploy(ReActAgent agent, RuntimeContext ctx, String id) {
        Msg rejected =
                confirmation(
                        false,
                        ToolUseBlock.builder()
                                .id(id)
                                .name(TOOL)
                                .input(Map.of("env", "prod"))
                                .build(),
                        null);
        agent.call(List.of(rejected), ctx).block(Duration.ofSeconds(10));
    }

    private static void assertOutcome(
            List<AgentEvent> events, String id, PermissionBehavior outcome) {
        if (outcome == PermissionBehavior.ASK) {
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    event ->
                                            event instanceof RequireUserConfirmEvent ask
                                                    && ask.getToolCalls().stream()
                                                            .anyMatch(
                                                                    tool ->
                                                                            id.equals(
                                                                                    tool
                                                                                            .getId()))));
        } else {
            ToolResultState expected =
                    outcome == PermissionBehavior.DENY
                            ? ToolResultState.DENIED
                            : ToolResultState.SUCCESS;
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    event ->
                                            event instanceof ToolResultEndEvent end
                                                    && id.equals(end.getToolCallId())
                                                    && end.getState() == expected));
        }
    }

    @Test
    @DisplayName("a non-null context replaces the persisted context")
    void nonNullPermissionContextReplacesPersistedContext() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();

        agent(model("tc-v1"), rules(PermissionBehavior.ALLOW), store, new DeployTool())
                .call(List.of(userMsg("deploy?")), session("sessA"))
                .block();

        Msg replaced =
                agent(model("tc-v2"), rules(PermissionBehavior.ASK), store, new DeployTool())
                        .call(List.of(userMsg("deploy?")), session("sessA"))
                        .block();

        assertNotNull(replaced);
        assertEquals(GenerateReason.PERMISSION_ASKING, replaced.getGenerateReason());
    }

    @Test
    @DisplayName("a null context leaves the persisted context untouched")
    void nullPermissionContextLeavesPersistedContext() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        DeployTool tool = new DeployTool();

        agent(model("tc-v1"), rules(PermissionBehavior.ALLOW), store, tool)
                .call(List.of(userMsg("deploy?")), session("sessA"))
                .block();

        agent(model("tc-v2"), null, store, tool)
                .call(List.of(userMsg("deploy?")), session("sessA"))
                .block();

        assertEquals(2, tool.callCount());
    }

    @Test
    @DisplayName("the builder context is not reapplied after a runtime replacement")
    void builderContextIsNotReappliedAfterRuntimeReplacement() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent agent =
                agent(
                        model("tc-1", "tc-2"),
                        rules(PermissionBehavior.ALLOW),
                        store,
                        new DeployTool());

        agent.call(List.of(userMsg("deploy?")), session("sessA")).block();

        agent.replacePermissionContext("u1", "sessA", rules(PermissionBehavior.ASK));

        Msg replaced = agent.call(List.of(userMsg("deploy?")), session("sessA")).block();

        assertNotNull(replaced);
        assertEquals(GenerateReason.PERMISSION_ASKING, replaced.getGenerateReason());
    }

    @Test
    @DisplayName("a non-null trivial context is a replacement, not a no-op")
    void trivialPermissionContextIsReplacementNotNoOp() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();

        agent(model("tc-v1"), rules(PermissionBehavior.DENY), store, new DeployTool())
                .call(List.of(userMsg("deploy?")), session("sessA"))
                .block();

        Msg replaced =
                agent(
                                model("tc-v2"),
                                PermissionContextState.builder().build(),
                                store,
                                new DeployTool())
                        .call(List.of(userMsg("deploy?")), session("sessA"))
                        .block();

        assertNotNull(replaced);
        assertEquals(GenerateReason.MODEL_STOP, replaced.getGenerateReason());
    }

    @Test
    @DisplayName("a rebuilt agent applies its non-null context again")
    void rebuiltAgentAppliesItsNonNullContextAgain() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent first =
                agent(model("tc-1"), rules(PermissionBehavior.ALLOW), store, new DeployTool());
        first.call(List.of(userMsg("deploy?")), session("sessA")).block();

        first.replacePermissionContext("u1", "sessA", rules(PermissionBehavior.DENY));

        Msg replaced =
                agent(model("tc-2"), rules(PermissionBehavior.ALLOW), store, new DeployTool())
                        .call(List.of(userMsg("deploy?")), session("sessA"))
                        .block();

        assertNotNull(replaced);
        assertEquals(GenerateReason.MODEL_STOP, replaced.getGenerateReason());
    }
}
