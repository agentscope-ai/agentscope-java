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
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
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

/** End-to-end tests for the ReAct reply loop. */
class ReActAgentNewLoopReplyTest {

    private static AgentState newState() {
        return AgentState.builder().sessionId("session-1").build();
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

    private static Toolkit toolkitWith(AgentTool tool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        return toolkit;
    }

    private static final class EchoTool implements AgentTool {
        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public String getDescription() {
            return "echoes the input";
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
            return Mono.just(ToolResultBlock.text("echo:" + q));
        }
    }

    @Test
    void textOnlyReplyEmitsExpectedEventOrder() {
        ChatModelBase model =
                new ScriptedModel(List.of(() -> Flux.just(textResponse("hello world"))));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("you are helpful")
                        .model(model)
                        .toolkit(new Toolkit())
                        .build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        assertTrue(events.get(0) instanceof AgentStartEvent);
        assertTrue(events.get(events.size() - 1) instanceof AgentEndEvent);
        long modelStarts = events.stream().filter(e -> e instanceof ModelCallStartEvent).count();
        long modelEnds = events.stream().filter(e -> e instanceof ModelCallEndEvent).count();
        assertEquals(1L, modelStarts);
        assertEquals(1L, modelEnds);
    }

    @Test
    void callResolvesToFinalAssistantMsg() {
        ChatModelBase model = new ScriptedModel(List.of(() -> Flux.just(textResponse("answer"))));
        ReActAgent agent =
                ReActAgent.builder().name("asst").model(model).toolkit(new Toolkit()).build();

        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        Msg result = agent.call(List.of(userMsg)).block();

        assertNotNull(result);
        assertEquals(MsgRole.ASSISTANT, result.getRole());
        List<TextBlock> texts = result.getContentBlocks(TextBlock.class);
        assertEquals(1, texts.size());
        assertEquals("answer", texts.get(0).getText());
        assertTrue(
                agent.getAgentState().getContext().size() >= 2,
                "user + assistant expected in state");
    }

    @Test
    void toolCallIterationThenTextTerminates() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "echo", "ping")),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkitWith(new EchoTool()))
                        .build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        int iReplyStart = indexOf(events, AgentStartEvent.class);
        int iToolCallStart = indexOf(events, ToolCallStartEvent.class);
        int iToolCallEnd = indexOf(events, ToolCallEndEvent.class);
        int iModelCallEnd1 = indexOf(events, ModelCallEndEvent.class);
        int iToolResultStart = indexOf(events, ToolResultStartEvent.class);
        int iToolResultEnd = indexOf(events, ToolResultEndEvent.class);
        int iReplyEnd = indexOf(events, AgentEndEvent.class);

        assertTrue(iReplyStart >= 0);
        assertTrue(iToolCallStart > iReplyStart);
        assertTrue(iToolCallEnd > iToolCallStart);
        assertTrue(iModelCallEnd1 > iToolCallEnd);
        assertTrue(iToolResultStart > iModelCallEnd1);
        assertTrue(iToolResultEnd > iToolResultStart);
        assertTrue(iReplyEnd > iToolResultEnd);

        int firstModelStart = indexOf(events, ModelCallStartEvent.class);
        int secondModelStart = indexOfFrom(events, ModelCallStartEvent.class, firstModelStart + 1);
        assertTrue(secondModelStart > iToolResultEnd, "second iteration should follow tool result");
    }

    @Test
    void maxItersOverflowEmitsExceedMaxItersEvent() {
        Supplier<Flux<ChatResponse>> loop = () -> Flux.just(toolUseResponse("tc", "echo", "x"));
        ChatModelBase model = new ScriptedModel(List.of(loop, loop, loop, loop, loop));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkitWith(new EchoTool()))
                        .maxIters(2)
                        .build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        int iExceed = indexOf(events, ExceedMaxItersEvent.class);
        assertTrue(iExceed >= 0, "ExceedMaxItersEvent expected when maxIters is reached");
        ExceedMaxItersEvent overflow = (ExceedMaxItersEvent) events.get(iExceed);
        assertEquals(2, overflow.getMaxIters());
    }

    @Test
    void textStartClosesThinkingBeforeTextBegins() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                chatResponse(
                                                        ThinkingBlock.builder()
                                                                .thinking("think")
                                                                .build(),
                                                        TextBlock.builder()
                                                                .text("text")
                                                                .build()))));
        ReActAgent agent =
                ReActAgent.builder().name("asst").model(model).toolkit(new Toolkit()).build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        int thinkingStart = indexOf(events, ThinkingBlockStartEvent.class);
        int thinkingEnd = indexOf(events, ThinkingBlockEndEvent.class);
        int textStart = indexOf(events, TextBlockStartEvent.class);
        assertTrue(thinkingStart >= 0);
        assertTrue(thinkingEnd > thinkingStart);
        assertTrue(textStart > thinkingEnd);
    }

    @Test
    void toolCallStartClosesTextThinkingAndPreviousTools() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                chatResponse(
                                                        ThinkingBlock.builder()
                                                                .thinking("think")
                                                                .build(),
                                                        TextBlock.builder().text("text").build(),
                                                        ToolUseBlock.builder()
                                                                .id("tc1")
                                                                .name("calc")
                                                                .input(Map.of("query", "1"))
                                                                .build(),
                                                        ToolUseBlock.builder()
                                                                .id("tc2")
                                                                .name("search")
                                                                .input(Map.of("query", "2"))
                                                                .build()))));
        ReActAgent agent =
                ReActAgent.builder().name("asst").model(model).toolkit(new Toolkit()).build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        int thinkingEnd = indexOf(events, ThinkingBlockEndEvent.class);
        int textStart = indexOf(events, TextBlockStartEvent.class);
        int textEnd = indexOf(events, TextBlockEndEvent.class);
        int tool1Start = indexOf(events, ToolCallStartEvent.class);
        int tool1End = indexOf(events, ToolCallEndEvent.class);
        int tool2Start = indexOfFrom(events, ToolCallStartEvent.class, tool1Start + 1);

        assertTrue(thinkingEnd >= 0);
        assertTrue(textStart > thinkingEnd);
        assertTrue(textEnd > textStart);
        assertTrue(tool1Start > thinkingEnd);
        assertTrue(tool1Start > textEnd);
        assertTrue(tool1End > tool1Start);
        assertTrue(tool2Start > tool1End);
    }

    @Test
    void modelEndFlushesRemainingTextAndThinking() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                chatResponse(
                                                        ThinkingBlock.builder()
                                                                .thinking("think")
                                                                .build(),
                                                        TextBlock.builder()
                                                                .text("text")
                                                                .build()))));
        ReActAgent agent =
                ReActAgent.builder().name("asst").model(model).toolkit(new Toolkit()).build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        int modelEnd = indexOf(events, ModelCallEndEvent.class);
        int textEnd = indexOf(events, TextBlockEndEvent.class);
        int thinkingEnd = indexOf(events, ThinkingBlockEndEvent.class);
        assertTrue(textEnd > 0 && textEnd < modelEnd);
        assertTrue(thinkingEnd > 0 && thinkingEnd < modelEnd);
    }

    @Test
    void consecutiveTextChunksEmitSingleStartAndEnd() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                chatResponse(
                                                        TextBlock.builder().text("hello").build()),
                                                chatResponse(
                                                        TextBlock.builder()
                                                                .text(" world")
                                                                .build()))));
        ReActAgent agent =
                ReActAgent.builder().name("asst").model(model).toolkit(new Toolkit()).build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        assertEquals(1L, countOf(events, TextBlockStartEvent.class));
        assertEquals(1L, countOf(events, TextBlockEndEvent.class));
        assertTrue(
                indexOf(events, TextBlockEndEvent.class)
                        < indexOf(events, ModelCallEndEvent.class));
    }

    @Test
    void summaryModelCallClosesThinkingBeforeTextAndFlushesTextBeforeModelEnd() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc", "echo", "x")),
                                () ->
                                        Flux.just(
                                                chatResponse(
                                                        ThinkingBlock.builder()
                                                                .thinking("summarizing")
                                                                .build(),
                                                        TextBlock.builder()
                                                                .text("summary")
                                                                .build()))));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkitWith(new EchoTool()))
                        .maxIters(1)
                        .build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        int exceed = indexOf(events, ExceedMaxItersEvent.class);
        int summaryModelStart = indexOfFrom(events, ModelCallStartEvent.class, exceed + 1);
        int summaryThinkingEnd =
                indexOfFrom(events, ThinkingBlockEndEvent.class, summaryModelStart);
        int summaryTextStart = indexOfFrom(events, TextBlockStartEvent.class, summaryModelStart);
        int summaryTextEnd = indexOfFrom(events, TextBlockEndEvent.class, summaryTextStart);
        int summaryModelEnd = indexOfFrom(events, ModelCallEndEvent.class, summaryModelStart);

        assertTrue(exceed >= 0);
        assertTrue(summaryModelStart > exceed);
        assertTrue(summaryThinkingEnd > summaryModelStart);
        assertTrue(summaryTextStart > summaryThinkingEnd);
        assertTrue(summaryTextEnd > summaryTextStart);
        assertTrue(summaryModelEnd > summaryTextEnd);
    }

    private static int indexOf(List<AgentEvent> events, Class<?> type) {
        return indexOfFrom(events, type, 0);
    }

    private static int indexOfFrom(List<AgentEvent> events, Class<?> type, int start) {
        for (int i = start; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static long countOf(List<AgentEvent> events, Class<?> type) {
        return events.stream().filter(type::isInstance).count();
    }

    private static ChatResponse chatResponse(ContentBlock... blocks) {
        return ChatResponse.builder().content(List.of(blocks)).build();
    }
}
