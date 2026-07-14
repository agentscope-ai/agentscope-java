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
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * End-to-end tests for Permission HITL via the persistent-state model. A first {@code call}
 * returns a {@link Msg} with {@link GenerateReason#PERMISSION_ASKING}; callers resume by
 * issuing a second {@code call} carrying {@link ConfirmResult}(s) under
 * {@link Msg#METADATA_CONFIRM_RESULTS}.
 */
class ReActAgentHitlTest {

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
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            Object q = param.getInput() == null ? "" : param.getInput().get("query");
            return Mono.just(ToolResultBlock.text("executed:" + q));
        }
    }

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
                Map<String, Object> toolInput, PermissionContextState context) {
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

    private static ReActAgent buildAgent(ChatModelBase model, Toolkit toolkit) {
        return ReActAgent.builder().name("asst").model(model).toolkit(toolkit).build();
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

    private static Msg confirmMsg(boolean confirmed, ToolUseBlock toolCall) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(
                Msg.METADATA_CONFIRM_RESULTS,
                List.of(new ConfirmResult(confirmed, toolCall, null)));
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("[confirm]")
                .metadata(meta)
                .build();
    }

    @Test
    void askingToolPausesFirstCallAndExecutesOnConfirmedSecondCall() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "ask", "ping")),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent = buildAgent(model, toolkitWith(new AskingTool("ask")));

        // First call: expect to pause with PERMISSION_ASKING
        Msg firstResult = agent.call(List.of()).block();
        assertNotNull(firstResult);
        assertEquals(GenerateReason.PERMISSION_ASKING, firstResult.getGenerateReason());

        // The returned Msg must contain the ASKING ToolUseBlocks so blocking-API
        // callers can extract them to build ConfirmResult (issue #2066).
        List<ToolUseBlock> returnedBlocks = firstResult.getContentBlocks(ToolUseBlock.class);
        assertEquals(
                1, returnedBlocks.size(), "returned Msg must contain the pending ToolUseBlock");
        assertEquals(ToolCallState.ASKING, returnedBlocks.get(0).getState());
        assertEquals("tc1", returnedBlocks.get(0).getId());

        // Also verify state has been persisted consistently
        Msg lastAssistant = null;
        for (int i = agent.getAgentState().getContext().size() - 1; i >= 0; i--) {
            Msg m = agent.getAgentState().getContext().get(i);
            if (m.getRole() == MsgRole.ASSISTANT) {
                lastAssistant = m;
                break;
            }
        }
        assertNotNull(lastAssistant);
        List<ToolUseBlock> blocks = lastAssistant.getContentBlocks(ToolUseBlock.class);
        assertEquals(1, blocks.size());
        assertEquals(ToolCallState.ASKING, blocks.get(0).getState());

        // Second call: resume with a confirmed ConfirmResult
        Msg secondResult = agent.call(List.of(confirmMsg(true, blocks.get(0)))).block();
        assertNotNull(secondResult);
        // Should have proceeded normally to the next reasoning round
        assertTrue(
                secondResult.getGenerateReason() == GenerateReason.MODEL_STOP
                        || secondResult.getGenerateReason() == GenerateReason.TOOL_CALLS,
                "expected normal completion, got " + secondResult.getGenerateReason());
    }

    @Test
    void askingToolEmitsRequireUserConfirmAndRequestStopEvents() {
        ChatModelBase model =
                new ScriptedModel(List.of(() -> Flux.just(toolUseResponse("tc1", "ask", "x"))));
        ReActAgent agent = buildAgent(model, toolkitWith(new AskingTool("ask")));

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        int iReq = indexOf(events, RequireUserConfirmEvent.class);
        int iStop = indexOf(events, RequestStopEvent.class);

        assertTrue(iReq >= 0, "RequireUserConfirmEvent must be emitted");
        assertTrue(iStop > iReq, "RequestStopEvent must follow RequireUserConfirmEvent");

        RequireUserConfirmEvent req = (RequireUserConfirmEvent) events.get(iReq);
        assertEquals(1, req.getToolCalls().size());
        assertEquals("tc1", req.getToolCalls().get(0).getId());

        RequestStopEvent stop = (RequestStopEvent) events.get(iStop);
        assertEquals(GenerateReason.PERMISSION_ASKING, stop.getGenerateReason());
    }

    @Test
    void askingToolResumeWithDeniedConfirmResultProducesDeniedToolResult() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "ask", "x")),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent = buildAgent(model, toolkitWith(new AskingTool("ask")));

        // First call → ASKING
        Msg first = agent.call(List.of()).block();
        assertNotNull(first);
        assertEquals(GenerateReason.PERMISSION_ASKING, first.getGenerateReason());

        Msg lastAssistant = null;
        for (int i = agent.getAgentState().getContext().size() - 1; i >= 0; i--) {
            Msg m = agent.getAgentState().getContext().get(i);
            if (m.getRole() == MsgRole.ASSISTANT) {
                lastAssistant = m;
                break;
            }
        }
        ToolUseBlock pending = lastAssistant.getContentBlocks(ToolUseBlock.class).get(0);

        // Second call → deny
        Msg second = agent.call(List.of(confirmMsg(false, pending))).block();
        assertNotNull(second);

        // Context should contain a DENIED ToolResultBlock for tc1
        boolean foundDenied =
                agent.getAgentState().getContext().stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .anyMatch(
                                tr ->
                                        "tc1".equals(tr.getId())
                                                && tr.getState() == ToolResultState.DENIED);
        assertTrue(foundDenied, "expected a DENIED ToolResultBlock for the rejected tool");
    }

    @Test
    void askingToolWithoutConfirmResultOnResumeThrows() {
        ChatModelBase model =
                new ScriptedModel(List.of(() -> Flux.just(toolUseResponse("tc1", "ask", "x"))));
        ReActAgent agent = buildAgent(model, toolkitWith(new AskingTool("ask")));

        agent.call(List.of()).block(); // pause

        // Calling again without ConfirmResult must fail explicitly
        assertThrows(
                Throwable.class,
                () ->
                        agent.call(
                                        List.of(
                                                Msg.builder()
                                                        .name("user")
                                                        .role(MsgRole.USER)
                                                        .textContent("hi")
                                                        .build()))
                                .block(),
                "expected explicit failure when no ConfirmResult is supplied");
    }

    @Test
    void blockingApiCanExtractToolCallsFromReturnedMsgAndResume() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "ask", "data")),
                                () -> Flux.just(textResponse("completed"))));
        ReActAgent agent = buildAgent(model, toolkitWith(new AskingTool("ask")));

        // First call returns PERMISSION_ASKING with ToolUseBlocks in content
        Msg result = agent.call(List.of()).block();
        assertNotNull(result);
        assertEquals(GenerateReason.PERMISSION_ASKING, result.getGenerateReason());

        // Extract ToolUseBlocks directly from the returned Msg — no state access
        List<ToolUseBlock> pending =
                result.getContent().stream()
                        .filter(b -> b instanceof ToolUseBlock)
                        .map(ToolUseBlock.class::cast)
                        .filter(t -> t.getState() == ToolCallState.ASKING)
                        .toList();
        assertEquals(1, pending.size(), "must find exactly one ASKING tool in returned Msg");

        // Build ConfirmResult from the extracted ToolUseBlock and resume
        Msg resumed = agent.call(List.of(confirmMsg(true, pending.get(0)))).block();
        assertNotNull(resumed);
        assertTrue(
                resumed.getGenerateReason() == GenerateReason.MODEL_STOP
                        || resumed.getGenerateReason() == GenerateReason.TOOL_CALLS,
                "expected normal completion after confirm, got " + resumed.getGenerateReason());
    }

    @Test
    void allowingToolBypassesHitlEntirely() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "allow", "x")),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent = buildAgent(model, toolkitWith(new AllowingTool("allow")));

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        assertEquals(
                0,
                countOf(events, RequireUserConfirmEvent.class),
                "no tool requires confirmation; HITL events must not appear");
        assertEquals(0, countOf(events, RequestStopEvent.class), "no stop should be requested");

        ToolResultEndEvent end =
                (ToolResultEndEvent) events.get(indexOf(events, ToolResultEndEvent.class));
        assertEquals(ToolResultState.SUCCESS, end.getState());
    }
}
