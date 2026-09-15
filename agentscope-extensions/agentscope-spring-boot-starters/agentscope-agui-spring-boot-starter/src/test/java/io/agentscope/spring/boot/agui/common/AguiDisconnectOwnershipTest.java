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
package io.agentscope.spring.boot.agui.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AgentResolver;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.processor.AguiResumeStateStore;
import io.agentscope.core.agui.processor.InMemoryAguiResumeStateStore;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.model.Model;
import io.agentscope.spring.boot.agui.mvc.AguiMvcController;
import io.agentscope.spring.boot.agui.webflux.AguiWebFluxHandler;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.ReactiveHttpOutputMessage;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/** Real handler/callback and agent state, with controlled response demand instead of a socket. */
class AguiDisconnectOwnershipTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @ParameterizedTest
    @ValueSource(strings = {"webflux", "mvc-error", "mvc-timeout"})
    void lateDisconnectDoesNotInterruptNewOwner(String transport) throws Exception {
        AtomicReference<Object> lifecycle = new AtomicReference<>();
        String hook = "agui-disconnect-cleanup-observer";
        InMemoryAguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        Map<String, AguiEvent.Interrupt> pending = pending("I");
        assertTrue(store.claimRun("T", "seed").claimed());
        assertTrue(store.replacePendingInterrupts("T", "seed", pending));
        store.releaseRun("T", "seed");
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch finishRelease = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            released.countDown();
                            assertTrue(finishRelease.await(5, TimeUnit.SECONDS));
                            return null;
                        })
                .when(store)
                .releaseRun("T", "A");
        doAnswer(
                        call -> {
                            var claim = (AguiResumeStateStore.RunClaim) call.callRealMethod();
                            assertTrue(claim.claimed());
                            return claim;
                        })
                .when(store)
                .claimRun("T", "A");
        ReActAgent agent =
                ReActAgent.builder().name("disconnect-test").model(mock(Model.class)).build();
        AguiAgentRegistry registry = new AguiAgentRegistry();
        registry.register("default", agent);
        SlowWriter writer = new SlowWriter();
        Disposable writing = null;
        Disposable owner = null;
        ExecutorService executor = null;
        Runnable disconnect = null;
        // Observe the actual usingWhen resource without requesting/subscribing to another stream.
        // The pinned Reactor operator keeps this resource in a scalar Mono.just supplier.
        Hooks.onEachOperator(
                hook,
                publisher -> {
                    if (publisher.getClass().getSimpleName().equals("FluxUsingWhen")) {
                        Object supplier =
                                ReflectionTestUtils.getField(publisher, "resourceSupplier");
                        Object resource =
                                supplier != null
                                                && supplier.getClass()
                                                        .getSimpleName()
                                                        .equals("MonoJust")
                                        ? ReflectionTestUtils.getField(supplier, "value")
                                        : null;
                        if (resource != null
                                && resource.getClass().getSimpleName().equals("AguiRunLifecycle")) {
                            RunAgentInput run =
                                    (RunAgentInput) ReflectionTestUtils.getField(resource, "input");
                            if (run.getRunId().equals("A")) lifecycle.set(resource);
                        }
                    }
                    return publisher;
                });
        try {
            if (transport.equals("webflux")) {
                AguiWebFluxHandler handler =
                        AguiWebFluxHandler.builder()
                                .agentRegistry(registry)
                                .resumeStateStore(store)
                                .interruptOnDisconnect(true)
                                .adapterFactory(
                                        (a, c) -> {
                                            throw new AssertionError("invalid A executed");
                                        })
                                .build();
                var exchange =
                        MockServerWebExchange.from(MockServerHttpRequest.post("/run").build());
                ServerRequest request = ServerRequest.create(exchange, List.of());
                Mono<ServerResponse> response =
                        ReflectionTestUtils.invokeMethod(
                                handler, "processInput", input("A", false), request, null);
                ServerResponse.Context context =
                        new ServerResponse.Context() {
                            @Override
                            public List<HttpMessageWriter<?>> messageWriters() {
                                return List.of(writer);
                            }

                            @Override
                            public List<ViewResolver> viewResolvers() {
                                return List.of();
                            }
                        };
                writing = response.block(TIMEOUT).writeTo(exchange, context).subscribe();
                disconnect = writer.subscriber::cancel;
            } else {
                AguiMvcController controller =
                        AguiMvcController.builder()
                                .agentRegistry(registry)
                                .resumeStateStore(store)
                                .interruptOnDisconnect(true)
                                .adapterFactory(
                                        (a, c) -> {
                                            throw new AssertionError("invalid A executed");
                                        })
                                .build();
                executor =
                        (ExecutorService)
                                ReflectionTestUtils.getField(controller, "executorService");
                SseEmitter emitter = controller.handle(input("A", false), null);
                disconnect =
                        () -> {
                            if (transport.equals("mvc-error")) {
                                Object callback =
                                        ReflectionTestUtils.getField(emitter, "errorCallback");
                                ReflectionTestUtils.invokeMethod(
                                        callback, "accept", new IOException("disconnected"));
                            } else {
                                Object callback =
                                        ReflectionTestUtils.getField(emitter, "timeoutCallback");
                                ReflectionTestUtils.invokeMethod(callback, "run");
                            }
                        };
            }
            assertTrue(released.await(5, TimeUnit.SECONDS));
            assertEquals(0, writer.consumed.get());
            CountDownLatch running = new CountDownLatch(1);
            AtomicInteger received = new AtomicInteger();
            CountDownLatch tokenReceived = new CountDownLatch(1);
            Sinks.Many<AguiEvent> tokens = Sinks.many().unicast().onBackpressureBuffer();
            AgentResolver resolver = mock(AgentResolver.class);
            when(resolver.resolveAgent(any(), any(), any())).thenReturn(agent);
            AguiRequestProcessor second =
                    AguiRequestProcessor.builder()
                            .agentResolver(resolver)
                            .resumeStateStore(store)
                            .adapterFactory(
                                    (a, c) ->
                                            new AguiAgentAdapter(a, c) {
                                                @Override
                                                public Flux<AguiEvent> run(
                                                        RunAgentInput i, RuntimeContext rc) {
                                                    return tokens.asFlux()
                                                            .doOnRequest(n -> running.countDown());
                                                }
                                            })
                            .build();
            owner =
                    second.process(
                                    AguiRuntimeContextRequest.builder()
                                            .input(input("B", true))
                                            .build())
                            .events()
                            .doOnNext(
                                    e -> {
                                        received.incrementAndGet();
                                        tokenReceived.countDown();
                                    })
                            .subscribe();
            assertTrue(running.await(5, TimeUnit.SECONDS));
            assertEquals("B", store.claimRun("T", "probe").activeRunId());
            Map<String, AguiEvent.Interrupt> nextPending = pending("J");
            assertTrue(store.replacePendingInterrupts("T", "B", nextPending));
            assertFalse(agent.getAgentState(null, "T").interruptControl().isInterrupted());

            // Cancellation must return while A's release still holds its Store monitor.
            Mono.fromRunnable(disconnect).subscribeOn(Schedulers.parallel()).block(TIMEOUT);
            assertFalse(
                    agent.getAgentState(null, "T").interruptControl().isInterrupted(),
                    "late A disconnect interrupted the new owner B's real agent session");
            assertNotNull(lifecycle.get());
            AtomicReference<?> cleanup =
                    (AtomicReference<?>) ReflectionTestUtils.getField(lifecycle.get(), "cleanup");
            assertNotNull(cleanup.get(), "A cleanup must already have started before joining it");
            finishRelease.countDown();
            Mono<?> completedCleanup =
                    ReflectionTestUtils.invokeMethod(
                            lifecycle.get(), "finish", "test-observe", null);
            completedCleanup.block(TIMEOUT);
            assertFalse(
                    agent.getAgentState(null, "T").interruptControl().isInterrupted(),
                    "A cleanup asynchronously interrupted B after the release gate opened");
            assertEquals(
                    Sinks.EmitResult.OK,
                    tokens.tryEmitNext(
                            new AguiEvent.TextMessageContent(
                                    "T", "B", "M", "still running", null, null)));
            assertTrue(tokenReceived.await(5, TimeUnit.SECONDS));
            assertEquals(1, received.get());
            assertFalse(owner.isDisposed());
            assertEquals("B", store.claimRun("T", "probe").activeRunId());
            assertEquals(nextPending, store.getPendingInterrupts("T"));
        } finally {
            Hooks.resetOnEachOperator(hook);
            finishRelease.countDown();
            if (disconnect != null) disconnect.run();
            if (owner != null) owner.dispose();
            if (writing != null) writing.dispose();
            if (executor != null) {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private static RunAgentInput input(String runId, boolean resume) {
        return RunAgentInput.builder()
                .threadId("T")
                .runId(runId)
                .resume(resume ? List.of(new AguiResume("I", "resolved", true)) : List.of())
                .build();
    }

    private static Map<String, AguiEvent.Interrupt> pending(String id) {
        return Map.of(
                id,
                new AguiEvent.Interrupt(id, "tool_call", "approve", "tool-1", null, null, null));
    }

    private static class SlowWriter implements HttpMessageWriter<Object> {
        final AtomicInteger consumed = new AtomicInteger();
        final BaseSubscriber<Object> subscriber =
                new BaseSubscriber<>() {
                    @Override
                    protected void hookOnSubscribe(Subscription subscription) {}

                    @Override
                    protected void hookOnNext(Object event) {
                        consumed.incrementAndGet();
                    }
                };

        @Override
        public List<MediaType> getWritableMediaTypes() {
            return List.of(MediaType.TEXT_EVENT_STREAM);
        }

        @Override
        public boolean canWrite(ResolvableType type, MediaType mediaType) {
            return true;
        }

        @Override
        public Mono<Void> write(
                Publisher<?> input,
                ResolvableType type,
                MediaType mediaType,
                ReactiveHttpOutputMessage output,
                Map<String, Object> hints) {
            return Mono.defer(
                    () -> {
                        Flux.from(input).subscribe(subscriber);
                        return Mono.never();
                    });
        }
    }
}
