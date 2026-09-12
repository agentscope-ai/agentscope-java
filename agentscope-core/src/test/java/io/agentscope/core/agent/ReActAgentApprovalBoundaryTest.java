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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Runtime security-boundary tests for the permission approval loop that escalation rides on
 * (requested in review of the escalation PR): an approval cannot be replayed after its call
 * executed, a tool-call id reused by the model never inherits an earlier approval, a confirm
 * referencing a non-ASKING call is rejected, and a transport-retried duplicate resume executes
 * the tool at most once.
 *
 * <p>Note on scope: {@code applyConfirmResults} intentionally replaces the ASKING block with the
 * (possibly edited) block from the ConfirmResult — the approver editing arguments before
 * approving is a supported capability, so the approval binds to the id of a currently-ASKING
 * call; what must NOT happen is execution without a fresh approval for that id.
 */
class ReActAgentApprovalBoundaryTest {

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

    private static ChatResponse toolUseResponse(String id, Map<String, ?> input) {
        Map<String, Object> typed = new HashMap<>(input);
        return ChatResponse.builder()
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder().id(id).name("work").input(typed).build()))
                .build();
    }

    private static final class CountingTool extends ToolBase {
        final AtomicInteger executions = new AtomicInteger();
        final AtomicReference<String> lastArgs = new AtomicReference<>();

        CountingTool() {
            super("work", "counting", schemaFor(), false, true, false, null, false, false);
        }

        private static Map<String, Object> schemaFor() {
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new HashMap<>();
            Map<String, Object> q = new HashMap<>();
            q.put("type", "string");
            props.put("query", q);
            props.put(
                    io.agentscope.core.permission.PermissionEscalation.ARG_PERMISSIONS,
                    Map.of("type", "string"));
            props.put(
                    io.agentscope.core.permission.PermissionEscalation.ARG_JUSTIFICATION,
                    Map.of("type", "string"));
            schema.put("properties", props);
            return schema;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.passthrough("no opinion"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            executions.incrementAndGet();
            Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
            lastArgs.set(String.valueOf(input.get("query")));
            return Mono.just(ToolResultBlock.text("executed:" + input.get("query")));
        }
    }

    private static ReActAgent escalationAgent(ChatModelBase model, Toolkit toolkit) {
        return ReActAgent.builder()
                .name("approval-boundary")
                .model(model)
                .toolkit(toolkit)
                .permissionContext(PermissionContextState.builder().escalationEnabled(true).build())
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

    private static Map<String, Object> escalationArgs(String query) {
        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("sandbox_permissions", "workspace-write");
        input.put("justification", "must run the build");
        return input;
    }

    /** Drives the agent into the paused (PERMISSION_ASKING) state and returns the pending call. */
    private static ToolUseBlock driveToPause(ScriptedModel model, Toolkit toolkit) {
        ReActAgent agent = escalationAgent(model, toolkit);
        Msg paused = agent.call(List.of()).block();
        assertNotNull(paused);
        assertEquals(GenerateReason.PERMISSION_ASKING, paused.getGenerateReason());
        List<ToolUseBlock> pending = paused.getContentBlocks(ToolUseBlock.class);
        assertEquals(1, pending.size());
        return pending.get(0);
    }

    @Test
    void approvalReplay_andModelIdReuse_cannotReuseApproval() {
        CountingTool tool = new CountingTool();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                // Pause on the escalation call.
                                () -> Flux.just(toolUseResponse("t9", escalationArgs("build"))),
                                // After confirm: tool executes, turn ends.
                                () -> Flux.just(textResponse("done")),
                                // Turn 2a: replayed confirm arrives (no ASKING) -> normal turn.
                                () -> Flux.just(textResponse("ignored")),
                                // Turn 2b: the model REUSES the same id with different args —
                                // must re-request approval instead of inheriting it.
                                () ->
                                        Flux.just(
                                                toolUseResponse(
                                                        "t9", escalationArgs("dangerous-args"))),
                                () -> Flux.just(textResponse("done-2"))));
        ReActAgent agent = escalationAgent(model, toolkit);

        // Turn 1: pause and capture the pending block.
        Msg paused = agent.call(List.of()).block();
        assertEquals(GenerateReason.PERMISSION_ASKING, paused.getGenerateReason());
        ToolUseBlock pending = paused.getContentBlocks(ToolUseBlock.class).get(0);
        assertEquals("t9", pending.getId());

        // Confirm: executes once.
        agent.call(List.of(confirmMsg(pending))).block();
        assertEquals(1, tool.executions.get());

        // Replay the SAME confirm after execution: no ASKING remains, so it is processed as a
        // normal turn — the tool must NOT execute again on the stale approval.
        agent.call(List.of(confirmMsg(pending))).block();
        assertEquals(1, tool.executions.get(), "a replayed approval must not re-execute");

        // The model reuses the approved id with DIFFERENT arguments. SECURITY property: the
        // new call never inherits the earlier approval — it is a fresh ToolUseBlock instance
        // (PENDING, not ALLOWED) and cannot execute. Current core semantics for the duplicate
        // id: the id-based tool-result correlation sees the OLD call's result as answering the
        // new one, so the call is silently dropped (fail-closed, no execution, no re-ask) —
        // a pre-existing correlation behavior worth its own follow-up, noted in the review
        // doc; the assertion below pins the boundary that matters for approval safety.
        agent.call(List.of()).block();
        assertEquals(
                1,
                tool.executions.get(),
                "an id-reused call must never inherit the earlier approval");
        assertEquals("build", tool.lastArgs.get(), "only the originally approved args ran");
    }

    @Test
    void confirmReferencingNonAskingId_rejected() {
        CountingTool tool = new CountingTool();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUseResponse("t1", escalationArgs("build")))));
        ReActAgent agent = escalationAgent(model, toolkit);

        Msg paused = agent.call(List.of()).block();
        assertEquals(GenerateReason.PERMISSION_ASKING, paused.getGenerateReason());
        ToolUseBlock asking = paused.getContentBlocks(ToolUseBlock.class).get(0);

        // A confirm for an id that is NOT among the ASKING calls must be rejected outright
        // (stale/replayed id from an earlier turn, foreign id, etc.).
        ToolUseBlock foreign =
                ToolUseBlock.builder()
                        .id("not-asking")
                        .name("work")
                        .input(new HashMap<>(escalationArgs("x")))
                        .build();
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> agent.call(List.of(confirmMsg(foreign))).block());
        assertTrue(
                ex.getMessage().contains("non-ASKING"),
                "rejection must name the non-ASKING reference, got: " + ex.getMessage());
        // Rejection is side-effect free: the genuinely ASKING call was untouched, and a
        // CORRECT confirm for it still executes after the rejected payload.
        assertEquals(0, tool.executions.get());
        assertNotNull(asking);
        agent.call(List.of(confirmMsg(asking))).block();
        assertEquals(1, tool.executions.get(), "a correct confirm must still work after rejection");
    }

    @Test
    void concurrentDuplicateResume_executesAtMostOnce() throws Exception {
        CountingTool tool = new CountingTool();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        CountDownLatch bothSubmitted = new CountDownLatch(2);
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUseResponse("t1", escalationArgs("build")))));
        ReActAgent agent = escalationAgent(model, toolkit);

        Msg paused = agent.call(List.of()).block();
        assertEquals(GenerateReason.PERMISSION_ASKING, paused.getGenerateReason());
        ToolUseBlock pending = paused.getContentBlocks(ToolUseBlock.class).get(0);
        Msg confirm = confirmMsg(pending);

        // A transport retries the SAME confirmation concurrently: same-session calls are
        // serialised, and after the first resume executes there is no ASKING call left, so the
        // duplicate is processed as a normal turn — the tool runs exactly once.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 =
                    pool.submit(
                            () -> {
                                bothSubmitted.countDown();
                                agent.call(List.of(confirm)).block();
                            });
            Future<?> f2 =
                    pool.submit(
                            () -> {
                                bothSubmitted.countDown();
                                agent.call(List.of(confirm)).block();
                            });
            assertTrue(bothSubmitted.await(5, TimeUnit.SECONDS));
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(
                1,
                tool.executions.get(),
                "a transport-retried duplicate confirmation must not execute the tool twice");
    }
}
