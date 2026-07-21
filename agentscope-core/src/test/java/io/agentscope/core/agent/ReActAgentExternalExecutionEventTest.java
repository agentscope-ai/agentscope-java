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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
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
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ReActAgentExternalExecutionEventTest {

    private static final String REPLY_ID = "reply-external-1";
    private static final String TOOL_CALL_ID = "tool-call-1";
    private static final String TOOL_NAME = "request_approval";

    @Test
    void suspendedToolEmitsOneRequestAndTypedStopWithoutPlaceholderResultEvents() {
        ReActAgent agent =
                buildAgent(new ScriptedModel(toolResponse(), textResponse("unused")), null);

        List<AgentEvent> events =
                agent.streamEvents(userMessage("approve this")).collectList().block();

        assertNotNull(events);
        List<RequireExternalExecutionEvent> requests =
                eventsOf(events, RequireExternalExecutionEvent.class);
        assertEquals(1, requests.size());
        assertEquals(REPLY_ID, requests.get(0).getReplyId());
        assertEquals(
                List.of(TOOL_CALL_ID),
                requests.get(0).getToolCalls().stream().map(ToolUseBlock::getId).toList());

        List<RequestStopEvent> stops = eventsOf(events, RequestStopEvent.class);
        assertEquals(1, stops.size());
        assertEquals(GenerateReason.TOOL_SUSPENDED, stops.get(0).getGenerateReason());
        assertTrue(events.indexOf(requests.get(0)) < events.indexOf(stops.get(0)));

        assertFalse(hasTerminalToolResultEvent(events, TOOL_CALL_ID));
        AgentResultEvent result = eventsOf(events, AgentResultEvent.class).get(0);
        assertEquals(GenerateReason.TOOL_SUSPENDED, result.getResult().getGenerateReason());
    }

    @Test
    void sameInstanceResumeEmitsAcceptedResultsOnceWithOriginalReplyIdentity() {
        ReActAgent agent =
                buildAgent(new ScriptedModel(toolResponse(), textResponse("approved")), null);
        RequireExternalExecutionEvent request =
                firstRequest(agent.streamEvents(userMessage("approve")).collectList().block());

        List<AgentEvent> resumed =
                agent.streamEvents(toolResultMessage("approved")).collectList().block();

        List<ExternalExecutionResultEvent> resultEvents =
                eventsOf(resumed, ExternalExecutionResultEvent.class);
        assertEquals(1, resultEvents.size());
        assertEquals(request.getReplyId(), resultEvents.get(0).getReplyId());
        assertEquals(
                List.of(TOOL_CALL_ID),
                resultEvents.get(0).getToolResults().stream().map(ToolResultBlock::getId).toList());
        assertTrue(eventsOf(resumed, RequireExternalExecutionEvent.class).isEmpty());
    }

    @Test
    void freshAgentResumePreservesRequestIdentityFromPersistedState() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        RuntimeContext context =
                RuntimeContext.builder().userId("user-1").sessionId("session-1").build();
        ReActAgent firstAgent = buildAgent(new ScriptedModel(toolResponse()), store);

        RequireExternalExecutionEvent request =
                firstRequest(
                        firstAgent
                                .streamEvents(userMessage("approve"), context)
                                .collectList()
                                .block());

        ReActAgent rebuiltAgent = buildAgent(new ScriptedModel(textResponse("approved")), store);
        List<AgentEvent> resumed =
                rebuiltAgent
                        .streamEvents(toolResultMessage("approved"), context)
                        .collectList()
                        .block();

        List<ExternalExecutionResultEvent> resultEvents =
                eventsOf(resumed, ExternalExecutionResultEvent.class);
        assertEquals(1, resultEvents.size());
        assertEquals(request.getReplyId(), resultEvents.get(0).getReplyId());
        assertEquals(TOOL_CALL_ID, resultEvents.get(0).getToolResults().get(0).getId());
    }

    @Test
    void rejectedResultsDoNotEmitAcceptedResultEvents() {
        ReActAgent duplicateAgent = buildAgent(new ScriptedModel(toolResponse()), null);
        duplicateAgent.streamEvents(userMessage("approve")).collectList().block();
        List<AgentEvent> duplicateEvents = new ArrayList<>();
        Msg duplicateResults =
                Msg.builder()
                        .name("tool")
                        .role(MsgRole.TOOL)
                        .content(List.of(toolResult("first"), toolResult("duplicate")))
                        .build();

        assertThrows(
                RuntimeException.class,
                () ->
                        duplicateAgent
                                .streamEvents(duplicateResults)
                                .doOnNext(duplicateEvents::add)
                                .blockLast());
        assertTrue(eventsOf(duplicateEvents, ExternalExecutionResultEvent.class).isEmpty());

        ReActAgent mismatchedAgent = buildAgent(new ScriptedModel(toolResponse()), null);
        mismatchedAgent.streamEvents(userMessage("approve")).collectList().block();
        List<AgentEvent> mismatchedEvents = new ArrayList<>();
        ToolResultBlock mismatched =
                ToolResultBlock.text("wrong")
                        .withIdAndName("another-tool-call", TOOL_NAME)
                        .withState(ToolResultState.SUCCESS);
        Msg mismatchedResult =
                Msg.builder().name("tool").role(MsgRole.TOOL).content(mismatched).build();

        assertThrows(
                RuntimeException.class,
                () ->
                        mismatchedAgent
                                .streamEvents(mismatchedResult)
                                .doOnNext(mismatchedEvents::add)
                                .blockLast());
        assertTrue(eventsOf(mismatchedEvents, ExternalExecutionResultEvent.class).isEmpty());
    }

    private static ReActAgent buildAgent(ChatModelBase model, InMemoryAgentStateStore stateStore) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name(TOOL_NAME)
                        .description("Request approval in the frontend")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("request", Map.of("type", "string"))))
                        .build());
        ReActAgent.Builder builder =
                ReActAgent.builder().name("assistant").model(model).toolkit(toolkit);
        if (stateStore != null) {
            builder.stateStore(stateStore);
        }
        return builder.build();
    }

    private static ChatResponse toolResponse() {
        return ChatResponse.builder()
                .id(REPLY_ID)
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder()
                                        .id(TOOL_CALL_ID)
                                        .name(TOOL_NAME)
                                        .input(Map.of("request", "Deploy to production?"))
                                        .content("{\"request\":\"Deploy to production?\"}")
                                        .build()))
                .build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .id("reply-final")
                .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static Msg userMessage(String text) {
        return Msg.builder().name("user").role(MsgRole.USER).textContent(text).build();
    }

    private static Msg toolResultMessage(String text) {
        return Msg.builder().name("tool").role(MsgRole.TOOL).content(toolResult(text)).build();
    }

    private static ToolResultBlock toolResult(String text) {
        return ToolResultBlock.text(text)
                .withIdAndName(TOOL_CALL_ID, TOOL_NAME)
                .withState(ToolResultState.SUCCESS);
    }

    private static RequireExternalExecutionEvent firstRequest(List<AgentEvent> events) {
        assertNotNull(events);
        List<RequireExternalExecutionEvent> requests =
                eventsOf(events, RequireExternalExecutionEvent.class);
        assertEquals(1, requests.size());
        return requests.get(0);
    }

    private static boolean hasTerminalToolResultEvent(List<AgentEvent> events, String toolCallId) {
        return events.stream()
                .anyMatch(
                        event ->
                                event instanceof ToolResultTextDeltaEvent text
                                                && toolCallId.equals(text.getToolCallId())
                                        || event instanceof ToolResultDataDeltaEvent data
                                                && toolCallId.equals(data.getToolCallId())
                                        || event instanceof ToolResultEndEvent end
                                                && toolCallId.equals(end.getToolCallId()));
    }

    private static <T extends AgentEvent> List<T> eventsOf(
            List<AgentEvent> events, Class<T> eventType) {
        return events.stream().filter(eventType::isInstance).map(eventType::cast).toList();
    }

    private static final class ScriptedModel extends ChatModelBase {
        private final List<ChatResponse> responses;
        private final AtomicInteger index = new AtomicInteger();

        private ScriptedModel(ChatResponse... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String getModelName() {
            return "scripted-external-execution";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int current = index.getAndIncrement();
            if (current >= responses.size()) {
                return Flux.just(textResponse("done"));
            }
            return Flux.just(responses.get(current));
        }
    }
}
