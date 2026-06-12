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
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
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
 * Coverage for the toolTitle resolution paths added in the McpTool-title work:
 * the tool-found / tool-not-found branches of {@code ReasoningStream.resolveToolTitle}
 * and the propagation of a non-default title through the tool-call /
 * tool-result event chain.
 */
class ReActAgentToolTitleTest {

    @Test
    void customToolTitlePropagatesThroughToolCallAndResultEvents() {
        AgentTool tool = new TitledTool("search", "Web Search");
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);

        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "search", "alpha")),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent = ReActAgent.builder().name("asst").model(model).toolkit(toolkit).build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        ToolCallStartEvent callStart = firstOf(events, ToolCallStartEvent.class);
        assertNotNull(callStart);
        assertEquals("search", callStart.getToolCallName());
        assertEquals("Web Search", callStart.getToolCallTitle());

        ToolCallEndEvent callEnd = firstOf(events, ToolCallEndEvent.class);
        assertNotNull(callEnd);
        assertEquals("Web Search", callEnd.getToolCallTitle());

        ToolResultStartEvent resultStart = firstOf(events, ToolResultStartEvent.class);
        assertNotNull(resultStart);
        assertEquals("Web Search", resultStart.getToolCallTitle());

        ToolResultEndEvent resultEnd = firstOf(events, ToolResultEndEvent.class);
        assertNotNull(resultEnd);
        assertEquals("Web Search", resultEnd.getToolCallTitle());
    }

    @Test
    void unknownToolNameYieldsNullTitleInToolCallStart() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new TitledTool("search", "Web Search"));

        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                toolUseResponse(
                                                        "tc1", "ghost_tool_not_registered", "x")),
                                () -> Flux.just(textResponse("fallback"))));
        ReActAgent agent = ReActAgent.builder().name("asst").model(model).toolkit(toolkit).build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        ToolCallStartEvent callStart = firstOf(events, ToolCallStartEvent.class);
        assertNotNull(callStart);
        assertEquals("ghost_tool_not_registered", callStart.getToolCallName());
        assertNull(
                callStart.getToolCallTitle(),
                "unknown tool name should resolve to null title (tool == null branch)");

        ToolCallEndEvent callEnd = firstOf(events, ToolCallEndEvent.class);
        assertNotNull(callEnd);
        assertNull(callEnd.getToolCallTitle());
    }

    private static <T extends AgentEvent> T firstOf(List<AgentEvent> events, Class<T> type) {
        for (AgentEvent e : events) {
            if (type.isInstance(e)) {
                return type.cast(e);
            }
        }
        return null;
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

    private static ChatResponse toolUseResponse(String toolId, String toolName, String inputJson) {
        Map<String, Object> input = new HashMap<>();
        input.put("query", inputJson);
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

    private record TitledTool(String name, String title) implements AgentTool {

        @Override
        public String getTitle() {
            return title;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "titled tool";
        }

        @Override
        public Map<String, Object> getParameters() {
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
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            Object q = param.getInput() == null ? "" : param.getInput().get("query");
            return Mono.just(ToolResultBlock.text("titled:" + q));
        }
    }
}
