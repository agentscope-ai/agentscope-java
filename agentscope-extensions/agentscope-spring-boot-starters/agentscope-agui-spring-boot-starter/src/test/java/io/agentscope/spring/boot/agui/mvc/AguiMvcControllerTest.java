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
package io.agentscope.spring.boot.agui.mvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

class AguiMvcControllerTest {

    @Test
    void eventStreamSignalsAreForwardedAndEmitterCompletes() throws Exception {
        ControllerFixture fixture =
                fixture(true, Flux.just(new AguiEvent.RunFinished("thread-1", "run-1")));
        try {
            SseEmitter emitter = fixture.controller.handle(input("run-1"), null);

            assertTrue(fixture.firstRunTerminated.await(5, TimeUnit.SECONDS));
            assertTrue((Boolean) ReflectionTestUtils.getField(emitter, "complete"));
            assertEquals(1, fixture.runCount.get());
        } finally {
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void eventStreamErrorCompletesEmitter() throws Exception {
        ControllerFixture fixture =
                fixture(true, Flux.error(new IllegalStateException("run failed")));
        try {
            SseEmitter emitter = fixture.controller.handle(input("run-1"), null);

            assertTrue(fixture.firstRunTerminated.await(5, TimeUnit.SECONDS));
            assertTrue((Boolean) ReflectionTestUtils.getField(emitter, "complete"));
            assertEquals(1, fixture.runCount.get());
        } finally {
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void sseErrorCancelsSubscriptionAndReleasesThread() throws Exception {
        ControllerFixture fixture = fixture(true);
        try {
            SseEmitter emitter = fixture.controller.handle(input("run-1"), null);
            assertTrue(fixture.firstRunSubscribed.await(5, TimeUnit.SECONDS));

            invokeError(emitter);
            assertTrue(fixture.firstRunTerminated.await(5, TimeUnit.SECONDS));

            awaitSecondRunAccepted(fixture);

            assertEquals(2, fixture.runCount.get());
            verify(fixture.agent).interrupt(any(RuntimeContext.class));
        } finally {
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void sseErrorKeepsRunActiveWhenInterruptOnDisconnectIsDisabled() throws Exception {
        ControllerFixture fixture = fixture(false);
        try {
            SseEmitter emitter = fixture.controller.handle(input("run-1"), null);
            assertTrue(fixture.firstRunSubscribed.await(5, TimeUnit.SECONDS));

            invokeError(emitter);

            List<AguiEvent> events =
                    fixture.processor
                            .process(
                                    AguiRuntimeContextRequest.builder()
                                            .input(input("run-2"))
                                            .build())
                            .events()
                            .collectList()
                            .block();

            assertEquals(1, fixture.runCount.get());
            assertEquals(
                    List.of(AguiEventType.RUN_STARTED, AguiEventType.RUN_ERROR),
                    events.stream().map(AguiEvent::getType).toList());
        } finally {
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void sseTimeoutCancelsSubscriptionAndReleasesThread() throws Exception {
        ControllerFixture fixture = fixture(true);
        try {
            SseEmitter emitter = fixture.controller.handle(input("run-1"), null);
            assertTrue(fixture.firstRunSubscribed.await(5, TimeUnit.SECONDS));

            Object timeoutCallback = ReflectionTestUtils.getField(emitter, "timeoutCallback");
            ReflectionTestUtils.invokeMethod(timeoutCallback, "run");
            assertTrue(fixture.firstRunTerminated.await(5, TimeUnit.SECONDS));

            awaitSecondRunAccepted(fixture);

            assertEquals(2, fixture.runCount.get());
            verify(fixture.agent).interrupt(any(RuntimeContext.class));
        } finally {
            fixture.executor.shutdownNow();
        }
    }

    private static ControllerFixture fixture(boolean interruptOnDisconnect) {
        return fixture(interruptOnDisconnect, Flux.never());
    }

    private static ControllerFixture fixture(
            boolean interruptOnDisconnect, Flux<AguiEvent> firstRunEvents) {
        ReActAgent agent = mock(ReActAgent.class);
        AguiAgentRegistry registry = new AguiAgentRegistry();
        registry.register("default", agent);
        CountDownLatch firstRunSubscribed = new CountDownLatch(1);
        CountDownLatch firstRunTerminated = new CountDownLatch(1);
        AtomicInteger runCount = new AtomicInteger();
        AguiMvcController controller =
                AguiMvcController.builder()
                        .agentRegistry(registry)
                        .interruptOnDisconnect(interruptOnDisconnect)
                        .adapterFactory(
                                (resolvedAgent, config) ->
                                        new TestAdapter(
                                                resolvedAgent,
                                                config,
                                                firstRunSubscribed,
                                                firstRunTerminated,
                                                firstRunEvents,
                                                runCount))
                        .build();
        AguiRequestProcessor processor =
                (AguiRequestProcessor) ReflectionTestUtils.getField(controller, "processor");
        ExecutorService executor =
                (ExecutorService) ReflectionTestUtils.getField(controller, "executorService");
        return new ControllerFixture(
                controller,
                processor,
                executor,
                agent,
                firstRunSubscribed,
                firstRunTerminated,
                runCount);
    }

    private static void invokeError(SseEmitter emitter) {
        Object errorCallback = ReflectionTestUtils.getField(emitter, "errorCallback");
        ReflectionTestUtils.invokeMethod(
                errorCallback, "accept", new IOException("client disconnected"));
    }

    /**
     * Submits run-2 until it is accepted by the processor.
     *
     * <p>On disconnect the controller cancels run-1's subscription. Reactor's {@code doFinally}
     * fires inner callbacks before outer ones on cancel, so the adapter's own teardown (which
     * counts down {@code firstRunTerminated}) completes <em>before</em> {@code
     * AguiResumeCoordinator.finishRun} releases the thread. Retrying run-2 therefore waits
     * deterministically for the thread to actually become free instead of racing it.
     */
    private static void awaitSecondRunAccepted(ControllerFixture fixture) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fixture.runCount.get() < 2 && System.nanoTime() < deadline) {
            fixture.processor
                    .process(AguiRuntimeContextRequest.builder().input(input("run-2")).build())
                    .events()
                    .collectList()
                    .block();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
    }

    private static RunAgentInput input(String runId) {
        return RunAgentInput.builder()
                .threadId("thread-1")
                .runId(runId)
                .messages(List.of(AguiMessage.userMessage("message-1", "hello")))
                .build();
    }

    private record ControllerFixture(
            AguiMvcController controller,
            AguiRequestProcessor processor,
            ExecutorService executor,
            ReActAgent agent,
            CountDownLatch firstRunSubscribed,
            CountDownLatch firstRunTerminated,
            AtomicInteger runCount) {}

    private static final class TestAdapter extends AguiAgentAdapter {

        private final CountDownLatch firstRunSubscribed;
        private final CountDownLatch firstRunTerminated;
        private final Flux<AguiEvent> firstRunEvents;
        private final AtomicInteger runCount;

        private TestAdapter(
                Agent agent,
                AguiAdapterConfig config,
                CountDownLatch firstRunSubscribed,
                CountDownLatch firstRunTerminated,
                Flux<AguiEvent> firstRunEvents,
                AtomicInteger runCount) {
            super(agent, config);
            this.firstRunSubscribed = firstRunSubscribed;
            this.firstRunTerminated = firstRunTerminated;
            this.firstRunEvents = firstRunEvents;
            this.runCount = runCount;
        }

        @Override
        public Flux<AguiEvent> run(RunAgentInput input, RuntimeContext runtimeContext) {
            if (runCount.incrementAndGet() == 1) {
                return firstRunEvents
                        .doOnRequest(ignored -> firstRunSubscribed.countDown())
                        .doFinally(signalType -> firstRunTerminated.countDown());
            }
            return Flux.empty();
        }
    }
}
