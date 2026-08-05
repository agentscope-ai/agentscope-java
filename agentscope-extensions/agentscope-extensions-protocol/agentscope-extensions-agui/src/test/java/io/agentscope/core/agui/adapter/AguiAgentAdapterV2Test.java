/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import io.agentscope.core.agui.event.AguiEvents;
import io.agentscope.core.agui.model.AguiContext;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.AguiTool;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.ToolMergeMode;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.DataBlockStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

@SuppressWarnings("unchecked")
class AguiAgentAdapterV2Test {

    @Nested
    class RuntimeContextAndLifecycleTests {

        @Test
        void testRunUsesReActStreamEventsWithRuntimeContext() {
            ReActAgent agent = mock(ReActAgent.class);
            ArgumentCaptor<RuntimeContext> contextCaptor =
                    ArgumentCaptor.forClass(RuntimeContext.class);
            when(agent.streamEvents(anyList(), contextCaptor.capture()))
                    .thenReturn(
                            Flux.just(
                                    new AgentStartEvent("thread-v2", "reply-1", "react"),
                                    new AgentEndEvent("reply-1")));

            RunAgentInput input =
                    inputBuilder()
                            .tools(List.of())
                            .context(List.of())
                            .state(Map.of("cursor", 1))
                            .forwardedProps(Map.of("tenant", "demo"))
                            .build();

            List<AguiEvent> events =
                    new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                            .run(input)
                            .collectList()
                            .block();

            assertNotNull(events);
            assertEquals(
                    List.of(AguiEventType.RUN_STARTED, AguiEventType.RUN_FINISHED), types(events));
            AguiEvent.RunStarted runStarted =
                    assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
            assertSame(input, runStarted.input());
            RuntimeContext context = contextCaptor.getValue();
            assertEquals("thread-v2", context.getSessionId());
            assertSame(input, context.get(RunAgentInput.class));
            assertEquals("thread-v2", context.get(AguiAgentAdapter.RUNTIME_CONTEXT_THREAD_ID_KEY));
            assertEquals("run-v2", context.get(AguiAgentAdapter.RUNTIME_CONTEXT_RUN_ID_KEY));
            assertSame(
                    input.getMessages(),
                    context.get(AguiAgentAdapter.RUNTIME_CONTEXT_MESSAGES_KEY));
            assertSame(input.getTools(), context.get(AguiAgentAdapter.RUNTIME_CONTEXT_TOOLS_KEY));
            assertSame(
                    input.getContext(), context.get(AguiAgentAdapter.RUNTIME_CONTEXT_CONTEXT_KEY));
            assertSame(input.getState(), context.get(AguiAgentAdapter.RUNTIME_CONTEXT_STATE_KEY));
            assertSame(
                    input.getForwardedProps(),
                    context.get(AguiAgentAdapter.RUNTIME_CONTEXT_FORWARDED_PROPS_KEY));
        }

        @Test
        void testRunMergesCustomRuntimeContextWithoutLosingAguiMetadata() {
            ReActAgent agent = mock(ReActAgent.class);
            ArgumentCaptor<RuntimeContext> contextCaptor =
                    ArgumentCaptor.forClass(RuntimeContext.class);
            when(agent.streamEvents(anyList(), contextCaptor.capture())).thenReturn(Flux.empty());
            RuntimeContext callerContext =
                    RuntimeContext.builder()
                            .sessionId("caller-session")
                            .userId("user-1")
                            .put("tenant", "tenant-a")
                            .put(Integer.class, 42)
                            .build();
            RunAgentInput input =
                    inputBuilder()
                            .context(List.of(new AguiContext("scope", "demo")))
                            .state(Map.of("cursor", 1))
                            .forwardedProps(Map.of("agentId", "agent-a"))
                            .build();

            new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                    .run(input, callerContext)
                    .collectList()
                    .block();

            RuntimeContext context = contextCaptor.getValue();
            assertNotSame(callerContext, context);
            assertEquals("thread-v2", context.getSessionId());
            assertEquals("user-1", context.getUserId());
            assertEquals("tenant-a", context.get("tenant"));
            assertEquals(42, context.get(Integer.class));
            assertSame(input, context.get(RunAgentInput.class));
            assertEquals("thread-v2", context.get(AguiAgentAdapter.RUNTIME_CONTEXT_THREAD_ID_KEY));
            assertEquals("run-v2", context.get(AguiAgentAdapter.RUNTIME_CONTEXT_RUN_ID_KEY));
            assertSame(
                    input.getMessages(),
                    context.get(AguiAgentAdapter.RUNTIME_CONTEXT_MESSAGES_KEY));
            assertSame(input.getTools(), context.get(AguiAgentAdapter.RUNTIME_CONTEXT_TOOLS_KEY));
            assertSame(
                    input.getContext(), context.get(AguiAgentAdapter.RUNTIME_CONTEXT_CONTEXT_KEY));
            assertSame(input.getState(), context.get(AguiAgentAdapter.RUNTIME_CONTEXT_STATE_KEY));
            assertSame(
                    input.getForwardedProps(),
                    context.get(AguiAgentAdapter.RUNTIME_CONTEXT_FORWARDED_PROPS_KEY));
            assertEquals("caller-session", callerContext.getSessionId());
            assertNull(callerContext.get(AguiAgentAdapter.RUNTIME_CONTEXT_THREAD_ID_KEY));
        }

        @Test
        void testBuildRuntimeContextCanBeCustomizedBySubclass() {
            ReActAgent agent = mock(ReActAgent.class);
            ArgumentCaptor<RuntimeContext> contextCaptor =
                    ArgumentCaptor.forClass(RuntimeContext.class);
            when(agent.streamEvents(anyList(), contextCaptor.capture())).thenReturn(Flux.empty());
            AguiAgentAdapter adapter =
                    new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig()) {
                        @Override
                        protected RuntimeContext buildRuntimeContext(
                                RunAgentInput input, RuntimeContext runtimeContext) {
                            return RuntimeContext.builder(
                                            super.buildRuntimeContext(input, runtimeContext))
                                    .put("subclass", "custom")
                                    .build();
                        }
                    };

            adapter.run(input(), RuntimeContext.builder().userId("user-1").build())
                    .collectList()
                    .block();

            RuntimeContext context = contextCaptor.getValue();
            assertEquals("thread-v2", context.getSessionId());
            assertEquals("user-1", context.getUserId());
            assertEquals("custom", context.get("subclass"));
            assertEquals("run-v2", context.get(AguiAgentAdapter.RUNTIME_CONTEXT_RUN_ID_KEY));
        }

        @Test
        void testRunConvertsOfficialResumeToToolResultMessage() {
            ReActAgent agent = mock(ReActAgent.class);
            ArgumentCaptor<List<Msg>> msgsCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<RuntimeContext> contextCaptor =
                    ArgumentCaptor.forClass(RuntimeContext.class);
            when(agent.streamEvents(msgsCaptor.capture(), contextCaptor.capture()))
                    .thenReturn(Flux.empty());
            AguiResume resume =
                    new AguiResume(
                            "reply-1:tool-call-1",
                            AguiResume.STATUS_RESOLVED,
                            Map.of("approved", true));
            RunAgentInput input =
                    inputBuilder().messages(List.of()).resume(List.of(resume)).build();

            new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                    .run(input)
                    .collectList()
                    .block();

            RuntimeContext context = contextCaptor.getValue();
            assertEquals(List.of(resume), context.get(AguiAgentAdapter.RUNTIME_CONTEXT_RESUME_KEY));
            List<Msg> msgs = msgsCaptor.getValue();
            assertEquals(1, msgs.size());
            ToolResultBlock result = msgs.get(0).getFirstContentBlock(ToolResultBlock.class);
            assertNotNull(result);
            assertEquals("tool-call-1", result.getId());
            assertEquals("reply-1:tool-call-1", result.getMetadata().get("agui.interruptId"));
        }

        @Test
        void testRunDoesNotEmitLifecycleEventsWhenUpstreamDoesNotEmitLifecycleEvents() {
            List<AguiEvent> events = runReActEvents();

            assertNotNull(events);
            assertTrue(events.isEmpty());
        }

        @Test
        void testAgentEndFlushesPendingEventsBeforeRunFinished() {
            AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
            List<AguiEvent> events =
                    runReActEvents(
                            config,
                            new AgentStartEvent("thread-v2", "reply-pending", "react"),
                            new ThinkingBlockDeltaEvent("reply-pending", "thinking-1", "think"),
                            new TextBlockDeltaEvent("reply-pending", "text-1", "answer"),
                            new ToolCallStartEvent("reply-pending", "tool-1", "lookup"),
                            new AgentEndEvent("reply-pending"));

            assertEquals(
                    List.of(
                            AguiEventType.RUN_STARTED,
                            AguiEventType.REASONING_MESSAGE_START,
                            AguiEventType.REASONING_MESSAGE_CONTENT,
                            AguiEventType.TEXT_MESSAGE_START,
                            AguiEventType.TEXT_MESSAGE_CONTENT,
                            AguiEventType.TOOL_CALL_START,
                            AguiEventType.TEXT_MESSAGE_END,
                            AguiEventType.REASONING_MESSAGE_END,
                            AguiEventType.TOOL_CALL_END,
                            AguiEventType.RUN_FINISHED),
                    types(events));
        }

        @Test
        void testRunUsesHarnessStreamEventsViaReflection() {
            HarnessAgent agent = new HarnessAgent();
            agent.setEvents(Flux.just(new TextBlockDeltaEvent("reply-harness", "block-1", "hi")));

            List<AguiEvent> events =
                    new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                            .run(input())
                            .collectList()
                            .block();

            assertNotNull(events);
            assertEquals("thread-v2", agent.getSeenContext().getSessionId());
            assertEquals("run-v2", agent.getSeenContext().get("agui.runId"));
            assertEquals(1, agent.getSeenMessages().size());
            assertTrue(events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageContent));
        }
    }

    @Nested
    class TextAndReasoningConversionTests {

        @Test
        void testTextBlockEventsConvertToAguiTextMessageEvents() {
            List<AguiEvent> events =
                    runReActEvents(
                            new TextBlockStartEvent("reply-text", "block-1"),
                            new TextBlockDeltaEvent("reply-text", "block-1", "hel"),
                            new TextBlockDeltaEvent("reply-text", "block-1", "lo"),
                            new TextBlockEndEvent("reply-text", "block-1"));

            assertEquals(
                    List.of(
                            AguiEventType.TEXT_MESSAGE_START,
                            AguiEventType.TEXT_MESSAGE_CONTENT,
                            AguiEventType.TEXT_MESSAGE_CONTENT,
                            AguiEventType.TEXT_MESSAGE_END),
                    types(events));
            AguiEvent.TextMessageContent firstDelta =
                    assertInstanceOf(AguiEvent.TextMessageContent.class, events.get(1));
            assertEquals("hel", firstDelta.delta());
        }

        @Test
        void testThinkingEventsAreIgnoredWhenReasoningDisabled() {
            List<AguiEvent> events =
                    runReActEvents(
                            new ThinkingBlockStartEvent("reply-thinking", "block-1"),
                            new ThinkingBlockDeltaEvent("reply-thinking", "block-1", "hidden"),
                            new ThinkingBlockEndEvent("reply-thinking", "block-1"));

            assertFalse(
                    events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageContent));
            assertTrue(events.isEmpty());
        }

        @Test
        void testThinkingEventsConvertWhenReasoningEnabled() {
            List<AguiEvent> events =
                    runReActEvents(
                            AguiAdapterConfig.builder().enableReasoning(true).build(),
                            new ThinkingBlockStartEvent("reply-thinking", "block-1"),
                            new ThinkingBlockDeltaEvent("reply-thinking", "block-1", "visible"),
                            new ThinkingBlockEndEvent("reply-thinking", "block-1"));

            assertEquals(
                    List.of(
                            AguiEventType.REASONING_MESSAGE_START,
                            AguiEventType.REASONING_MESSAGE_CONTENT,
                            AguiEventType.REASONING_MESSAGE_END),
                    types(events));
            AguiEvent.ReasoningMessageStart start =
                    assertInstanceOf(AguiEvent.ReasoningMessageStart.class, events.get(0));
            AguiEvent.ReasoningMessageContent content =
                    assertInstanceOf(AguiEvent.ReasoningMessageContent.class, events.get(1));
            AguiEvent.ReasoningMessageEnd end =
                    assertInstanceOf(AguiEvent.ReasoningMessageEnd.class, events.get(2));
            String expectedMessageId =
                    "reply-thinking" + AguiStreamContext.REASONING_MESSAGE_ID_SUFFIX;
            assertEquals(expectedMessageId, start.messageId());
            assertEquals(expectedMessageId, content.messageId());
            assertEquals(expectedMessageId, end.messageId());
        }

        @Test
        void testTextAndReasoningUseDifferentMessageIdsForSameReply() {
            List<AguiEvent> events =
                    runReActEvents(
                            AguiAdapterConfig.builder().enableReasoning(true).build(),
                            new ThinkingBlockDeltaEvent("reply-shared", "thinking-1", "think"),
                            new ThinkingBlockEndEvent("reply-shared", "thinking-1"),
                            new TextBlockDeltaEvent("reply-shared", "text-1", "answer"),
                            new TextBlockEndEvent("reply-shared", "text-1"));

            AguiEvent.ReasoningMessageContent reasoningContent =
                    events.stream()
                            .filter(AguiEvent.ReasoningMessageContent.class::isInstance)
                            .map(AguiEvent.ReasoningMessageContent.class::cast)
                            .findFirst()
                            .orElseThrow();
            AguiEvent.TextMessageContent textContent =
                    events.stream()
                            .filter(AguiEvent.TextMessageContent.class::isInstance)
                            .map(AguiEvent.TextMessageContent.class::cast)
                            .findFirst()
                            .orElseThrow();

            assertEquals("reply-shared", textContent.messageId());
            assertEquals(
                    "reply-shared" + AguiStreamContext.REASONING_MESSAGE_ID_SUFFIX,
                    reasoningContent.messageId());
        }

        @Test
        void testTextAndThinkingStartOnlyEventsDoNotEmitEmptyMessages() {
            List<AguiEvent> textEvents =
                    runReActEvents(
                            new TextBlockStartEvent("reply-empty-text", "text-1"),
                            new TextBlockEndEvent("reply-empty-text", "text-1"));
            List<AguiEvent> thinkingEvents =
                    runReActEvents(
                            AguiAdapterConfig.builder().enableReasoning(true).build(),
                            new ThinkingBlockStartEvent("reply-empty-thinking", "thinking-1"),
                            new ThinkingBlockEndEvent("reply-empty-thinking", "thinking-1"));

            assertTrue(textEvents.isEmpty());
            assertTrue(thinkingEvents.isEmpty());
        }

        @Test
        void testStreamingTextDeltasShareOneStartAndOneFinishPendingEnd() {
            List<AguiEvent> events =
                    runReActEvents(
                            new TextBlockDeltaEvent("reply-stream", "text-1", "Hello"),
                            new TextBlockDeltaEvent("reply-stream", "text-1", ", world"));

            assertEquals(
                    List.of(
                            AguiEventType.TEXT_MESSAGE_START,
                            AguiEventType.TEXT_MESSAGE_CONTENT,
                            AguiEventType.TEXT_MESSAGE_CONTENT,
                            AguiEventType.TEXT_MESSAGE_END),
                    types(events));
            assertEquals(
                    1,
                    events.stream().filter(AguiEvent.TextMessageStart.class::isInstance).count());
            assertEquals(
                    1, events.stream().filter(AguiEvent.TextMessageEnd.class::isInstance).count());
        }

        @Test
        void testStreamingReasoningDeltasShareOneStartAndOneFinishPendingEnd() {
            List<AguiEvent> events =
                    runReActEvents(
                            AguiAdapterConfig.builder().enableReasoning(true).build(),
                            new ThinkingBlockDeltaEvent("reply-stream", "thinking-1", "First"),
                            new ThinkingBlockDeltaEvent("reply-stream", "thinking-1", "Second"));

            assertEquals(
                    List.of(
                            AguiEventType.REASONING_MESSAGE_START,
                            AguiEventType.REASONING_MESSAGE_CONTENT,
                            AguiEventType.REASONING_MESSAGE_CONTENT,
                            AguiEventType.REASONING_MESSAGE_END),
                    types(events));
            assertEquals(
                    1,
                    events.stream()
                            .filter(AguiEvent.ReasoningMessageStart.class::isInstance)
                            .count());
            assertEquals(
                    1,
                    events.stream()
                            .filter(AguiEvent.ReasoningMessageEnd.class::isInstance)
                            .count());
        }

        @Test
        void testStateIsIsolatedAcrossMultipleSubscriptions() {
            ReActAgent agent = mock(ReActAgent.class);
            when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                    .thenReturn(
                            Flux.just(
                                    new TextBlockDeltaEvent("reply-repeat", "text-1", "Preparing"),
                                    new ToolCallStartEvent("reply-repeat", "tool-1", "lookup")));

            Flux<AguiEvent> resultFlux =
                    new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig()).run(input());

            List<AguiEvent> firstEvents = resultFlux.collectList().block();
            List<AguiEvent> secondEvents = resultFlux.collectList().block();

            assertEquals(types(firstEvents), types(secondEvents));
            assertEquals(
                    1,
                    secondEvents.stream()
                            .filter(AguiEvent.TextMessageStart.class::isInstance)
                            .count());
            assertEquals(
                    1,
                    secondEvents.stream()
                            .filter(AguiEvent.TextMessageContent.class::isInstance)
                            .count());
            assertEquals(
                    1,
                    secondEvents.stream()
                            .filter(AguiEvent.ToolCallStart.class::isInstance)
                            .count());
            assertEquals(
                    1,
                    secondEvents.stream().filter(AguiEvent.ToolCallEnd.class::isInstance).count());
        }
    }

    @Nested
    class ToolCallConversionTests {

        @Test
        void testToolCallArgsRespectConfig() {
            List<AguiEvent> enabled =
                    runReActEvents(
                            new ToolCallStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolCallDeltaEvent("reply-tool", "tool-1", "lookup", "{\"q\""),
                            new ToolCallEndEvent("reply-tool", "tool-1", "lookup"));
            List<AguiEvent> disabled =
                    runReActEvents(
                            AguiAdapterConfig.builder().emitToolCallArgs(false).build(),
                            new ToolCallStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolCallDeltaEvent("reply-tool", "tool-1", "lookup", "{\"q\""),
                            new ToolCallEndEvent("reply-tool", "tool-1", "lookup"));

            assertTrue(enabled.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallArgs));
            assertFalse(disabled.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallArgs));
        }

        @Test
        void testToolResultDeltasAreAggregatedIntoToolCallResult() {
            List<AguiEvent> events =
                    runReActEvents(
                            new ToolCallStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolCallEndEvent("reply-tool", "tool-1", "lookup"),
                            new ToolResultStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolResultTextDeltaEvent("reply-tool", "tool-1", "lookup", "hel"),
                            new ToolResultTextDeltaEvent("reply-tool", "tool-1", "lookup", "lo"),
                            new ToolResultEndEvent("reply-tool", "tool-1", "lookup", null));

            AguiEvent.ToolCallResult result =
                    events.stream()
                            .filter(AguiEvent.ToolCallResult.class::isInstance)
                            .map(AguiEvent.ToolCallResult.class::cast)
                            .findFirst()
                            .orElseThrow();
            assertEquals("hello", result.content());
            assertEquals("reply-tool", result.messageId());
        }

        @Test
        void testToolResultDataDeltaContributesToToolCallResult() {
            List<AguiEvent> events =
                    runReActEvents(
                            new ToolCallStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolCallEndEvent("reply-tool", "tool-1", "lookup"),
                            new ToolResultStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolResultDataDeltaEvent(
                                    "reply-tool",
                                    "tool-1",
                                    "lookup",
                                    TextBlock.builder().text("data").build()),
                            new ToolResultEndEvent("reply-tool", "tool-1", "lookup", null));

            AguiEvent.ToolCallResult result =
                    events.stream()
                            .filter(AguiEvent.ToolCallResult.class::isInstance)
                            .map(AguiEvent.ToolCallResult.class::cast)
                            .findFirst()
                            .orElseThrow();
            assertEquals("data", result.content());
        }

        @Test
        void testToolResultTextAndDataDeltasAreJoinedInArrivalOrder() {
            List<AguiEvent> events =
                    runReActEvents(
                            new ToolCallStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolCallEndEvent("reply-tool", "tool-1", "lookup"),
                            new ToolResultStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolResultTextDeltaEvent("reply-tool", "tool-1", "lookup", "hel"),
                            new ToolResultDataDeltaEvent(
                                    "reply-tool",
                                    "tool-1",
                                    "lookup",
                                    TextBlock.builder().text("structured").build()),
                            new ToolResultTextDeltaEvent("reply-tool", "tool-1", "lookup", "lo"),
                            new ToolResultEndEvent("reply-tool", "tool-1", "lookup", null));

            AguiEvent.ToolCallResult result =
                    events.stream()
                            .filter(AguiEvent.ToolCallResult.class::isInstance)
                            .map(AguiEvent.ToolCallResult.class::cast)
                            .findFirst()
                            .orElseThrow();
            assertEquals("hel\nstructuredlo", result.content());
        }

        @Test
        void testToolResultEndWithoutContentStillEmitsNullResult() {
            List<AguiEvent> events =
                    runReActEvents(
                            new ToolCallStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolResultStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolResultEndEvent("reply-tool", "tool-1", "lookup", null));

            assertEquals(
                    List.of(
                            AguiEventType.TOOL_CALL_START,
                            AguiEventType.TOOL_CALL_END,
                            AguiEventType.TOOL_CALL_RESULT),
                    types(events));
            AguiEvent.ToolCallResult result =
                    assertInstanceOf(AguiEvent.ToolCallResult.class, events.get(2));
            assertEquals("tool-1", result.toolCallId());
            assertNull(result.content());
        }

        @Test
        void testReasoningTextAndMultipleToolCallsPreserveStrictStartEndOrder() {
            AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
            List<AguiEvent> events =
                    runReActEvents(
                            config,
                            new ThinkingBlockDeltaEvent("reply-mixed", "thinking-1", "think"),
                            new ThinkingBlockEndEvent("reply-mixed", "thinking-1"),
                            new TextBlockDeltaEvent("reply-mixed", "text-1", "answer"),
                            new TextBlockEndEvent("reply-mixed", "text-1"),
                            new ToolCallStartEvent("reply-mixed", "tool-1", "lookup"),
                            new ToolCallStartEvent("reply-mixed", "tool-2", "search"),
                            new ToolCallDeltaEvent("reply-mixed", "tool-1", "lookup", "{\"q\":"),
                            new ToolCallDeltaEvent("reply-mixed", "tool-2", "search", "{\"p\":"),
                            new ToolCallEndEvent("reply-mixed", "tool-1", "lookup"),
                            new ToolCallEndEvent("reply-mixed", "tool-2", "search"));

            assertEquals(
                    List.of(
                            AguiEventType.REASONING_MESSAGE_START,
                            AguiEventType.REASONING_MESSAGE_CONTENT,
                            AguiEventType.REASONING_MESSAGE_END,
                            AguiEventType.TEXT_MESSAGE_START,
                            AguiEventType.TEXT_MESSAGE_CONTENT,
                            AguiEventType.TEXT_MESSAGE_END,
                            AguiEventType.TOOL_CALL_START,
                            AguiEventType.TOOL_CALL_START,
                            AguiEventType.TOOL_CALL_ARGS,
                            AguiEventType.TOOL_CALL_ARGS,
                            AguiEventType.TOOL_CALL_END,
                            AguiEventType.TOOL_CALL_END),
                    types(events));
            assertToolCallId(events.get(6), "tool-1");
            assertToolCallId(events.get(7), "tool-2");
            assertToolCallId(events.get(8), "tool-1");
            assertToolCallId(events.get(9), "tool-2");
            assertToolCallId(events.get(10), "tool-1");
            assertToolCallId(events.get(11), "tool-2");
        }

        @Test
        void testParallelToolCallsKeepIndependentArgsEndAndResultOrder() {
            List<AguiEvent> events =
                    runReActEvents(
                            new ToolCallStartEvent("reply-parallel", "tool-1", "lookup"),
                            new ToolCallStartEvent("reply-parallel", "tool-2", "search"),
                            new ToolCallDeltaEvent("reply-parallel", "tool-1", "lookup", "{\"q\""),
                            new ToolCallDeltaEvent("reply-parallel", "tool-2", "search", "{\"p\""),
                            new ToolCallEndEvent("reply-parallel", "tool-2", "search"),
                            new ToolResultStartEvent("reply-parallel", "tool-2", "search"),
                            new ToolResultTextDeltaEvent(
                                    "reply-parallel", "tool-2", "search", "result-2"),
                            new ToolResultEndEvent("reply-parallel", "tool-2", "search", null),
                            new ToolCallEndEvent("reply-parallel", "tool-1", "lookup"),
                            new ToolResultStartEvent("reply-parallel", "tool-1", "lookup"),
                            new ToolResultTextDeltaEvent(
                                    "reply-parallel", "tool-1", "lookup", "result-1"),
                            new ToolResultEndEvent("reply-parallel", "tool-1", "lookup", null));

            assertEquals(
                    List.of(
                            AguiEventType.TOOL_CALL_START,
                            AguiEventType.TOOL_CALL_START,
                            AguiEventType.TOOL_CALL_ARGS,
                            AguiEventType.TOOL_CALL_ARGS,
                            AguiEventType.TOOL_CALL_END,
                            AguiEventType.TOOL_CALL_RESULT,
                            AguiEventType.TOOL_CALL_END,
                            AguiEventType.TOOL_CALL_RESULT),
                    types(events));
            assertToolCallResult(events.get(5), "tool-2", "result-2");
            assertToolCallResult(events.get(7), "tool-1", "result-1");
        }

        @Test
        void testSuspendedToolResultDoesNotEmitToolCallResult() {
            List<AguiEvent> events =
                    runReActEvents(
                            new ToolCallStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolCallEndEvent("reply-tool", "tool-1", "lookup"),
                            new ToolResultStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolResultTextDeltaEvent(
                                    "reply-tool", "tool-1", "lookup", "needs external work"),
                            new ToolResultEndEvent(
                                    "reply-tool", "tool-1", "lookup", ToolResultState.RUNNING));

            assertEquals(
                    List.of(AguiEventType.TOOL_CALL_START, AguiEventType.TOOL_CALL_END),
                    types(events));
        }

        @Test
        void testSuspendedToolResultFinishesRunWithToolCallInterrupt() {
            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id("tool-1")
                            .name("lookup")
                            .input(Map.of("city", "Paris"))
                            .build();
            Msg suspendedResult =
                    suspendedToolResult(
                            "reply-suspended",
                            toolUse,
                            ToolResultBlock.builder()
                                    .id("tool-1")
                                    .name("lookup")
                                    .output(
                                            TextBlock.builder()
                                                    .text("Execute lookup externally")
                                                    .build())
                                    .metadata(Map.of(ToolResultBlock.METADATA_SUSPENDED, true))
                                    .build());

            List<AguiEvent> events =
                    runReActEvents(
                            new AgentStartEvent("thread-v2", "reply-suspended", "react"),
                            new ToolCallStartEvent("reply-suspended", "tool-1", "lookup"),
                            new ToolCallDeltaEvent(
                                    "reply-suspended", "tool-1", "lookup", "{\"city\":\"Paris\"}"),
                            new ToolResultStartEvent("reply-suspended", "tool-1", "lookup"),
                            new ToolResultTextDeltaEvent(
                                    "reply-suspended",
                                    "tool-1",
                                    "lookup",
                                    "Execute lookup externally"),
                            new ToolResultEndEvent(
                                    "reply-suspended", "tool-1", "lookup", ToolResultState.RUNNING),
                            new AgentResultEvent(suspendedResult),
                            new AgentEndEvent("reply-suspended"));

            assertEquals(
                    List.of(
                            AguiEventType.RUN_STARTED,
                            AguiEventType.TOOL_CALL_START,
                            AguiEventType.TOOL_CALL_ARGS,
                            AguiEventType.TOOL_CALL_END,
                            AguiEventType.RUN_FINISHED),
                    types(events));

            AguiEvent.RunFinished finished =
                    assertInstanceOf(AguiEvent.RunFinished.class, events.get(4));
            AguiEvent.RunFinishedInterruptOutcome outcome =
                    assertInstanceOf(
                            AguiEvent.RunFinishedInterruptOutcome.class, finished.outcome());
            assertEquals(1, outcome.interrupts().size());
            AguiEvent.Interrupt interrupt = outcome.interrupts().get(0);
            assertEquals("reply-suspended:tool-1", interrupt.id());
            assertEquals("tool_call", interrupt.reason());
            assertEquals("Execute lookup externally", interrupt.message());
            assertEquals("tool-1", interrupt.toolCallId());
            assertNull(interrupt.responseSchema());
            assertNull(interrupt.expiresAt());
            assertEquals("lookup", interrupt.metadata().get("toolName"));
            assertEquals(Map.of("city", "Paris"), interrupt.metadata().get("toolInput"));
            assertEquals("reply-suspended", interrupt.metadata().get("replyId"));
        }

        @Test
        void testOnlySuspendedToolIsInterruptedWhenParallelToolCallsPartiallyComplete() {
            ToolUseBlock suspendedTool =
                    ToolUseBlock.builder()
                            .id("tool-2")
                            .name("search")
                            .input(Map.of("q", "agent"))
                            .build();
            Msg suspendedResult =
                    suspendedToolResult(
                            "reply-parallel",
                            suspendedTool,
                            ToolResultBlock.builder()
                                    .id("tool-2")
                                    .name("search")
                                    .output(TextBlock.builder().text("Search externally").build())
                                    .metadata(Map.of(ToolResultBlock.METADATA_SUSPENDED, true))
                                    .build());

            List<AguiEvent> events =
                    runReActEvents(
                            new AgentStartEvent("thread-v2", "reply-parallel", "react"),
                            new ToolCallStartEvent("reply-parallel", "tool-1", "lookup"),
                            new ToolCallStartEvent("reply-parallel", "tool-2", "search"),
                            new ToolCallEndEvent("reply-parallel", "tool-1", "lookup"),
                            new ToolResultStartEvent("reply-parallel", "tool-1", "lookup"),
                            new ToolResultTextDeltaEvent(
                                    "reply-parallel", "tool-1", "lookup", "done"),
                            new ToolResultEndEvent("reply-parallel", "tool-1", "lookup", null),
                            new ToolCallEndEvent("reply-parallel", "tool-2", "search"),
                            new ToolResultStartEvent("reply-parallel", "tool-2", "search"),
                            new ToolResultTextDeltaEvent(
                                    "reply-parallel", "tool-2", "search", "Search externally"),
                            new ToolResultEndEvent(
                                    "reply-parallel", "tool-2", "search", ToolResultState.RUNNING),
                            new AgentResultEvent(suspendedResult),
                            new AgentEndEvent("reply-parallel"));

            assertEquals(
                    List.of(
                            AguiEventType.RUN_STARTED,
                            AguiEventType.TOOL_CALL_START,
                            AguiEventType.TOOL_CALL_START,
                            AguiEventType.TOOL_CALL_END,
                            AguiEventType.TOOL_CALL_RESULT,
                            AguiEventType.TOOL_CALL_END,
                            AguiEventType.RUN_FINISHED),
                    types(events));
            assertToolCallResult(events.get(4), "tool-1", "done");

            AguiEvent.RunFinished finished =
                    assertInstanceOf(AguiEvent.RunFinished.class, events.get(6));
            AguiEvent.RunFinishedInterruptOutcome outcome =
                    assertInstanceOf(
                            AguiEvent.RunFinishedInterruptOutcome.class, finished.outcome());
            assertEquals(1, outcome.interrupts().size());
            assertEquals("tool-2", outcome.interrupts().get(0).toolCallId());
        }

        @Test
        void testToolCallWithoutArgsStillEmitsStartAndEnd() {
            List<AguiEvent> events =
                    runReActEvents(
                            new ToolCallStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolCallEndEvent("reply-tool", "tool-1", "lookup"));

            assertEquals(
                    List.of(AguiEventType.TOOL_CALL_START, AguiEventType.TOOL_CALL_END),
                    types(events));
        }

        @Test
        void testDuplicateToolCallStartAndEndAreNotEmitted() {
            List<AguiEvent> events =
                    runReActEvents(
                            new ToolCallStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolCallStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolCallDeltaEvent("reply-tool", "tool-1", "lookup", "{\"q\""),
                            new ToolCallEndEvent("reply-tool", "tool-1", "lookup"),
                            new ToolCallEndEvent("reply-tool", "tool-1", "lookup"));

            assertEquals(
                    List.of(
                            AguiEventType.TOOL_CALL_START,
                            AguiEventType.TOOL_CALL_ARGS,
                            AguiEventType.TOOL_CALL_END),
                    types(events));
        }

        @Test
        void testToolCallDeltaWithoutStartIsIgnored() {
            List<AguiEvent> events =
                    runReActEvents(
                            new ToolCallDeltaEvent(
                                    "reply-tool", "tool-1", "__fragment__", "{\"q\""));

            assertTrue(events.isEmpty());
        }

        @Test
        void testFragmentToolCallDeltaForStartedToolAppendsArgs() {
            List<AguiEvent> events =
                    runReActEvents(
                            new ToolCallStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolCallDeltaEvent(
                                    "reply-tool", "tool-1", "__fragment__", ":\"agent\"}"),
                            new ToolCallEndEvent("reply-tool", "tool-1", "lookup"));

            assertEquals(
                    List.of(
                            AguiEventType.TOOL_CALL_START,
                            AguiEventType.TOOL_CALL_ARGS,
                            AguiEventType.TOOL_CALL_END),
                    types(events));
        }

        @Test
        void testToolCallEventsWithoutStableIdAreIgnored() {
            List<AguiEvent> events =
                    runReActEvents(
                            new ToolCallStartEvent("reply-tool", null, "lookup"),
                            new ToolCallDeltaEvent("reply-tool", "", "lookup", "{\"q\""),
                            new ToolCallEndEvent("reply-tool", null, "lookup"));

            assertTrue(events.isEmpty());
        }

        @Test
        void testToolResultEventsWithoutStableIdAreIgnored() {
            List<AguiEvent> events =
                    runReActEvents(
                            new ToolResultStartEvent("reply-tool", null, "lookup"),
                            new ToolResultTextDeltaEvent("reply-tool", "", "lookup", "ignored"),
                            new ToolResultEndEvent("reply-tool", null, "lookup", null));

            assertTrue(events.isEmpty());
        }

        @Test
        void testToolResultEventsWithoutStartedToolCallAreIgnored() {
            List<AguiEvent> events =
                    runReActEvents(
                            new ToolResultStartEvent("reply-tool", "tool-1", "lookup"),
                            new ToolResultTextDeltaEvent(
                                    "reply-tool", "tool-1", "lookup", "ignored"),
                            new ToolResultEndEvent("reply-tool", "tool-1", "lookup", null));

            assertTrue(events.isEmpty());
        }

        @Test
        void testWarnMissingToolCallIdDeduplicatesByEventName() throws Exception {
            AguiStreamContext context =
                    new AguiStreamContext("thread-v2", "run-v2", AguiAdapterConfig.defaultConfig());

            context.startToolCall(null, "lookup");
            context.startToolCall("", "lookup");
            context.appendToolCallArgs(null, "{}");
            context.appendToolCallArgs("", "{}");
            context.endToolCall(null);
            context.endToolCall("");
            context.beginToolResult(null);
            context.beginToolResult("");
            context.appendToolResultText(null, "ignored");
            context.appendToolResultText("", "ignored");
            context.appendToolResultData(null, TextBlock.builder().text("ignored").build());
            context.appendToolResultData("", TextBlock.builder().text("ignored").build());
            context.endToolResult("reply-tool", null);
            context.endToolResult("reply-tool", "");

            assertEquals(
                    Set.of(
                            "ToolCallStartEvent",
                            "ToolCallDeltaEvent",
                            "ToolCallEndEvent",
                            "ToolResultStartEvent",
                            "ToolResultTextDeltaEvent",
                            "ToolResultDataDeltaEvent",
                            "ToolResultEndEvent"),
                    warnedMissingToolCallIdOperations(context));
        }
    }

    @Nested
    class TokenUsageTests {

        @Test
        void testTokenUsageIsNotEmittedByDefault() {
            List<AguiEvent> events =
                    runReActEvents(
                            new ModelCallEndEvent("reply-usage", new ChatUsage(100, 20, 40, 0.8)));

            assertTrue(events.isEmpty());
        }

        @Test
        void testEnabledTokenUsageEmitsCustomEventWithDeltaAndCumulativeUsage() {
            AguiAdapterConfig config = AguiAdapterConfig.builder().emitTokenUsage(true).build();
            List<AguiEvent> events =
                    runReActEvents(
                            config,
                            new ModelCallEndEvent("reply-usage", new ChatUsage(100, 20, 40, 0.8)));

            assertEquals(List.of(AguiEventType.CUSTOM), types(events));
            AguiEvent.Custom usageEvent = assertCustomEvent(events.get(0), "token_usage");
            Map<String, Object> value = customValue(usageEvent);

            assertUsage(value.get("delta"), 100L, 20L, 40L, 120L, 0.8);
            assertUsage(value.get("cumulative"), 100L, 20L, 40L, 120L, 0.8);
            assertEquals(Map.of("replyId", "reply-usage"), value.get("modelCall"));
        }

        @Test
        void testEnabledTokenUsageAccumulatesAcrossModelCalls() {
            AguiAdapterConfig config = AguiAdapterConfig.builder().emitTokenUsage(true).build();
            List<AguiEvent> events =
                    runReActEvents(
                            config,
                            new ModelCallEndEvent("reply-1", new ChatUsage(100, 20, 40, 0.8)),
                            new ModelCallEndEvent("reply-2", new ChatUsage(50, 30, 10, 1.2)));

            assertEquals(List.of(AguiEventType.CUSTOM, AguiEventType.CUSTOM), types(events));

            Map<String, Object> firstValue =
                    customValue(assertCustomEvent(events.get(0), "token_usage"));
            Map<String, Object> secondValue =
                    customValue(assertCustomEvent(events.get(1), "token_usage"));
            assertUsage(firstValue.get("cumulative"), 100L, 20L, 40L, 120L, 0.8);
            assertUsage(secondValue.get("delta"), 50L, 30L, 10L, 80L, 1.2);
            assertUsage(secondValue.get("cumulative"), 150L, 50L, 50L, 200L, 2.0);
            assertEquals(Map.of("replyId", "reply-2"), secondValue.get("modelCall"));
        }

        @Test
        void testTokenUsageWithNullUsageIsIgnored() {
            AguiAdapterConfig config = AguiAdapterConfig.builder().emitTokenUsage(true).build();
            List<AguiEvent> events =
                    runReActEvents(config, new ModelCallEndEvent("reply-usage", null));

            assertTrue(events.isEmpty());
        }

        @Test
        void testTokenUsageCustomEventPreservesStreamOrder() {
            AguiAdapterConfig config = AguiAdapterConfig.builder().emitTokenUsage(true).build();
            List<AguiEvent> events =
                    runReActEvents(
                            config,
                            new TextBlockDeltaEvent("reply-text", "text-1", "hello"),
                            new ModelCallEndEvent("reply-text", new ChatUsage(10, 5, 3, 0.2)),
                            new TextBlockEndEvent("reply-text", "text-1"));

            assertEquals(
                    List.of(
                            AguiEventType.TEXT_MESSAGE_START,
                            AguiEventType.TEXT_MESSAGE_CONTENT,
                            AguiEventType.CUSTOM,
                            AguiEventType.TEXT_MESSAGE_END),
                    types(events));
            assertCustomEvent(events.get(2), "token_usage");
        }

        @Test
        void testEnabledBaseEventPropertiesEnricherAddsTimestampToTokenUsageEvent() {
            AguiAdapterConfig config =
                    AguiAdapterConfig.builder()
                            .emitTokenUsage(true)
                            .baseEventPropertiesEnricherEnabled(true)
                            .build();
            List<AguiEvent> events =
                    runReActEvents(
                            config,
                            new ModelCallEndEvent("reply-usage", new ChatUsage(100, 20, 40, 0.8)));

            AguiEvent.Custom usageEvent = assertCustomEvent(events.get(0), "token_usage");
            assertNotNull(usageEvent.timestamp());
            assertNull(usageEvent.rawEvent());
        }

        @Test
        void testCustomConverterCanOverrideTokenUsageConverter() {
            AguiAdapterConfig config =
                    AguiAdapterConfig.builder()
                            .emitTokenUsage(true)
                            .addEventConverter(
                                    new AgentEventConverter() {
                                        @Override
                                        public Set<Class<? extends AgentEvent>> eventTypes() {
                                            return Set.of(ModelCallEndEvent.class);
                                        }

                                        @Override
                                        public void convert(
                                                AgentEvent event, AguiStreamContext context) {}
                                    })
                            .build();

            List<AguiEvent> events =
                    runReActEvents(
                            config,
                            new ModelCallEndEvent("reply-usage", new ChatUsage(100, 20, 40, 0.8)));

            assertTrue(events.isEmpty());
        }
    }

    @Nested
    class ToolMergeModeTests {

        @Test
        void testRunRegistersFrontendToolsForRunAndCleansUp() {
            ReActAgent agent = mock(ReActAgent.class);
            Toolkit toolkit = new Toolkit();
            when(agent.getToolkit()).thenReturn(toolkit);
            when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                    .thenAnswer(
                            invocation -> {
                                assertInstanceOf(
                                        SchemaOnlyTool.class, toolkit.getTool("frontend_lookup"));
                                assertTrue(toolkit.isExternalTool("frontend_lookup"));
                                return Flux.empty();
                            });

            new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                    .run(inputWithTools(frontendTool("frontend_lookup")))
                    .collectList()
                    .block();

            assertNull(toolkit.getTool("frontend_lookup"));
        }

        @Test
        void testRunRestoresAgentToolWhenFrontendToolHasSameName() {
            ReActAgent agent = mock(ReActAgent.class);
            Toolkit toolkit = new Toolkit();
            SchemaOnlyTool existingTool = schemaOnlyTool("shared_lookup");
            toolkit.registerAgentTool(existingTool);
            when(agent.getToolkit()).thenReturn(toolkit);
            when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                    .thenAnswer(
                            invocation -> {
                                assertInstanceOf(
                                        SchemaOnlyTool.class, toolkit.getTool("shared_lookup"));
                                assertNotSame(existingTool, toolkit.getTool("shared_lookup"));
                                return Flux.empty();
                            });

            new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                    .run(inputWithTools(frontendTool("shared_lookup")))
                    .collectList()
                    .block();

            assertSame(existingTool, toolkit.getTool("shared_lookup"));
        }

        @Test
        void testRunDoesNotRegisterFrontendToolsWhenAgentOnly() {
            ReActAgent agent = mock(ReActAgent.class);
            Toolkit toolkit = new Toolkit();
            when(agent.getToolkit()).thenReturn(toolkit);
            when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());

            AguiAgentAdapter agentOnlyAdapter =
                    new AguiAgentAdapter(
                            agent,
                            AguiAdapterConfig.builder()
                                    .toolMergeMode(ToolMergeMode.AGENT_ONLY)
                                    .build());

            agentOnlyAdapter
                    .run(inputWithTools(frontendTool("frontend_lookup")))
                    .collectList()
                    .block();

            assertNull(toolkit.getTool("frontend_lookup"));
        }

        @Test
        void testRunIgnoresFrontendToolsWhenAgentHasNoToolkit() {
            ReActAgent agent = mock(ReActAgent.class);
            when(agent.getToolkit()).thenReturn(null);
            when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());

            List<AguiEvent> events =
                    new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                            .run(inputWithTools(frontendTool("frontend_lookup")))
                            .collectList()
                            .block();

            assertNotNull(events);
            assertTrue(events.isEmpty());
        }

        @Test
        void testRunUsesFrontendPriorityWhenToolMergeModeIsNull() {
            ReActAgent agent = mock(ReActAgent.class);
            Toolkit toolkit = new Toolkit();
            when(agent.getToolkit()).thenReturn(toolkit);
            when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                    .thenAnswer(
                            invocation -> {
                                assertInstanceOf(
                                        SchemaOnlyTool.class, toolkit.getTool("frontend_lookup"));
                                return Flux.empty();
                            });

            AguiAgentAdapter nullMergeModeAdapter =
                    new AguiAgentAdapter(
                            agent, AguiAdapterConfig.builder().toolMergeMode(null).build());

            nullMergeModeAdapter
                    .run(inputWithTools(frontendTool("frontend_lookup")))
                    .collectList()
                    .block();

            assertNull(toolkit.getTool("frontend_lookup"));
        }

        @Test
        void testRunWithFrontendOnlyTemporarilyReplacesToolkitAndRestoresAgentTools() {
            ReActAgent agent = mock(ReActAgent.class);
            Toolkit toolkit = new Toolkit();
            SchemaOnlyTool existingTool = schemaOnlyTool("agent_lookup");
            SchemaOnlyTool existingSharedTool = schemaOnlyTool("shared_lookup");
            toolkit.registerAgentTool(existingTool);
            toolkit.registerAgentTool(existingSharedTool);
            when(agent.getToolkit()).thenReturn(toolkit);
            when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                    .thenAnswer(
                            invocation -> {
                                assertNull(toolkit.getTool("agent_lookup"));
                                assertInstanceOf(
                                        SchemaOnlyTool.class, toolkit.getTool("frontend_lookup"));
                                assertInstanceOf(
                                        SchemaOnlyTool.class, toolkit.getTool("shared_lookup"));
                                assertNotSame(existingSharedTool, toolkit.getTool("shared_lookup"));
                                return Flux.empty();
                            });

            AguiAgentAdapter frontendOnlyAdapter =
                    new AguiAgentAdapter(
                            agent,
                            AguiAdapterConfig.builder()
                                    .toolMergeMode(ToolMergeMode.FRONTEND_ONLY)
                                    .build());

            frontendOnlyAdapter
                    .run(
                            inputWithTools(
                                    frontendTool("frontend_lookup"), frontendTool("shared_lookup")))
                    .collectList()
                    .block();

            assertSame(existingTool, toolkit.getTool("agent_lookup"));
            assertSame(existingSharedTool, toolkit.getTool("shared_lookup"));
            assertNull(toolkit.getTool("frontend_lookup"));
        }

        @Test
        void testRunWithFrontendOnlySkipsToolNameThatNoLongerResolves() {
            ReActAgent agent = mock(ReActAgent.class);
            Toolkit toolkit = new GhostToolNameToolkit();
            when(agent.getToolkit()).thenReturn(toolkit);
            when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                    .thenAnswer(
                            invocation -> {
                                assertInstanceOf(
                                        SchemaOnlyTool.class, toolkit.getTool("frontend_lookup"));
                                return Flux.empty();
                            });

            AguiAgentAdapter frontendOnlyAdapter =
                    new AguiAgentAdapter(
                            agent,
                            AguiAdapterConfig.builder()
                                    .toolMergeMode(ToolMergeMode.FRONTEND_ONLY)
                                    .build());

            frontendOnlyAdapter
                    .run(inputWithTools(frontendTool("frontend_lookup")))
                    .collectList()
                    .block();

            assertNull(toolkit.getTool("frontend_lookup"));
        }

        @Test
        void testRunKeepsToolThatReplacesInjectedFrontendToolBeforeCleanup() {
            ReActAgent agent = mock(ReActAgent.class);
            Toolkit toolkit = new Toolkit();
            SchemaOnlyTool existingTool = schemaOnlyTool("shared_lookup");
            SchemaOnlyTool replacementTool = schemaOnlyTool("shared_lookup");
            toolkit.registerAgentTool(existingTool);
            when(agent.getToolkit()).thenReturn(toolkit);
            when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                    .thenAnswer(
                            invocation -> {
                                toolkit.registerAgentTool(replacementTool);
                                return Flux.empty();
                            });

            new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                    .run(inputWithTools(frontendTool("shared_lookup")))
                    .collectList()
                    .block();

            assertSame(replacementTool, toolkit.getTool("shared_lookup"));
        }
    }

    @Nested
    class ErrorHandlingTests {

        @Test
        void testRunEmitsErrorEventsWhenStreamEventsThrowsBeforeReturningFlux() {
            ReActAgent agent = mock(ReActAgent.class);
            when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                    .thenThrow(new IllegalStateException("boom"));

            RunAgentInput input = input();
            List<AguiEvent> events =
                    new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                            .run(input)
                            .collectList()
                            .block();

            assertStartupErrorRun(events, input, "boom", "INVALID_INPUT_ERROR");
        }

        @Test
        void testRunEmitsStartedErrorEventsWhenStreamEventsFailsBeforeFirstEvent() {
            ReActAgent agent = mock(ReActAgent.class);
            when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                    .thenReturn(Flux.error(new RuntimeException("boom")));

            RunAgentInput input = input();
            List<AguiEvent> events =
                    new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                            .run(input)
                            .collectList()
                            .block();

            assertStartupErrorRun(events, input, "boom", "INTERNAL_ERROR");
        }

        @Test
        void testRunDoesNotDuplicateRunStartedWhenStreamEventsFailsAfterAgentStart() {
            List<AguiEvent> events =
                    runReActFlux(
                            Flux.concat(
                                    Flux.just(new AgentStartEvent("thread-v2", "reply", "react")),
                                    Flux.error(new RuntimeException("boom"))));

            assertEquals(
                    List.of(
                            AguiEventType.RUN_STARTED,
                            AguiEventType.RUN_ERROR,
                            AguiEventType.RUN_FINISHED),
                    types(events));
            assertErrorRun(events.subList(1, 3), "boom", "INTERNAL_ERROR");
        }

        @Test
        void testRunMapsTimeoutAndInterruptedErrors() {
            List<AguiEvent> timeoutEvents =
                    runReActFlux(Flux.error(new TimeoutException("too slow")));
            List<AguiEvent> interruptedEvents =
                    runReActFlux(Flux.error(new InterruptedException("cancelled")));

            assertStartedErrorRun(timeoutEvents, "too slow", "TIMEOUT_ERROR");
            assertStartedErrorRun(interruptedEvents, "cancelled", "INTERRUPTED_ERROR");
        }

        @Test
        void testRunClosesTextMessageBeforeErrorWhenStreamEventsFailsAfterDelta() {
            List<AguiEvent> events =
                    runReActFlux(
                            Flux.concat(
                                    Flux.just(
                                            new TextBlockDeltaEvent("reply-text", "text-1", "hi")),
                                    Flux.error(new RuntimeException("boom"))));

            assertEquals(
                    List.of(
                            AguiEventType.TEXT_MESSAGE_START,
                            AguiEventType.TEXT_MESSAGE_CONTENT,
                            AguiEventType.TEXT_MESSAGE_END,
                            AguiEventType.RUN_ERROR,
                            AguiEventType.RUN_FINISHED),
                    types(events));
        }

        @Test
        void testRunClosesReasoningMessageBeforeErrorWhenStreamEventsFailsAfterDelta() {
            List<AguiEvent> events =
                    runReActFlux(
                            AguiAdapterConfig.builder().enableReasoning(true).build(),
                            Flux.concat(
                                    Flux.just(
                                            new ThinkingBlockDeltaEvent(
                                                    "reply-thinking", "thinking-1", "thinking")),
                                    Flux.error(new RuntimeException("boom"))));

            assertEquals(
                    List.of(
                            AguiEventType.REASONING_MESSAGE_START,
                            AguiEventType.REASONING_MESSAGE_CONTENT,
                            AguiEventType.REASONING_MESSAGE_END,
                            AguiEventType.RUN_ERROR,
                            AguiEventType.RUN_FINISHED),
                    types(events));
        }

        @Test
        void testRunClosesToolCallBeforeErrorWhenStreamEventsFailsAfterArgs() {
            List<AguiEvent> events =
                    runReActFlux(
                            Flux.concat(
                                    Flux.just(
                                            new ToolCallStartEvent(
                                                    "reply-tool", "tool-1", "lookup"),
                                            new ToolCallDeltaEvent(
                                                    "reply-tool", "tool-1", "lookup", "{\"q\"")),
                                    Flux.error(new RuntimeException("boom"))));

            assertEquals(
                    List.of(
                            AguiEventType.TOOL_CALL_START,
                            AguiEventType.TOOL_CALL_ARGS,
                            AguiEventType.TOOL_CALL_END,
                            AguiEventType.RUN_ERROR,
                            AguiEventType.RUN_FINISHED),
                    types(events));
        }

        @Test
        void testRunClosesStartedToolCallBeforeErrorWhenStreamEventsFailsAfterStart() {
            List<AguiEvent> events =
                    runReActFlux(
                            Flux.concat(
                                    Flux.just(
                                            new ToolCallStartEvent(
                                                    "reply-tool", "tool-1", "lookup")),
                                    Flux.error(new RuntimeException("boom"))));

            assertEquals(
                    List.of(
                            AguiEventType.TOOL_CALL_START,
                            AguiEventType.TOOL_CALL_END,
                            AguiEventType.RUN_ERROR,
                            AguiEventType.RUN_FINISHED),
                    types(events));
        }

        @Test
        void testRunEnrichesPendingTextEndBeforeErrorWhenBasePropertiesAreEnabled() {
            AguiAdapterConfig config =
                    AguiAdapterConfig.builder().baseEventPropertiesEnricherEnabled(true).build();
            List<AguiEvent> events =
                    runReActFlux(
                            config,
                            Flux.concat(
                                    Flux.just(
                                            new TextBlockDeltaEvent("reply-text", "text-1", "hi")),
                                    Flux.error(new RuntimeException("boom"))));

            assertEquals(
                    List.of(
                            AguiEventType.TEXT_MESSAGE_START,
                            AguiEventType.TEXT_MESSAGE_CONTENT,
                            AguiEventType.TEXT_MESSAGE_END,
                            AguiEventType.RUN_ERROR,
                            AguiEventType.RUN_FINISHED),
                    types(events));
            assertNotNull(events.get(2).timestamp());
            assertNotNull(events.get(3).timestamp());
            assertNull(events.get(4).timestamp());
        }
    }

    @Nested
    class RawCustomAndExtensionTests {

        @Test
        void testCustomAndUnmappedEventsConvertWithoutDroppingSignals() {
            List<AguiEvent> events =
                    runReActEvents(
                            new CustomEvent("state_updated", Map.of("task", "done")),
                            new DataBlockStartEvent("reply-raw", "data-1"));

            assertTrue(events.stream().anyMatch(e -> e instanceof AguiEvent.Custom));
            assertTrue(events.stream().anyMatch(e -> e instanceof AguiEvent.Raw));
        }

        @Test
        void testEnabledBaseEventPropertiesEnricherAddsTimestampToAgentAndLifecycleEvents() {
            AguiAdapterConfig config =
                    AguiAdapterConfig.builder().baseEventPropertiesEnricherEnabled(true).build();
            List<AguiEvent> events =
                    runReActEvents(
                            config,
                            new AgentStartEvent("thread-v2", "reply-text", "react"),
                            new TextBlockDeltaEvent("reply-text", "block-1", "hello"),
                            new AgentEndEvent("reply-text"));

            assertEquals(
                    List.of(
                            AguiEventType.RUN_STARTED,
                            AguiEventType.TEXT_MESSAGE_START,
                            AguiEventType.TEXT_MESSAGE_CONTENT,
                            AguiEventType.TEXT_MESSAGE_END,
                            AguiEventType.RUN_FINISHED),
                    types(events));
            assertTrue(events.stream().allMatch(event -> event.timestamp() != null));
            assertTrue(events.stream().allMatch(event -> event.rawEvent() == null));
        }

        @Test
        void testFinishPendingEventsAreEnrichedWithoutAgentEndEvent() {
            AguiAdapterConfig config =
                    AguiAdapterConfig.builder().baseEventPropertiesEnricherEnabled(true).build();
            List<AguiEvent> events =
                    runReActEvents(
                            config, new TextBlockDeltaEvent("reply-text", "block-1", "hello"));

            assertEquals(
                    List.of(
                            AguiEventType.TEXT_MESSAGE_START,
                            AguiEventType.TEXT_MESSAGE_CONTENT,
                            AguiEventType.TEXT_MESSAGE_END),
                    types(events));
            assertNotNull(events.get(0).timestamp());
            assertNotNull(events.get(1).timestamp());
            assertNotNull(events.get(2).timestamp());
        }

        @Test
        void testBaseEventPropertiesEnricherDisabledByDefault() {
            AguiAdapterConfig config = AguiAdapterConfig.builder().build();
            List<AguiEvent> events =
                    runReActEvents(
                            config, new TextBlockDeltaEvent("reply-text", "block-1", "hello"));

            assertTrue(events.stream().allMatch(event -> event.timestamp() == null));
            assertTrue(events.stream().allMatch(event -> event.rawEvent() == null));
        }

        @Test
        void testCustomEnricherCanAttachRawSourceEvent() {
            AgentEvent source = new TextBlockDeltaEvent("reply-text", "block-1", "hello");
            AguiAdapterConfig config =
                    AguiAdapterConfig.builder()
                            .addEventEnricher(
                                    (agentEvent, events, context) ->
                                            events.stream()
                                                    .map(
                                                            event ->
                                                                    AguiEvents.withBaseProperties(
                                                                            event,
                                                                            event.timestamp(),
                                                                            agentEvent))
                                                    .toList())
                            .build();

            List<AguiEvent> events = runReActEvents(config, source);
            AguiEvent.TextMessageContent content =
                    events.stream()
                            .filter(AguiEvent.TextMessageContent.class::isInstance)
                            .map(AguiEvent.TextMessageContent.class::cast)
                            .findFirst()
                            .orElseThrow();

            assertSame(source, content.rawEvent());
            assertNull(content.timestamp());
            assertNull(events.get(2).rawEvent());
        }

        @Test
        void testRawEventUsesOfficialEventSourceShapeWithoutDefaultRawEvent() {
            DataBlockStartEvent source = new DataBlockStartEvent("reply-data", "block-1");
            source.withSource("main/researcher");

            List<AguiEvent> events = runReActEvents(source);
            AguiEvent.Raw raw =
                    events.stream()
                            .filter(AguiEvent.Raw.class::isInstance)
                            .map(AguiEvent.Raw.class::cast)
                            .findFirst()
                            .orElseThrow();

            assertSame(source, raw.event());
            assertEquals("main/researcher", raw.source());
            assertNull(raw.timestamp());
            assertNull(raw.rawEvent());
        }

        @Test
        void testCustomConverterOverridesBuiltInConverter() {
            AguiAdapterConfig config =
                    AguiAdapterConfig.builder()
                            .addEventConverter(
                                    new AgentEventConverter() {
                                        @Override
                                        public Set<Class<? extends AgentEvent>> eventTypes() {
                                            return Set.of(AgentStartEvent.class);
                                        }

                                        @Override
                                        public void convert(
                                                AgentEvent event, AguiStreamContext context) {
                                            context.appendTextDelta("custom-message", "custom");
                                        }
                                    })
                            .build();

            List<AguiEvent> events = runReActEvents(config, new AgentStartEvent("t", "r", "agent"));

            assertEquals(
                    List.of(
                            AguiEventType.TEXT_MESSAGE_START,
                            AguiEventType.TEXT_MESSAGE_CONTENT,
                            AguiEventType.TEXT_MESSAGE_END),
                    types(events));
            AguiEvent.TextMessageContent content =
                    assertInstanceOf(AguiEvent.TextMessageContent.class, events.get(1));
            assertEquals("custom", content.delta());
            assertNull(content.timestamp());
        }
    }

    private static List<AguiEvent> runReActEvents(AgentEvent... agentEvents) {
        return runReActEvents(AguiAdapterConfig.defaultConfig(), agentEvents);
    }

    private static List<AguiEvent> runReActEvents(
            AguiAdapterConfig config, AgentEvent... agentEvents) {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.fromArray(agentEvents));
        return new AguiAgentAdapter(agent, config).run(input()).collectList().block();
    }

    private static List<AguiEvent> runReActFlux(Flux<AgentEvent> agentEvents) {
        return runReActFlux(AguiAdapterConfig.defaultConfig(), agentEvents);
    }

    private static List<AguiEvent> runReActFlux(
            AguiAdapterConfig config, Flux<AgentEvent> agentEvents) {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(agentEvents);
        return new AguiAgentAdapter(agent, config).run(input()).collectList().block();
    }

    private static void assertStartupErrorRun(
            List<AguiEvent> events, RunAgentInput input, String message, String code) {
        assertStartedErrorRun(events, message, code);
        AguiEvent.RunStarted runStarted =
                assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        assertSame(input, runStarted.input());
    }

    private static void assertStartedErrorRun(List<AguiEvent> events, String message, String code) {
        assertNotNull(events);
        assertEquals(3, events.size());
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        AguiEvent.RunError runError = assertInstanceOf(AguiEvent.RunError.class, events.get(1));
        assertEquals(message, runError.message());
        assertEquals(code, runError.code());
        assertNotNull(runError.timestamp());
        AguiEvent.RunFinished finished =
                assertInstanceOf(AguiEvent.RunFinished.class, events.get(2));
        assertNull(finished.timestamp());
    }

    private static void assertErrorRun(List<AguiEvent> events, String message, String code) {
        assertNotNull(events);
        assertEquals(2, events.size());
        AguiEvent.RunError runError = assertInstanceOf(AguiEvent.RunError.class, events.get(0));
        assertEquals(message, runError.message());
        assertEquals(code, runError.code());
        assertNotNull(runError.timestamp());
        AguiEvent.RunFinished finished =
                assertInstanceOf(AguiEvent.RunFinished.class, events.get(1));
        assertNull(finished.timestamp());
    }

    private static void assertToolCallId(AguiEvent event, String expectedToolCallId) {
        if (event instanceof AguiEvent.ToolCallStart start) {
            assertEquals(expectedToolCallId, start.toolCallId());
        } else if (event instanceof AguiEvent.ToolCallArgs args) {
            assertEquals(expectedToolCallId, args.toolCallId());
        } else if (event instanceof AguiEvent.ToolCallEnd end) {
            assertEquals(expectedToolCallId, end.toolCallId());
        } else {
            throw new AssertionError("Unexpected tool call event: " + event);
        }
    }

    private static void assertToolCallResult(
            AguiEvent event, String expectedToolCallId, String expectedContent) {
        AguiEvent.ToolCallResult result = assertInstanceOf(AguiEvent.ToolCallResult.class, event);
        assertEquals(expectedToolCallId, result.toolCallId());
        assertEquals(expectedContent, result.content());
    }

    private static Msg suspendedToolResult(
            String replyId, ToolUseBlock toolUse, ToolResultBlock toolResult) {
        return AssistantMessage.builder()
                .id(replyId)
                .content(List.<ContentBlock>of(toolUse, toolResult))
                .generateReason(GenerateReason.TOOL_SUSPENDED)
                .build();
    }

    private static AguiEvent.Custom assertCustomEvent(AguiEvent event, String expectedName) {
        AguiEvent.Custom custom = assertInstanceOf(AguiEvent.Custom.class, event);
        assertEquals(expectedName, custom.name());
        return custom;
    }

    private static Map<String, Object> customValue(AguiEvent.Custom event) {
        assertInstanceOf(Map.class, event.value());
        return (Map<String, Object>) event.value();
    }

    private static void assertUsage(
            Object value,
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            long totalTokens,
            double time) {
        assertInstanceOf(Map.class, value);
        Map<String, Object> usage = (Map<String, Object>) value;
        assertEquals(inputTokens, usage.get("inputTokens"));
        assertEquals(outputTokens, usage.get("outputTokens"));
        assertEquals(cachedTokens, usage.get("cachedTokens"));
        assertEquals(totalTokens, usage.get("totalTokens"));
        assertEquals(time, (Double) usage.get("time"), 0.000001);
    }

    private static Set<String> warnedMissingToolCallIdOperations(AguiStreamContext context)
            throws Exception {
        Field field = AguiStreamContext.class.getDeclaredField("warnedMissingToolCallIdOperations");
        field.setAccessible(true);
        return Set.copyOf((Set<String>) field.get(context));
    }

    private static List<AguiEventType> types(List<AguiEvent> events) {
        return events.stream().map(AguiEvent::getType).toList();
    }

    private static RunAgentInput input() {
        return inputBuilder().build();
    }

    private static RunAgentInput inputWithTools(AguiTool... tools) {
        return inputBuilder().tools(List.of(tools)).build();
    }

    private static RunAgentInput.Builder inputBuilder() {
        return RunAgentInput.builder()
                .threadId("thread-v2")
                .runId("run-v2")
                .messages(List.of(AguiMessage.userMessage("msg-1", "hello")));
    }

    private static AguiTool frontendTool(String name) {
        return new AguiTool(
                name,
                "Frontend tool",
                Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))));
    }

    private static SchemaOnlyTool schemaOnlyTool(String name) {
        return new SchemaOnlyTool(
                ToolSchema.builder()
                        .name(name)
                        .description("Existing tool")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("query", Map.of("type", "string"))))
                        .build());
    }

    private static final class GhostToolNameToolkit extends Toolkit {

        @Override
        public Set<String> getToolNames() {
            return Set.of("ghost_lookup");
        }
    }
}
