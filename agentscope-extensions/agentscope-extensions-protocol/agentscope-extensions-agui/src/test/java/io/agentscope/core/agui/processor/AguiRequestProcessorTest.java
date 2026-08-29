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
package io.agentscope.core.agui.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/** Unit tests for AguiRequestProcessor. */
class AguiRequestProcessorTest {

    @Test
    void extractLatestUserMessagePreservesFullRunInputMetadata() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(mock(AgentResolver.class)).build();
        AguiMessage firstUser = AguiMessage.userMessage("msg-1", "first");
        AguiMessage lastUser = AguiMessage.userMessage("msg-3", "last");
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(
                                List.of(
                                        firstUser,
                                        AguiMessage.assistantMessage("msg-2", "ok"),
                                        lastUser))
                        .state(Map.of("cursor", 8))
                        .forwardedProps(Map.of("agentId", "agent-a"))
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "int-1",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of("approved", true))))
                        .build();

        RunAgentInput extracted = processor.extractLatestUserMessage(input);

        assertEquals(List.of(lastUser), extracted.getMessages());
        assertEquals(input.getState(), extracted.getState());
        assertEquals(input.getForwardedProps(), extracted.getForwardedProps());
        assertEquals(input.getResume(), extracted.getResume());
    }

    @Test
    void extractLatestUserMessageKeepsFullInputWhenNoAssistantTurnExists() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(mock(AgentResolver.class)).build();
        AguiMessage firstUser = AguiMessage.userMessage("msg-1", "first");
        AguiMessage secondUser = AguiMessage.userMessage("msg-2", "second");
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(firstUser, secondUser))
                        .build();

        assertSame(input, processor.extractLatestUserMessage(input));
    }

    @Test
    void extractLatestUserMessageIncludesToolAndUserFollowUpsAfterAssistant() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(mock(AgentResolver.class)).build();
        AguiMessage tool = AguiMessage.toolMessage("msg-3", "tool-1", "approved");
        AguiMessage followUp = AguiMessage.userMessage("msg-4", "continue");
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(
                                List.of(
                                        AguiMessage.userMessage("msg-1", "first"),
                                        AguiMessage.assistantMessage("msg-2", "need approval"),
                                        tool,
                                        followUp))
                        .state(Map.of("cursor", 8))
                        .build();

        RunAgentInput extracted = processor.extractLatestUserMessage(input);

        assertEquals(List.of(tool, followUp), extracted.getMessages());
        assertEquals(input.getState(), extracted.getState());
    }

    @Test
    void extractLatestUserMessageFallsBackToLastUserWhenAssistantIsLast() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(mock(AgentResolver.class)).build();
        AguiMessage firstUser = AguiMessage.userMessage("msg-1", "first");
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(firstUser, AguiMessage.assistantMessage("msg-2", "done")))
                        .state(Map.of("cursor", 8))
                        .forwardedProps(Map.of("agentId", "agent-a"))
                        .build();

        RunAgentInput extracted = processor.extractLatestUserMessage(input);

        assertEquals(List.of(firstUser), extracted.getMessages());
        assertEquals(input.getState(), extracted.getState());
        assertEquals(input.getForwardedProps(), extracted.getForwardedProps());
    }

    @Test
    void processCoalescesNullResolverResultWithoutNpe() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        when(resolver.hasMemory(any(RuntimeContext.class))).thenReturn(false);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentEndEvent("ok")));

        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .runtimeContextResolver(request -> null)
                        .build();

        List<AguiEvent> events =
                processor.process(request(input("run-1"))).events().collectList().block();

        assertNotNull(events);
    }

    @Test
    void processPassesCustomRuntimeContextThroughAdapterWithoutLosingAguiMetadata() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        when(agent.streamEvents(anyList(), contextCaptor.capture())).thenReturn(Flux.empty());
        RuntimeContext callerContext =
                RuntimeContext.builder()
                        .sessionId("caller-session")
                        .userId("user-1")
                        .put("tenant", "tenant-a")
                        .build();
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "hello")))
                        .build();
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .runtimeContextResolver(request -> callerContext)
                        .build();

        processor.process(request(input)).events().collectList().block();

        RuntimeContext context = contextCaptor.getValue();
        assertEquals("thread-1", context.getSessionId());
        assertEquals("user-1", context.getUserId());
        assertEquals("tenant-a", context.get("tenant"));
        assertEquals(input, context.get(RunAgentInput.class));
        assertEquals("thread-1", context.get(AguiAgentAdapter.RUNTIME_CONTEXT_THREAD_ID_KEY));
        assertEquals("run-1", context.get(AguiAgentAdapter.RUNTIME_CONTEXT_RUN_ID_KEY));
    }

    @Test
    void processRecordsInterruptsAndResolvesOfficialResumeToToolCallId() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        when(resolver.hasMemory(any(RuntimeContext.class))).thenReturn(false);
        ArgumentCaptor<List<Msg>> msgsCaptor = ArgumentCaptor.forClass(List.class);
        when(agent.streamEvents(msgsCaptor.capture(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentEndEvent("reply-2")));
        AtomicInteger runCount = new AtomicInteger();
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .adapterFactory(
                                (resolvedAgent, config) ->
                                        runCount.getAndIncrement() == 0
                                                ? new InterruptingAdapter(resolvedAgent, config)
                                                : new AguiAgentAdapter(resolvedAgent, config))
                        .build();

        processor.process(request(input("run-1"))).events().collectList().block();
        RunAgentInput resumeInput =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "interrupt-from-server",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of("approved", true))))
                        .build();

        processor.process(request(resumeInput)).events().collectList().block();

        ToolResultBlock result =
                msgsCaptor.getValue().get(0).getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(result);
        assertEquals("tool-call-from-server", result.getId());
    }

    @Test
    void processRejectsNewInputWhenThreadHasOpenInterrupts() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        AtomicInteger adapterCount = new AtomicInteger();
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .adapterFactory(
                                (resolvedAgent, config) -> {
                                    adapterCount.incrementAndGet();
                                    return new InterruptingAdapter(resolvedAgent, config);
                                })
                        .build();

        processor.process(request(input("run-1"))).events().collectList().block();
        List<AguiEvent> events =
                processor
                        .process(
                                request(
                                        RunAgentInput.builder()
                                                .threadId("thread-1")
                                                .runId("run-2")
                                                .messages(
                                                        List.of(
                                                                AguiMessage.userMessage(
                                                                        "msg-2", "new input")))
                                                .build()))
                        .events()
                        .collectList()
                        .block();

        assertEquals(1, adapterCount.get());
        assertResumeContractErrorLifecycle(events);
    }

    @Test
    void processRejectsConcurrentRunOnSameThreadUntilActiveRunFinishes() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        AtomicInteger adapterCount = new AtomicInteger();
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .adapterFactory(
                                (resolvedAgent, config) -> {
                                    adapterCount.incrementAndGet();
                                    return new NeverEndingAdapter(resolvedAgent, config);
                                })
                        .build();

        Disposable activeRun = processor.process(request(input("run-1"))).events().subscribe();
        try {
            List<AguiEvent> rejectedEvents =
                    processor.process(request(input("run-2"))).events().collectList().block();

            assertEquals(1, adapterCount.get());
            assertResumeContractErrorLifecycle(rejectedEvents);
        } finally {
            activeRun.dispose();
        }

        Disposable nextRun = processor.process(request(input("run-3"))).events().subscribe();
        try {
            assertEquals(2, adapterCount.get());
        } finally {
            nextRun.dispose();
        }
    }

    @Test
    void processDoesNotBeginRunUntilEventsAreSubscribed() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(resolver).build();

        processor.process(request(input("run-1")));
        processor.process(request(input("run-2"))).events().collectList().block();

        verify(agent, times(1)).streamEvents(anyList(), any(RuntimeContext.class));
    }

    @Test
    void processCreatesIndependentRunStateForEachEventsSubscription() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());
        AguiRequestProcessor.ProcessResult result =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .build()
                        .process(request(input("run-1")));

        result.events().collectList().block();
        result.events().collectList().block();

        verify(agent, times(2)).streamEvents(anyList(), any(RuntimeContext.class));
    }

    @Test
    void processReleasesActiveRunWhenSynchronousSetupFailsAfterBeginRun() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());
        AtomicInteger adapterCount = new AtomicInteger();
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .adapterFactory(
                                (resolvedAgent, config) -> {
                                    if (adapterCount.getAndIncrement() == 0) {
                                        throw new IllegalStateException("adapter setup failed");
                                    }
                                    return new AguiAgentAdapter(resolvedAgent, config);
                                })
                        .build();

        List<AguiEvent> setupErrorEvents =
                processor.process(request(input("run-1"))).events().collectList().block();
        List<AguiEvent> nextRunEvents =
                processor.process(request(input("run-2"))).events().collectList().block();

        assertProcessorErrorLifecycle(
                setupErrorEvents, "adapter setup failed", "INVALID_INPUT_ERROR");
        assertEquals(List.of(), nextRunEvents);
        verify(agent, times(1)).streamEvents(anyList(), any(RuntimeContext.class));
    }

    @Test
    void processRejectsPartialResumeWhenMultipleInterruptsAreOpen() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .adapterFactory(
                                (resolvedAgent, config) ->
                                        new InterruptingAdapter(
                                                resolvedAgent,
                                                config,
                                                List.of(
                                                        interrupt("interrupt-1", "tool-call-1"),
                                                        interrupt("interrupt-2", "tool-call-2"))))
                        .build();

        processor.process(request(input("run-1"))).events().collectList().block();
        List<AguiEvent> events =
                processor
                        .process(
                                request(
                                        RunAgentInput.builder()
                                                .threadId("thread-1")
                                                .runId("run-2")
                                                .resume(
                                                        List.of(
                                                                new AguiResume(
                                                                        "interrupt-1",
                                                                        AguiResume.STATUS_RESOLVED,
                                                                        Map.of("approved", true))))
                                                .build()))
                        .events()
                        .collectList()
                        .block();

        assertResumeContractErrorLifecycle(events);
    }

    @Test
    void processRejectsUnsupportedResumeStatus() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .adapterFactory(InterruptingAdapter::new)
                        .build();

        processor.process(request(input("run-1"))).events().collectList().block();
        List<AguiEvent> events =
                processor
                        .process(
                                request(
                                        RunAgentInput.builder()
                                                .threadId("thread-1")
                                                .runId("run-2")
                                                .resume(
                                                        List.of(
                                                                new AguiResume(
                                                                        "interrupt-from-server",
                                                                        "accepted",
                                                                        Map.of("approved", true))))
                                                .build()))
                        .events()
                        .collectList()
                        .block();

        assertResumeContractErrorLifecycle(events);
    }

    @Test
    void processAllowsResumeOnlyWhenAllOpenInterruptsAreCovered() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        ArgumentCaptor<List<Msg>> msgsCaptor = ArgumentCaptor.forClass(List.class);
        when(agent.streamEvents(msgsCaptor.capture(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentEndEvent("reply-2")));
        AtomicInteger runCount = new AtomicInteger();
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .adapterFactory(
                                (resolvedAgent, config) ->
                                        runCount.getAndIncrement() == 0
                                                ? new InterruptingAdapter(
                                                        resolvedAgent,
                                                        config,
                                                        List.of(
                                                                interrupt(
                                                                        "interrupt-1",
                                                                        "tool-call-1"),
                                                                interrupt(
                                                                        "interrupt-2",
                                                                        "tool-call-2")))
                                                : new AguiAgentAdapter(resolvedAgent, config))
                        .build();

        processor.process(request(input("run-1"))).events().collectList().block();
        processor
                .process(
                        request(
                                RunAgentInput.builder()
                                        .threadId("thread-1")
                                        .runId("run-2")
                                        .resume(
                                                List.of(
                                                        new AguiResume(
                                                                "interrupt-1",
                                                                AguiResume.STATUS_RESOLVED,
                                                                Map.of("approved", true)),
                                                        new AguiResume(
                                                                "interrupt-2",
                                                                AguiResume.STATUS_CANCELLED,
                                                                null)))
                                        .build()))
                .events()
                .collectList()
                .block();

        List<Msg> msgs = msgsCaptor.getValue();
        assertEquals(
                "tool-call-1", msgs.get(0).getFirstContentBlock(ToolResultBlock.class).getId());
        assertEquals(
                "tool-call-2", msgs.get(1).getFirstContentBlock(ToolResultBlock.class).getId());
    }

    @Test
    void processRejectsResumeWhenNoInterruptsAreOpen() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(resolver).build();
        RunAgentInput resumeInput =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "interrupt-from-server",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of("approved", true))))
                        .build();

        List<AguiEvent> events =
                processor.process(request(resumeInput)).events().collectList().block();

        assertResumeContractErrorLifecycle(events);
    }

    @Test
    void processUsesCustomAdapterFactory() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        when(agent.streamEvents(anyList(), contextCaptor.capture())).thenReturn(Flux.empty());
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "hello")))
                        .build();
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .adapterFactory(CustomAdapter::new)
                        .build();

        processor.process(request(input)).events().collectList().block();

        RuntimeContext context = contextCaptor.getValue();
        assertEquals("custom-adapter", context.get("adapter"));
        assertEquals("thread-1", context.getSessionId());
        assertEquals("run-1", context.get(AguiAgentAdapter.RUNTIME_CONTEXT_RUN_ID_KEY));
    }

    @Test
    void processExtractsFollowUpMessagesWhenServerHasMemory() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        when(resolver.hasMemory(any(RuntimeContext.class))).thenReturn(true);
        AtomicReference<RunAgentInput> seenInput = new AtomicReference<>();
        AguiMessage tool = AguiMessage.toolMessage("msg-3", "tool-1", "approved");
        AguiMessage followUp = AguiMessage.userMessage("msg-4", "continue");
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(
                                List.of(
                                        AguiMessage.userMessage("msg-1", "first"),
                                        AguiMessage.assistantMessage("msg-2", "need approval"),
                                        tool,
                                        followUp))
                        .build();
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .adapterFactory(
                                (resolvedAgent, config) ->
                                        new RecordingAdapter(resolvedAgent, config, seenInput))
                        .build();

        processor.process(request(input)).events().collectList().block();

        verify(resolver).hasMemory(any(RuntimeContext.class));
        assertEquals(List.of(tool, followUp), seenInput.get().getMessages());
    }

    @Test
    void processResultInterruptTargetsReActSessionWithoutClosingTheAgent() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        RuntimeContext callerContext =
                RuntimeContext.builder().userId("user-1").sessionId("caller-session").build();
        AguiRequestProcessor.ProcessResult result =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .runtimeContextResolver(request -> callerContext)
                        .build()
                        .process(request(input("run-1")));

        result.interrupt("thread-1");

        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).interrupt(contextCaptor.capture());
        verify(agent, never()).interrupt();
        assertEquals("thread-1", contextCaptor.getValue().getSessionId());
        assertEquals("user-1", contextCaptor.getValue().getUserId());
    }

    @Test
    void processResultInterruptFallsBackForNonReActAgent() {
        AgentResolver resolver = mock(AgentResolver.class);
        Agent agent = mock(Agent.class);
        when(resolver.resolveAgent(eq("default"), eq("thread-1"), nullable(String.class)))
                .thenReturn(agent);
        AguiRequestProcessor.ProcessResult result =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .build()
                        .process(request(input("run-1")));

        result.interrupt("thread-1");

        verify(agent).interrupt();
    }

    private static final class RecordingAdapter extends AguiAgentAdapter {

        private final AtomicReference<RunAgentInput> seenInput;

        private RecordingAdapter(
                Agent agent, AguiAdapterConfig config, AtomicReference<RunAgentInput> seenInput) {
            super(agent, config);
            this.seenInput = seenInput;
        }

        @Override
        public Flux<AguiEvent> run(RunAgentInput input, RuntimeContext runtimeContext) {
            seenInput.set(input);
            return Flux.empty();
        }
    }

    private static final class CustomAdapter extends AguiAgentAdapter {

        private CustomAdapter(Agent agent, AguiAdapterConfig config) {
            super(agent, config);
        }

        @Override
        protected RuntimeContext buildRuntimeContext(
                RunAgentInput input, RuntimeContext runtimeContext) {
            return RuntimeContext.builder(super.buildRuntimeContext(input, runtimeContext))
                    .put("adapter", "custom-adapter")
                    .build();
        }
    }

    private static final class InterruptingAdapter extends AguiAgentAdapter {

        private final List<AguiEvent.Interrupt> interrupts;

        private InterruptingAdapter(Agent agent, AguiAdapterConfig config) {
            this(
                    agent,
                    config,
                    List.of(interrupt("interrupt-from-server", "tool-call-from-server")));
        }

        private InterruptingAdapter(
                Agent agent, AguiAdapterConfig config, List<AguiEvent.Interrupt> interrupts) {
            super(agent, config);
            this.interrupts = interrupts;
        }

        @Override
        public Flux<AguiEvent> run(RunAgentInput input, RuntimeContext runtimeContext) {
            return Flux.just(
                    new AguiEvent.RunFinished(
                            input.getThreadId(),
                            input.getRunId(),
                            null,
                            new AguiEvent.RunFinishedInterruptOutcome(interrupts)));
        }
    }

    private static final class NeverEndingAdapter extends AguiAgentAdapter {

        private NeverEndingAdapter(Agent agent, AguiAdapterConfig config) {
            super(agent, config);
        }

        @Override
        public Flux<AguiEvent> run(RunAgentInput input, RuntimeContext runtimeContext) {
            return Flux.never();
        }
    }

    private static void assertResumeContractErrorLifecycle(List<AguiEvent> events) {
        assertEquals(
                List.of(AguiEventType.RUN_STARTED, AguiEventType.RUN_ERROR),
                events.stream().map(AguiEvent::getType).toList());
        AguiEvent.RunError error = assertInstanceOf(AguiEvent.RunError.class, events.get(1));
        assertEquals("AGUI_INTERRUPT_CONTRACT_ERROR", error.code());
        assertNotNull(error.timestamp());
    }

    private static void assertProcessorErrorLifecycle(
            List<AguiEvent> events, String message, String code) {
        assertEquals(
                List.of(AguiEventType.RUN_STARTED, AguiEventType.RUN_ERROR),
                events.stream().map(AguiEvent::getType).toList());
        AguiEvent.RunError error = assertInstanceOf(AguiEvent.RunError.class, events.get(1));
        assertEquals(message, error.message());
        assertEquals(code, error.code());
        assertNotNull(error.timestamp());
    }

    private static AguiEvent.Interrupt interrupt(String interruptId, String toolCallId) {
        return new AguiEvent.Interrupt(
                interruptId, "tool_call", "approve", toolCallId, null, null, null);
    }

    private static RunAgentInput input(String runId) {
        return RunAgentInput.builder()
                .threadId("thread-1")
                .runId(runId)
                .messages(List.of(AguiMessage.userMessage("msg-1", "hello")))
                .build();
    }

    private static AguiRuntimeContextRequest<?> request(RunAgentInput runInput) {
        return AguiRuntimeContextRequest.builder().input(runInput).build();
    }
}
