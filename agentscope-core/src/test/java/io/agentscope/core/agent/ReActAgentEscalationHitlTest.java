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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEscalation;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
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
 * End-to-end confirmation loop for model-requested escalation on an otherwise default-configured
 * (trivial-context) agent: a call carrying escalation arguments pauses with {@code
 * PERMISSION_ASKING}, the user's confirmation executes it, and — the key posture invariant —
 * plain calls on the same agent keep auto-executing exactly as before the flag was enabled.
 */
class ReActAgentEscalationHitlTest {

    private static final class ScriptedModel extends ChatModelBase {
        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger idx = new AtomicInteger(0);

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
                return Flux.just(textResponse(""));
            }
            return scripts.get(i).get();
        }
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static ChatResponse toolUseResponse(
            String toolId, String toolName, Map<String, ?> input) {
        Map<String, Object> typed = new HashMap<>(input);
        return ChatResponse.builder()
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder()
                                        .id(toolId)
                                        .name(toolName)
                                        .input(typed)
                                        .build()))
                .build();
    }

    /** PASSTHROUGH tool: in the lightweight path it auto-executes without asking. */
    private static final class PassthroughTool extends ToolBase {
        final AtomicReference<String> lastResult = new AtomicReference<>("never-run");

        PassthroughTool(String name) {
            super(name, "passthrough", schemaFor(), false, true, false, null, false, false);
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.passthrough("no opinion"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            Object q = param.getInput() == null ? "" : param.getInput().get("query");
            lastResult.set("executed:" + q);
            return Mono.just(ToolResultBlock.text("executed:" + q));
        }
    }

    private static Map<String, Object> schemaFor() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        Map<String, Object> q = new HashMap<>();
        q.put("type", "string");
        props.put("query", q);
        props.put(PermissionEscalation.ARG_PERMISSIONS, Map.of("type", "string"));
        props.put(PermissionEscalation.ARG_JUSTIFICATION, Map.of("type", "string"));
        schema.put("properties", props);
        return schema;
    }

    /** Denies via the tool self-check WITHOUT a message — the denial must still hold. */
    private static final class SilentDenyTool extends ToolBase {
        final AtomicReference<String> lastResult = new AtomicReference<>("never-run");

        SilentDenyTool() {
            super(
                    "deny-silently",
                    "denies without a message",
                    schemaFor(),
                    false,
                    true,
                    false,
                    null,
                    false,
                    false);
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            // PermissionDecision requires a non-null message at construction; an EMPTY
            // message is the weakest text a real custom tool can produce.
            return Mono.just(
                    PermissionDecision.builder()
                            .behavior(io.agentscope.core.permission.PermissionBehavior.DENY)
                            .message("")
                            .build());
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            lastResult.set("SHOULD-NOT-RUN");
            return Mono.just(ToolResultBlock.text("SHOULD-NOT-RUN"));
        }
    }

    private static ReActAgent agentWith(
            ChatModelBase model, Toolkit toolkit, boolean escalationEnabled) {
        return ReActAgent.builder()
                .name("esc-hitl")
                .model(model)
                .toolkit(toolkit)
                .permissionContext(
                        PermissionContextState.builder()
                                .escalationEnabled(escalationEnabled)
                                .build())
                .build();
    }

    private static Msg confirmMsg(ToolUseBlock toolCall) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(Msg.METADATA_CONFIRM_RESULTS, List.of(new ConfirmResult(true, toolCall, null)));
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("[confirm]")
                .metadata(meta)
                .build();
    }

    @Test
    void escalationCallPausesThenExecutesOnConfirm_plainCallStillAutoRuns() {
        PassthroughTool tool = new PassthroughTool("work");
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        // Trivial context (DEFAULT mode, no rules) with only the escalation flag enabled.
        // The ReAct loop keeps calling the model until a text response ends the turn, so the
        // script alternates: plain tool call -> text (ends turn 1) -> escalation call (pauses)
        // -> text (ends the resumed turn after the confirmed call executes).
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                toolUseResponse(
                                                        "t1", "work", Map.of("query", "plain"))),
                                () -> Flux.just(textResponse("turn-1-done")),
                                () ->
                                        Flux.just(
                                                toolUseResponse(
                                                        "t2",
                                                        "work",
                                                        Map.of(
                                                                "query",
                                                                "build",
                                                                "sandbox_permissions",
                                                                "workspace-write",
                                                                "justification",
                                                                "must run the build"))),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent = agentWith(model, toolkit, true);

        // Turn 1: plain call on the trivial+escalation context — previous behavior, no pause.
        Msg first = agent.call(List.of()).block();
        assertNotNull(first);
        assertNotEquals(
                GenerateReason.PERMISSION_ASKING,
                first.getGenerateReason(),
                "plain calls must keep auto-executing after the flag is enabled");
        assertEquals("executed:plain", tool.lastResult.get());

        // Turn 2: escalation request — pauses with PERMISSION_ASKING carrying the tool call.
        Msg second = agent.call(List.of()).block();
        assertNotNull(second);
        assertEquals(GenerateReason.PERMISSION_ASKING, second.getGenerateReason());
        List<ToolUseBlock> pending = second.getContentBlocks(ToolUseBlock.class);
        assertEquals(1, pending.size());
        assertEquals("t2", pending.get(0).getId());

        // Turn 3: confirm — the escalated call executes.
        Msg third = agent.call(List.of(confirmMsg(pending.get(0)))).block();
        assertNotNull(third);
        assertNotEquals(GenerateReason.PERMISSION_ASKING, third.getGenerateReason());
        assertEquals("executed:build", tool.lastResult.get());
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .anyMatch(m -> m.getRole() == MsgRole.TOOL || m instanceof Msg),
                "context advanced");
    }

    @Test
    void hallucinatedEscalationArgsOnDisabledAgent_deniesClosed() {
        PassthroughTool tool = new PassthroughTool("work");
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        // Escalation NOT enabled; the model hallucinated the arguments anyway.
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                toolUseResponse(
                                                        "t1",
                                                        "work",
                                                        Map.of(
                                                                "query",
                                                                "build",
                                                                "sandbox_permissions",
                                                                "workspace-write",
                                                                "justification",
                                                                "please"))),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent = agentWith(model, toolkit, false);

        Msg first = agent.call(List.of()).block();
        assertNotNull(first);
        // Fail-closed: the call is denied with the not-enabled reason instead of executing.
        assertEquals("never-run", tool.lastResult.get());
        // And the denial REASON reaches the model in the DENIED tool result — a generic
        // "denied" would give it nothing to correct against.
        String deniedText = null;
        for (Msg m : agent.getAgentState().getContext()) {
            for (ToolResultBlock tr : m.getContentBlocks(ToolResultBlock.class)) {
                if (tr.getState() == io.agentscope.core.message.ToolResultState.DENIED) {
                    deniedText = String.valueOf(tr.getOutput());
                }
            }
        }
        assertNotNull(deniedText, "a DENIED tool result must be present");
        assertTrue(deniedText.contains("not enabled"), deniedText);
    }

    @Test
    void emptyMessageSelfCheckDeny_stillDenies() {
        // A custom tool's self-check may return DENY with an empty message; the denial
        // must hold (fail-closed) rather than silently falling out of the denied map.
        SilentDenyTool tool = new SilentDenyTool();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                toolUseResponse(
                                                        "t1",
                                                        "deny-silently",
                                                        Map.of("query", "x"))),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent = agentWith(model, toolkit, false);

        agent.call(List.of()).block();
        assertEquals("never-run", tool.lastResult.get(), "a message-less DENY must not execute");
    }

    @Test
    void longJustification_isCappedInTheConfirmationPayload() {
        // The 500-char cap must bind what the approver actually sees: the pending
        // ToolUseBlock inside RequireUserConfirmEvent carries the truncated text, not the
        // original full-length one the model sent.
        PassthroughTool tool = new PassthroughTool("work");
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        String longReason = "r".repeat(900);
        Map<String, Object> args = new HashMap<>();
        args.put("query", "build");
        args.put("sandbox_permissions", "workspace-write");
        args.put("justification", longReason);
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUseResponse("t1", "work", args))));
        ReActAgent agent = agentWith(model, toolkit, true);

        // Assert on the EVENT payload — the surface the approval UI renders. (The returned
        // Msg's context blocks keep the model's original text; the cap binds the boundary the
        // approver sees.)
        List<io.agentscope.core.event.AgentEvent> events =
                agent.streamEvents(List.of()).collectList().block();
        io.agentscope.core.event.RequireUserConfirmEvent confirmEvent = null;
        for (io.agentscope.core.event.AgentEvent e : events) {
            if (e instanceof io.agentscope.core.event.RequireUserConfirmEvent rce) {
                confirmEvent = rce;
            }
        }
        assertNotNull(confirmEvent, "RequireUserConfirmEvent must be emitted");
        assertEquals(1, confirmEvent.getToolCalls().size());
        Object seen =
                confirmEvent
                        .getToolCalls()
                        .get(0)
                        .getInput()
                        .get(PermissionEscalation.ARG_JUSTIFICATION);
        assertEquals(
                io.agentscope.core.permission.PermissionEscalation.MAX_JUSTIFICATION_LENGTH,
                String.valueOf(seen).length(),
                "the approver-visible payload carries the capped text");
        assertTrue(String.valueOf(seen).endsWith("…"));
    }
}
