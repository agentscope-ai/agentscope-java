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

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContext;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.permission.ToolBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

/** End-to-end tests for the Stage 7f HITL gating via {@link Sinks.Many}. */
class Agent2HitlTest {

    private static AgentState newState() {
        return AgentState.builder().sessionId("session-hitl").build();
    }

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

    /** ToolBase whose checkPermissions always returns {@link PermissionBehavior#ASK}. */
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
                Map<String, Object> toolInput, PermissionContext context) {
            return Mono.just(PermissionDecision.ask("ask: " + getName()));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            Object q = param.getInput() == null ? "" : param.getInput().get("query");
            return Mono.just(ToolResultBlock.text("executed:" + q));
        }
    }

    /** ToolBase whose checkPermissions always returns {@link PermissionBehavior#ALLOW}. */
    private static final class AllowingTool extends ToolBase {
        AllowingTool(String name) {
            super(name, "auto-allow", schemaFor(), true, true, false, null, false, false);
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
                Map<String, Object> toolInput, PermissionContext context) {
            return Mono.just(PermissionDecision.allow("allow: " + getName()));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            Object q = param.getInput() == null ? "" : param.getInput().get("query");
            return Mono.just(ToolResultBlock.text("allowed:" + q));
        }
    }

    private static Toolkit toolkitWith(ToolBase... tools) {
        Toolkit tk = new Toolkit();
        for (ToolBase t : tools) {
            tk.registerAgentTool(t);
        }
        return tk;
    }

    private static Agent2 buildAgent(ChatModelBase model, Toolkit toolkit) {
        return new Agent2("asst", null, model, toolkit, List.of(), newState(), null, null, null);
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

    // ==================== Auto-deny path (no sink registered) ====================

    @Test
    void askingToolWithoutSinkAutoDeniesAndEmitsDeniedToolResult() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "ask", "x")),
                                () -> Flux.just(textResponse("done"))));
        Agent2 agent = buildAgent(model, toolkitWith(new AskingTool("ask")));

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        int iReq = indexOf(events, RequireUserConfirmEvent.class);
        int iRes = indexOf(events, UserConfirmResultEvent.class);
        int iEnd = indexOf(events, ToolResultEndEvent.class);

        assertTrue(iReq >= 0, "RequireUserConfirmEvent must be emitted");
        assertTrue(iRes > iReq, "UserConfirmResultEvent must follow RequireUserConfirmEvent");
        assertTrue(iEnd > iRes, "tool-result events must follow confirm result");

        RequireUserConfirmEvent req = (RequireUserConfirmEvent) events.get(iReq);
        assertEquals(1, req.getToolCalls().size());
        assertEquals("tc1", req.getToolCalls().get(0).getId());

        UserConfirmResultEvent res = (UserConfirmResultEvent) events.get(iRes);
        assertEquals(1, res.getConfirmResults().size());
        assertFalse(
                res.getConfirmResults().get(0).isConfirmed(),
                "auto-deny path must surface confirmed=false");

        ToolResultEndEvent end = (ToolResultEndEvent) events.get(iEnd);
        assertEquals(ToolResultState.DENIED, end.getState());
    }

    // ==================== Confirmed path (sink emits allow) ====================

    @Test
    void askingToolWithConfirmedSinkExecutesNormally() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "ask", "ping")),
                                () -> Flux.just(textResponse("done"))));
        Agent2 agent = buildAgent(model, toolkitWith(new AskingTool("ask")));

        Sinks.Many<ConfirmResult> sink = Sinks.many().multicast().onBackpressureBuffer();

        Flux<AgentEvent> stream =
                agent.streamEvents(List.of())
                        .contextWrite(Context.of(Agent2.CONFIRM_SINK_KEY, sink));

        // Pre-emit before subscribing: multicast().onBackpressureBuffer() buffers until subscribed.
        ToolUseBlock pending = ToolUseBlock.builder().id("tc1").name("ask").input(Map.of()).build();
        sink.tryEmitNext(new ConfirmResult(true, pending));

        List<AgentEvent> events = stream.collectList().block();
        assertNotNull(events);

        int iReq = indexOf(events, RequireUserConfirmEvent.class);
        int iRes = indexOf(events, UserConfirmResultEvent.class);
        int iEnd = indexOf(events, ToolResultEndEvent.class);
        assertTrue(iReq >= 0);
        assertTrue(iRes > iReq);
        assertTrue(iEnd > iRes);

        UserConfirmResultEvent res = (UserConfirmResultEvent) events.get(iRes);
        assertTrue(res.getConfirmResults().get(0).isConfirmed());

        ToolResultEndEvent end = (ToolResultEndEvent) events.get(iEnd);
        assertEquals(
                ToolResultState.SUCCESS, end.getState(), "confirmed call must produce SUCCESS");
    }

    // ==================== Denied path (sink emits deny) ====================

    @Test
    void askingToolWithDeniedSinkEmitsDeniedToolResult() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "ask", "x")),
                                () -> Flux.just(textResponse("done"))));
        Agent2 agent = buildAgent(model, toolkitWith(new AskingTool("ask")));

        Sinks.Many<ConfirmResult> sink = Sinks.many().multicast().onBackpressureBuffer();
        ToolUseBlock pending = ToolUseBlock.builder().id("tc1").name("ask").input(Map.of()).build();
        sink.tryEmitNext(new ConfirmResult(false, pending));

        List<AgentEvent> events =
                agent.streamEvents(List.of())
                        .contextWrite(Context.of(Agent2.CONFIRM_SINK_KEY, sink))
                        .collectList()
                        .block();
        assertNotNull(events);

        UserConfirmResultEvent res =
                (UserConfirmResultEvent) events.get(indexOf(events, UserConfirmResultEvent.class));
        assertFalse(res.getConfirmResults().get(0).isConfirmed());

        ToolResultEndEvent end =
                (ToolResultEndEvent) events.get(indexOf(events, ToolResultEndEvent.class));
        assertEquals(ToolResultState.DENIED, end.getState());
    }

    // ==================== Bypass when no tool needs confirmation ====================

    @Test
    void allowingToolBypassesHitlEntirely() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "allow", "x")),
                                () -> Flux.just(textResponse("done"))));
        Agent2 agent = buildAgent(model, toolkitWith(new AllowingTool("allow")));

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        assertEquals(
                0,
                countOf(events, RequireUserConfirmEvent.class),
                "no tool requires confirmation; HITL events must not appear");
        assertEquals(0, countOf(events, UserConfirmResultEvent.class));

        ToolResultEndEvent end =
                (ToolResultEndEvent) events.get(indexOf(events, ToolResultEndEvent.class));
        assertEquals(ToolResultState.SUCCESS, end.getState());
    }
}
