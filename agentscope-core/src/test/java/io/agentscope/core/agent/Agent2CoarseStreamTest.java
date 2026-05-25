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

import io.agentscope.core.agent.config.ReactConfig;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
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

/**
 * Exercises the coarse {@code Flux<Event>} surface produced by {@link Agent2#stream(List,
 * StreamOptions)} — verifies that the real {@code adaptToCoarseEvent} translation produces
 * REASONING / TOOL_RESULT / SUMMARY events with the expected {@code isLast} flags.
 */
class Agent2CoarseStreamTest {

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

    private static ChatResponse toolUseResponse(String id, String name, String q) {
        Map<String, Object> in = new HashMap<>();
        in.put("query", q);
        return ChatResponse.builder()
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder().id(id).name(name).input(in).build()))
                .build();
    }

    private static final class EchoTool extends ToolBase {
        EchoTool() {
            super("echo", "echoes input", schema(), true, true, false, null, false, false);
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
                Map<String, Object> input, PermissionContext ctx) {
            return Mono.just(PermissionDecision.allow("ok"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            Object q = param.getInput() == null ? "" : param.getInput().get("query");
            return Mono.just(ToolResultBlock.text("echoed:" + q));
        }
    }

    @Test
    void coarseStreamYieldsTerminalReasoningAndToolResultEvents() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("c1", "echo", "ping")),
                                () -> Flux.just(textResponse("all-done"))));
        Toolkit tk = new Toolkit();
        tk.registerAgentTool(new EchoTool());

        AgentState state = AgentState.builder().sessionId("coarse").build();

        Agent2 agent = new Agent2("asst", null, model, tk, List.of(), state, null, null, null);

        List<Event> events =
                agent.stream(List.of(), StreamOptions.defaults()).collectList().block();
        assertNotNull(events);
        assertTrue(events.size() > 0, "coarse stream must be non-empty");

        // At least one terminal REASONING (isLast=true) — from ModelCallEndEvent.
        long terminalReasoning =
                events.stream()
                        .filter(e -> e.getType() == EventType.REASONING && e.isLast())
                        .count();
        assertTrue(
                terminalReasoning >= 1,
                "expected at least one terminal REASONING event, got " + terminalReasoning);

        // Exactly one terminal TOOL_RESULT (isLast=true) — from the single ToolResultEndEvent.
        long terminalToolResult =
                events.stream()
                        .filter(e -> e.getType() == EventType.TOOL_RESULT && e.isLast())
                        .count();
        assertEquals(
                1L,
                terminalToolResult,
                "expected exactly one terminal TOOL_RESULT event from the single tool call");
    }

    @Test
    void coarseStreamEmitsSummaryWhenMaxItersExceeded() {
        // Always return a tool call → reactLoop will never terminate via text; ReactConfig with
        // tiny maxIters triggers ExceedMaxItersEvent → mapped to SUMMARY(isLast=true).
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("c1", "echo", "x1")),
                                () -> Flux.just(toolUseResponse("c2", "echo", "x2"))));
        Toolkit tk = new Toolkit();
        tk.registerAgentTool(new EchoTool());

        AgentState state = AgentState.builder().sessionId("coarse-max").build();
        ReactConfig react = new ReactConfig(1, false);

        Agent2 agent = new Agent2("asst", null, model, tk, List.of(), state, null, null, react);

        List<Event> events =
                agent.stream(List.of(), StreamOptions.defaults()).collectList().block();
        assertNotNull(events);
        long terminalSummary =
                events.stream().filter(e -> e.getType() == EventType.SUMMARY && e.isLast()).count();
        assertEquals(
                1L,
                terminalSummary,
                "exceeding maxIters must emit exactly one terminal SUMMARY event");
    }
}
