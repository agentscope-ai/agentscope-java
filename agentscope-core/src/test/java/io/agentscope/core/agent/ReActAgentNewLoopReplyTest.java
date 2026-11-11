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
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolSuspendException;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.MessageUtils;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

    private static Toolkit toolkitWithExternalSchema() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("external_api")
                        .description("Execute API outside the agent runtime")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("query", Map.of("type", "string"))))
                        .build());
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
    void externalToolCallReturnsSuspendedMessage() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                toolUseResponse(
                                                        "ext1", "external_api", "/users"))));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkitWithExternalSchema())
                        .build();

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertEquals(GenerateReason.TOOL_SUSPENDED, result.getGenerateReason());
        assertEquals(1, result.getContentBlocks(ToolUseBlock.class).size());

        List<ToolResultBlock> toolResults = result.getContentBlocks(ToolResultBlock.class);
        assertEquals(1, toolResults.size());
        ToolResultBlock toolResult = toolResults.get(0);
        assertEquals("ext1", toolResult.getId());
        assertEquals("external_api", toolResult.getName());
        assertTrue(toolResult.isSuspended());
        assertEquals(ToolResultState.SUSPENDED, toolResult.getState());
        assertTrue(
                toolResult.getOutput().isEmpty(),
                "suspended marker must not fabricate tool output");
    }

    @Test
    void externalToolCallEmitsRequireExternalExecutionEvent() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                toolUseResponse(
                                                        "ext1", "external_api", "/users"))));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkitWithExternalSchema())
                        .build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        int iRequireExternal = indexOf(events, RequireExternalExecutionEvent.class);

        assertEquals(
                -1,
                indexOf(events, ToolResultEndEvent.class),
                "a suspended tool must not emit a result end event");
        assertEquals(
                -1,
                indexOf(events, ToolResultTextDeltaEvent.class),
                "a suspended tool must not emit result content");
        assertTrue(iRequireExternal >= 0, "RequireExternalExecutionEvent expected");

        RequireExternalExecutionEvent event =
                (RequireExternalExecutionEvent) events.get(iRequireExternal);
        assertEquals(1, event.getToolCalls().size());
        assertEquals("ext1", event.getToolCalls().get(0).getId());
        assertEquals("external_api", event.getToolCalls().get(0).getName());
    }

    @Test
    void externalToolResultResumeEmitsExternalExecutionResultEvent() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("ext1", "external_api", "/users")),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkitWithExternalSchema())
                        .build();

        List<AgentEvent> firstEvents = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(firstEvents);
        int iRequireExternal = indexOf(firstEvents, RequireExternalExecutionEvent.class);
        assertTrue(iRequireExternal >= 0, "RequireExternalExecutionEvent expected");

        RequireExternalExecutionEvent requireEvent =
                (RequireExternalExecutionEvent) firstEvents.get(iRequireExternal);
        ToolResultBlock externalResult =
                ToolResultBlock.builder()
                        .id("ext1")
                        .name("external_api")
                        .output(TextBlock.builder().text("external result").build())
                        .state(ToolResultState.SUCCESS)
                        .build();
        Msg resumeMsg = Msg.builder().role(MsgRole.TOOL).content(externalResult).build();

        List<AgentEvent> resumedEvents =
                agent.streamEvents(List.of(resumeMsg)).collectList().block();
        assertNotNull(resumedEvents);

        int iExternalResult = indexOf(resumedEvents, ExternalExecutionResultEvent.class);
        int iModelStart = indexOf(resumedEvents, ModelCallStartEvent.class);
        assertTrue(iExternalResult >= 0, "ExternalExecutionResultEvent expected");
        assertTrue(
                iModelStart > iExternalResult,
                "ExternalExecutionResultEvent should be emitted before resumed reasoning");

        ExternalExecutionResultEvent resultEvent =
                (ExternalExecutionResultEvent) resumedEvents.get(iExternalResult);
        assertEquals(requireEvent.getReplyId(), resultEvent.getReplyId());
        assertEquals(1, resultEvent.getToolResults().size());
        assertEquals("ext1", resultEvent.getToolResults().get(0).getId());

        AgentResultEvent agentResult =
                (AgentResultEvent)
                        resumedEvents.get(indexOf(resumedEvents, AgentResultEvent.class));
        assertEquals("done", agentResult.getResult().getTextContent());
    }

    private enum ExternalCheckpoint {
        CURRENT,
        LEGACY_NATIVE_LOG,
        LEGACY_STATE_ONLY
    }

    @ParameterizedTest
    @EnumSource(ExternalCheckpoint.class)
    void partialExternalResultsKeepTheOriginalWaitAndReplyId(ExternalCheckpoint checkpoint) {
        var first = toolUseResponse("ext1", "external_api", "A").getContent().get(0);
        var second = toolUseResponse("ext2", "external_api", "B").getContent().get(0);
        var local = toolUseResponse("local", "echo", "C").getContent().get(0);
        var batch = ChatResponse.builder().content(List.of(first, second, local)).build();
        var model =
                new ScriptedModel(
                        List.of(() -> Flux.just(batch), () -> Flux.just(textResponse("done"))));
        var toolkit =
                toolkitWith(
                        new ToolBase(
                                ToolBase.builder()
                                        .name("external_api")
                                        .description("Execute API outside the agent runtime")
                                        .inputSchema(Map.of("type", "object"))) {
                            @Override
                            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                                return Mono.error(new ToolSuspendException("Run the external API"));
                            }
                        });
        toolkit.registerAgentTool(new EchoTool());
        var deferLocalTool =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onActing(
                            Agent agent,
                            RuntimeContext ctx,
                            ActingInput input,
                            Function<ActingInput, Flux<AgentEvent>> next) {
                        if (input.toolCalls().size() == 1) return next.apply(input);
                        return next.apply(
                                new ActingInput(
                                        input.toolCalls().stream()
                                                .filter(call -> !call.getId().equals("local"))
                                                .map(
                                                        call -> {
                                                            if (!call.getId().equals("ext2"))
                                                                return call;
                                                            return ToolUseBlock.builder()
                                                                    .id(call.getId())
                                                                    .name(call.getName())
                                                                    .input(
                                                                            Map.of(
                                                                                    "query",
                                                                                    "rewritten B"))
                                                                    .build();
                                                        })
                                                .toList()));
                    }
                };
        var builder =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkit)
                        .middlewares(List.of(deferLocalTool));
        if (checkpoint == ExternalCheckpoint.LEGACY_NATIVE_LOG)
            builder.sessionLogStore(new InMemorySessionLogStore());
        var agent = builder.build();
        var context = RuntimeContext.builder().userId("u").sessionId("s").build();
        var timeout = Duration.ofSeconds(10);
        var initialEvents = agent.streamEvents(List.of(), context).collectList().block(timeout);
        var request =
                (RequireExternalExecutionEvent)
                        initialEvents.get(
                                indexOf(initialEvents, RequireExternalExecutionEvent.class));
        assertEquals(2, request.getToolCalls().size());
        var requestedB =
                request.getToolCalls().stream()
                        .filter(call -> call.getId().equals("ext2"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(Map.of("query", "rewritten B"), requestedB.getInput());

        if (checkpoint == ExternalCheckpoint.LEGACY_NATIVE_LOG) {
            var recorder =
                    new SessionRecorder(agent.sessionLog(context), request.getExecution().turnId());
            try {
                var state = recorder.initial().restore();
                replaceWithLegacyExternalWait(state, request.getReplyId());
                recorder.checkpointNow(state, "legacy_external_wait");
            } finally {
                recorder.close();
            }
        } else {
            var state = agent.getAgentState(context);
            if (checkpoint == ExternalCheckpoint.LEGACY_STATE_ONLY)
                replaceWithLegacyExternalWait(state, request.getReplyId());
            var restored = AgentState.fromJsonString(state.toJson());
            state.contextMutable().clear();
            state.contextMutable().addAll(restored.getContext());
        }

        var resultA = ToolResultBlock.success("A").withIdAndName("ext1", "external_api");
        var partial =
                agent.streamEvents(
                                List.of(Msg.builder().role(MsgRole.TOOL).content(resultA).build()),
                                RuntimeContext.builder(context).runId(null).build())
                        .collectList()
                        .block(timeout);
        var acceptedA =
                (ExternalExecutionResultEvent)
                        partial.get(indexOf(partial, ExternalExecutionResultEvent.class));
        assertEquals(request.getReplyId(), acceptedA.getReplyId());
        assertEquals(-1, indexOf(partial, ModelCallStartEvent.class));
        var waiting =
                ((AgentResultEvent) partial.get(indexOf(partial, AgentResultEvent.class)))
                        .getResult();
        assertEquals(GenerateReason.TOOL_SUSPENDED, waiting.getGenerateReason());
        var waitingCalls = waiting.getContentBlocks(ToolUseBlock.class);
        assertEquals(List.of("ext2"), waitingCalls.stream().map(ToolUseBlock::getId).toList());
        var expectedReplyId = request.getReplyId();
        if (checkpoint == ExternalCheckpoint.LEGACY_STATE_ONLY) {
            var redispatched =
                    (RequireExternalExecutionEvent)
                            partial.get(indexOf(partial, RequireExternalExecutionEvent.class));
            assertNotEquals(expectedReplyId, redispatched.getReplyId());
            expectedReplyId = redispatched.getReplyId();
        } else {
            assertEquals(-1, indexOf(partial, RequireExternalExecutionEvent.class));
            assertEquals(-1, indexOf(partial, ToolResultStartEvent.class));
        }
        if (checkpoint != ExternalCheckpoint.LEGACY_STATE_ONLY) {
            assertEquals(requestedB.getInput(), waitingCalls.get(0).getInput());
            assertEquals(
                    "Run the external API",
                    waiting.getContentBlocks(ToolResultBlock.class).get(0).getSuspendReason());
        }
        if (checkpoint == ExternalCheckpoint.LEGACY_NATIVE_LOG)
            assertNotEquals(request.getExecution().turnId(), acceptedA.getExecution().turnId());

        var resultB = ToolResultBlock.success("B").withIdAndName("ext2", "external_api");
        var completed =
                agent.streamEvents(
                                List.of(Msg.builder().role(MsgRole.TOOL).content(resultB).build()),
                                RuntimeContext.builder(context).runId(null).build())
                        .collectList()
                        .block(timeout);
        var acceptedB =
                (ExternalExecutionResultEvent)
                        completed.get(indexOf(completed, ExternalExecutionResultEvent.class));
        assertEquals(expectedReplyId, acceptedB.getReplyId());
        var localResult =
                (ToolResultEndEvent) completed.get(indexOf(completed, ToolResultEndEvent.class));
        assertEquals("local", localResult.getToolCallId());
        assertEquals(ToolResultState.SUCCESS, localResult.getState());
        assertEquals(
                "done",
                ((AgentResultEvent) completed.get(indexOf(completed, AgentResultEvent.class)))
                        .getResult()
                        .getTextContent());
    }

    private static void replaceWithLegacyExternalWait(AgentState state, String replyId) {
        var oldCalls = new HashMap<String, ToolUseBlock>();
        // A prior permission pass can leave even the deferred local call ALLOWED.
        for (var call : MessageUtils.extractRecentToolCalls(state.getContext(), "asst"))
            oldCalls.put(call.getId(), call.withState(ToolCallState.ALLOWED));
        assertTrue(MessageUtils.replaceToolUseBlocks(state.contextMutable(), oldCalls));
        var assistant = MessageUtils.lastAssistantMessage(state.getContext());
        assertTrue(
                MessageUtils.replaceLastMessageByRole(
                        state.contextMutable(),
                        MsgRole.ASSISTANT,
                        assistant.withMetadata(
                                Map.of(
                                        Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID,
                                        replyId))));
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
    void textSeparatedByToolCallUsesDistinctBlockIds() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                chatResponse(
                                                        TextBlock.builder().text("before").build()),
                                                chatResponse(
                                                        ToolUseBlock.builder()
                                                                .id("tc1")
                                                                .name("echo")
                                                                .input(Map.of("query", "ping"))
                                                                .build()),
                                                chatResponse(
                                                        TextBlock.builder().text("after").build())),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkitWith(new EchoTool()))
                        .build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        int firstModelEnd = indexOf(events, ModelCallEndEvent.class);
        List<TextBlockStartEvent> starts =
                events.subList(0, firstModelEnd).stream()
                        .filter(TextBlockStartEvent.class::isInstance)
                        .map(TextBlockStartEvent.class::cast)
                        .toList();
        List<TextBlockEndEvent> ends =
                events.subList(0, firstModelEnd).stream()
                        .filter(TextBlockEndEvent.class::isInstance)
                        .map(TextBlockEndEvent.class::cast)
                        .toList();
        List<TextBlockDeltaEvent> deltas =
                events.subList(0, firstModelEnd).stream()
                        .filter(TextBlockDeltaEvent.class::isInstance)
                        .map(TextBlockDeltaEvent.class::cast)
                        .toList();

        assertEquals(
                List.of("text", "text-2"),
                starts.stream().map(TextBlockStartEvent::getBlockId).toList());
        assertEquals(
                List.of("text", "text-2"),
                deltas.stream().map(TextBlockDeltaEvent::getBlockId).toList());
        assertEquals(
                List.of("text", "text-2"),
                ends.stream().map(TextBlockEndEvent::getBlockId).toList());
    }

    @Test
    void thinkingSeparatedByToolCallUsesDistinctBlockIds() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                chatResponse(
                                                        ThinkingBlock.builder()
                                                                .thinking("before")
                                                                .build()),
                                                chatResponse(
                                                        ToolUseBlock.builder()
                                                                .id("tc1")
                                                                .name("echo")
                                                                .input(Map.of("query", "ping"))
                                                                .build()),
                                                chatResponse(
                                                        ThinkingBlock.builder()
                                                                .thinking("after")
                                                                .build())),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkitWith(new EchoTool()))
                        .build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        int firstModelEnd = indexOf(events, ModelCallEndEvent.class);
        List<ThinkingBlockStartEvent> starts =
                events.subList(0, firstModelEnd).stream()
                        .filter(ThinkingBlockStartEvent.class::isInstance)
                        .map(ThinkingBlockStartEvent.class::cast)
                        .toList();
        List<ThinkingBlockEndEvent> ends =
                events.subList(0, firstModelEnd).stream()
                        .filter(ThinkingBlockEndEvent.class::isInstance)
                        .map(ThinkingBlockEndEvent.class::cast)
                        .toList();
        List<ThinkingBlockDeltaEvent> deltas =
                events.subList(0, firstModelEnd).stream()
                        .filter(ThinkingBlockDeltaEvent.class::isInstance)
                        .map(ThinkingBlockDeltaEvent.class::cast)
                        .toList();

        assertEquals(
                List.of("thinking", "thinking-2"),
                starts.stream().map(ThinkingBlockStartEvent::getBlockId).toList());
        assertEquals(
                List.of("thinking", "thinking-2"),
                deltas.stream().map(ThinkingBlockDeltaEvent::getBlockId).toList());
        assertEquals(
                List.of("thinking", "thinking-2"),
                ends.stream().map(ThinkingBlockEndEvent::getBlockId).toList());
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

    @Test
    void summaryFailurePreservesMaxIterationsAndMarksFailure() {
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc", "echo", "x")),
                                () -> Flux.error(new RuntimeException("summary failed"))));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkitWith(new EchoTool()))
                        .maxIters(1)
                        .build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();

        assertNotNull(events);
        AgentResultEvent resultEvent =
                (AgentResultEvent) events.get(indexOf(events, AgentResultEvent.class));
        Msg result = resultEvent.getResult();
        assertEquals(GenerateReason.MAX_ITERATIONS, result.getGenerateReason());
        assertEquals(Boolean.TRUE, result.getMetadata().get(MessageMetadataKeys.SUMMARY_FAILED));
        assertTrue(result.getTextContent().contains("Error generating summary"));
    }

    @Test
    void summaryModelCallExposesEmptyToolsToMiddleware() {
        AtomicInteger modelCallCount = new AtomicInteger();
        AtomicReference<List<ToolSchema>> summaryTools = new AtomicReference<>();
        MiddlewareBase middleware =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onModelCall(
                            Agent agent,
                            RuntimeContext ctx,
                            ModelCallInput input,
                            Function<ModelCallInput, Flux<AgentEvent>> next) {
                        if (modelCallCount.incrementAndGet() == 2) {
                            summaryTools.set(List.copyOf(input.tools()));
                        }
                        return next.apply(input);
                    }
                };
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc", "echo", "x")),
                                () -> Flux.just(textResponse("summary"))));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkitWith(new EchoTool()))
                        .middlewares(List.of(middleware))
                        .maxIters(1)
                        .build();

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();

        assertNotNull(events);
        assertEquals(List.of(), summaryTools.get());
        AgentResultEvent resultEvent =
                (AgentResultEvent) events.get(indexOf(events, AgentResultEvent.class));
        assertEquals("summary", resultEvent.getResult().getTextContent());
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
