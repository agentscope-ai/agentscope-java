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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * End-to-end ReAct loop: tool-use → tool-result → text-terminate, with two tools and a recording
 * middleware. Verifies the canonical event order and the final reply text.
 */
class ReActAgentNewLoopE2ETest {

    private static final class ScriptedModel extends ChatModelBase {
        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger idx = new AtomicInteger(0);
        final AtomicInteger calls = new AtomicInteger(0);

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
            calls.incrementAndGet();
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

    private static ChatResponse toolUseResponse(String id, String name, String q) {
        Map<String, Object> in = new HashMap<>();
        in.put("query", q);
        return ChatResponse.builder()
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder().id(id).name(name).input(in).build()))
                .build();
    }

    private static final class AlwaysAllowTool extends ToolBase {
        AlwaysAllowTool(String name) {
            super(name, "always allow", schema(), true, true, false, null, false, false);
        }

        private static Map<String, Object> schema() {
            Map<String, Object> s = new HashMap<>();
            s.put("type", "object");
            Map<String, Object> props = new HashMap<>();
            Map<String, Object> q = new HashMap<>();
            q.put("type", "string");
            props.put("query", q);
            s.put("properties", props);
            return s;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> input, PermissionContextState ctx) {
            return Mono.just(PermissionDecision.allow("ok"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            Object q = param.getInput() == null ? "" : param.getInput().get("query");
            return Mono.just(ToolResultBlock.text(getName() + ":" + q));
        }
    }

    private static final class ReturningErrorTool extends ToolBase {
        ReturningErrorTool(String name) {
            super(
                    name,
                    "returns structured error",
                    AlwaysAllowTool.schema(),
                    true,
                    true,
                    false,
                    null,
                    false,
                    false);
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> input, PermissionContextState ctx) {
            return Mono.just(PermissionDecision.allow("ok"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.error("probe failed"));
        }
    }

    private static final class RecordingMiddleware implements MiddlewareBase {
        final List<String> trace = new ArrayList<>();

        @Override
        public Flux<AgentEvent> onAgent(
                Agent agent,
                RuntimeContext ctx,
                AgentInput input,
                Function<AgentInput, Flux<AgentEvent>> next) {
            trace.add("reply:enter");
            return next.apply(input).doOnComplete(() -> trace.add("reply:exit"));
        }

        @Override
        public Flux<AgentEvent> onActing(
                Agent agent,
                RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            trace.add("acting:enter");
            return next.apply(input).doOnComplete(() -> trace.add("acting:exit"));
        }
    }

    @Test
    void twoToolReactLoopProducesOrderedEventsAndFinalText() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("c1", "search", "alpha")),
                                () -> Flux.just(toolUseResponse("c2", "lookup", "beta")),
                                () -> Flux.just(textResponse("done-final"))));
        Toolkit tk = new Toolkit();
        tk.registerAgentTool(new AlwaysAllowTool("search"));
        tk.registerAgentTool(new AlwaysAllowTool("lookup"));

        RecordingMiddleware mw = new RecordingMiddleware();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("you are helpful")
                        .model(model)
                        .toolkit(tk)
                        .middleware(mw)
                        .build();
        AgentState state = agent.getAgentState();

        List<AgentEvent> events =
                agent.streamEvents(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("find me alpha then beta")
                                                .build()))
                        .collectList()
                        .block();
        assertNotNull(events);

        assertEquals(3, model.calls.get(), "model must be called once per ReAct iteration");

        assertTrue(events.get(0) instanceof AgentStartEvent, "first event must be AgentStartEvent");
        assertTrue(
                events.get(events.size() - 1) instanceof AgentEndEvent,
                "last event must be AgentEndEvent");

        long modelEnds = events.stream().filter(e -> e instanceof ModelCallEndEvent).count();
        assertEquals(3L, modelEnds);

        assertEquals(2L, events.stream().filter(e -> e instanceof ToolCallEndEvent).count());
        long toolResEnds = events.stream().filter(e -> e instanceof ToolResultEndEvent).count();
        assertEquals(2L, toolResEnds);

        events.stream()
                .filter(e -> e instanceof ToolResultEndEvent)
                .forEach(
                        e ->
                                assertEquals(
                                        ToolResultState.SUCCESS,
                                        ((ToolResultEndEvent) e).getState()));

        assertTrue(mw.trace.contains("reply:enter"), mw.trace.toString());
        assertTrue(mw.trace.contains("reply:exit"), mw.trace.toString());
        assertTrue(mw.trace.contains("acting:enter"), mw.trace.toString());
        assertTrue(mw.trace.contains("acting:exit"), mw.trace.toString());

        List<Msg> ctx = state.getContext();
        boolean hasFinalText =
                ctx.stream()
                        .filter(m -> m.getRole() == MsgRole.ASSISTANT)
                        .flatMap(m -> m.getContentBlocks(TextBlock.class).stream())
                        .anyMatch(tb -> tb.getText().equals("done-final"));
        assertTrue(hasFinalText, "final assistant text 'done-final' must be in state.context");
    }

    @Test
    void toolReturningErrorBlockEmitsErrorResultEndState() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("c1", "failing", "probe")),
                                () -> Flux.just(textResponse("handled"))));
        Toolkit tk = new Toolkit();
        tk.registerAgentTool(new ReturningErrorTool("failing"));

        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("you are helpful")
                        .model(model)
                        .toolkit(tk)
                        .build();

        List<AgentEvent> events =
                agent.streamEvents(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("run the failing tool")
                                                .build()))
                        .collectList()
                        .block();
        assertNotNull(events);

        ToolResultEndEvent end =
                events.stream()
                        .filter(ToolResultEndEvent.class::isInstance)
                        .map(ToolResultEndEvent.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertEquals(ToolResultState.ERROR, end.getState());
    }
}
