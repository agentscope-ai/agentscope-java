/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AguiResumeStateStore;
import io.agentscope.core.agui.processor.InMemoryAguiResumeStateStore;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.spring.boot.agui.webflux.AguiWebFluxHandler;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.LoopResources;

/** Runs the production WebFlux handler on an actual HTTP event loop. */
class AguiWebFluxLifecycleTest {
    @Test
    void productionHandlerOffloadsSynchronousStoreFromHttpEventLoop() throws Exception {
        AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        List<String> threads = new CopyOnWriteArrayList<>();
        List<Boolean> nonblocking = new CopyOnWriteArrayList<>();
        CountDownLatch released = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            threads.add(Thread.currentThread().getName());
                            nonblocking.add(Schedulers.isInNonBlockingThread());
                            return call.callRealMethod();
                        })
                .when(store)
                .claimRun(anyString(), anyString());
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            released.countDown();
                            return null;
                        })
                .when(store)
                .releaseRun(anyString(), anyString());
        ReActAgent agent = mock(ReActAgent.class);
        AguiWebFluxHandler handler =
                handler(store, agent, true, Flux.just(new AguiEvent.RunFinished("T", "A")));
        LoopResources loops = LoopResources.create("pr2890-http", 1, true);
        DisposableServer server = serve(handler, loops, new CountDownLatch(1));
        try {
            String body =
                    HttpClient.create()
                            .post()
                            .uri("http://127.0.0.1:" + server.port() + "/run")
                            .send(
                                    reactor.netty.ByteBufFlux.fromString(
                                            Mono.just("{\"threadId\":\"T\",\"runId\":\"A\"}")))
                            .responseSingle((response, content) -> content.asString())
                            .block(Duration.ofSeconds(5));
            assertNotNull(body);
            assertTrue(released.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(false), nonblocking);
            assertTrue(threads.get(0).startsWith("boundedElastic"));
        } finally {
            server.disposeNow();
            loops.disposeLater().block(Duration.ofSeconds(5));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realClientDisconnectFollowsInterruptOnDisconnect(boolean interruptOnDisconnect)
            throws Exception {
        AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        ReActAgent agent = mock(ReActAgent.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch disconnect = new CountDownLatch(1);
        CountDownLatch upstreamCancelled = new CountDownLatch(1);
        Sinks.Empty<Void> finish = Sinks.empty();
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            release.countDown();
                            return null;
                        })
                .when(store)
                .releaseRun("T", "A");
        Flux<AguiEvent> events =
                Flux.<AguiEvent>concat(
                                Flux.just(new AguiEvent.RunStarted("T", "A")),
                                finish.asMono()
                                        .thenMany(Flux.just(new AguiEvent.RunFinished("T", "A"))))
                        .doOnCancel(upstreamCancelled::countDown);
        AguiWebFluxHandler handler = handler(store, agent, interruptOnDisconnect, events);
        LoopResources loops = LoopResources.create("pr2890-disconnect", 1, true);
        DisposableServer server = serve(handler, loops, disconnect);
        try {
            HttpClient.create()
                    .post()
                    .uri("http://127.0.0.1:" + server.port() + "/run")
                    .send(
                            reactor.netty.ByteBufFlux.fromString(
                                    Mono.just("{\"threadId\":\"T\",\"runId\":\"A\"}")))
                    .response((response, content) -> content.asString().take(1))
                    .blockLast(Duration.ofSeconds(5));
            assertTrue(disconnect.await(5, TimeUnit.SECONDS));
            if (interruptOnDisconnect) {
                assertTrue(upstreamCancelled.await(5, TimeUnit.SECONDS));
                assertTrue(release.await(5, TimeUnit.SECONDS));
                verify(agent).interrupt(any(RuntimeContext.class));
                assertTrue(store.claimRun("T", "B").claimed());
            } else {
                verify(agent, never()).interrupt(any(RuntimeContext.class));
                assertEquals(1, upstreamCancelled.getCount());
                assertEquals(1, release.getCount());
                assertFalse(store.claimRun("T", "B").claimed());
                finish.tryEmitEmpty();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                assertTrue(store.claimRun("T", "B").claimed());
            }
        } finally {
            finish.tryEmitEmpty();
            server.disposeNow();
            loops.disposeLater().block(Duration.ofSeconds(5));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"claim", "read", "write"})
    void storeFailuresUseSseRunErrorLifecycle(String failurePhase) {
        AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        RuntimeException failure = new RuntimeException("store unavailable");
        switch (failurePhase) {
            case "claim" -> doThrow(failure).when(store).claimRun("T", "A");
            case "read" -> doThrow(failure).when(store).getPendingInterrupts("T");
            case "write" ->
                    doThrow(failure)
                            .when(store)
                            .replacePendingInterrupts("T", "A", java.util.Map.of());
            default -> throw new AssertionError(failurePhase);
        }
        AguiWebFluxHandler handler =
                handler(
                        store,
                        mock(ReActAgent.class),
                        true,
                        Flux.just(
                                new AguiEvent.RunStarted("T", "A"),
                                new AguiEvent.RunFinished("T", "A")));
        LoopResources loops = LoopResources.create("agui-error", 1, true);
        DisposableServer server = serve(handler, loops, new CountDownLatch(1));
        try {
            String body =
                    post(server)
                            .responseSingle(
                                    (response, content) -> {
                                        assertEquals(200, response.status().code());
                                        return content.asString();
                                    })
                            .block(Duration.ofSeconds(5));
            assertNotNull(body);
            // AG-UI uses data-only SSE frames; each JSON payload contains one event type.
            assertEquals(1, body.split("RUN_STARTED", -1).length - 1);
            assertEquals(1, body.split("RUN_ERROR", -1).length - 1);
            assertFalse(body.contains("RUN_FINISHED"));
            assertTrue(body.contains("INTERNAL_ERROR"));
            assertTrue(body.contains("store unavailable"));
        } finally {
            server.disposeNow();
            loops.disposeLater().block(Duration.ofSeconds(5));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "error", "cancel"})
    void exhaustedCleanupIsLoggedWithContextForEveryTermination(String phase) throws Exception {
        AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        doThrow(new RuntimeException("release unavailable")).when(store).releaseRun("T", "A");
        List<ILoggingEvent> logs = new CopyOnWriteArrayList<>();
        CountDownLatch exhausted = new CountDownLatch(1);
        AppenderBase<ILoggingEvent> appender =
                new AppenderBase<>() {
                    @Override
                    protected void append(ILoggingEvent event) {
                        logs.add(event);
                        if (event.getFormattedMessage().contains("release exhausted")) {
                            exhausted.countDown();
                        }
                    }
                };
        Logger logger =
                (Logger)
                        LoggerFactory.getLogger(
                                "io.agentscope.core.agui.processor.AguiRunLifecycle");
        appender.start();
        logger.addAppender(appender);
        Flux<AguiEvent> ending =
                switch (phase) {
                    case "complete" -> Flux.just(new AguiEvent.RunFinished("T", "A"));
                    case "error" -> Flux.error(new RuntimeException("primary error"));
                    case "cancel" -> Flux.never();
                    default -> throw new AssertionError(phase);
                };
        AguiWebFluxHandler handler =
                handler(
                        store,
                        mock(ReActAgent.class),
                        true,
                        Flux.concat(Flux.just(new AguiEvent.RunStarted("T", "A")), ending));
        LoopResources loops = LoopResources.create("agui-cleanup", 1, true);
        DisposableServer server = serve(handler, loops, new CountDownLatch(1));
        try {
            if (phase.equals("cancel")) {
                post(server)
                        .response((response, content) -> content.asString().take(1))
                        .blockLast(Duration.ofSeconds(5));
            } else {
                post(server)
                        .responseSingle((response, content) -> content.asString())
                        .block(Duration.ofSeconds(5));
            }
            assertTrue(exhausted.await(5, TimeUnit.SECONDS));
            verify(store, times(3)).releaseRun("T", "A");
            assertEquals(
                    2,
                    logs.stream()
                            .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                            .count());
            ILoggingEvent terminal = logs.get(logs.size() - 1);
            assertEquals(ch.qos.logback.classic.Level.ERROR, terminal.getLevel());
            assertTrue(
                    terminal.getFormattedMessage()
                            .contains("threadId=T, runId=A, phase=" + phase + ", attempts=3"));
            assertNotNull(terminal.getThrowableProxy());
            assertEquals("release unavailable", terminal.getThrowableProxy().getMessage());
            assertEquals("A", store.claimRun("T", "B").activeRunId());
        } finally {
            server.disposeNow();
            loops.disposeLater().block(Duration.ofSeconds(5));
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static HttpClient.ResponseReceiver<?> post(DisposableServer server) {
        return HttpClient.create()
                .post()
                .uri("http://127.0.0.1:" + server.port() + "/run")
                .send(
                        reactor.netty.ByteBufFlux.fromString(
                                Mono.just("{\"threadId\":\"T\",\"runId\":\"A\"}")));
    }

    private static AguiWebFluxHandler handler(
            AguiResumeStateStore store,
            ReActAgent agent,
            boolean interruptOnDisconnect,
            Flux<AguiEvent> events) {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        registry.register("default", agent);
        return AguiWebFluxHandler.builder()
                .agentRegistry(registry)
                .resumeStateStore(store)
                .interruptOnDisconnect(interruptOnDisconnect)
                .adapterFactory(
                        (a, c) ->
                                new AguiAgentAdapter(a, c) {
                                    @Override
                                    public Flux<AguiEvent> run(
                                            RunAgentInput input, RuntimeContext context) {
                                        return events;
                                    }
                                })
                .build();
    }

    private static DisposableServer serve(
            AguiWebFluxHandler handler, LoopResources loops, CountDownLatch disconnect) {
        var routes = RouterFunctions.route().POST("/run", handler::handle).build();
        return HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .runOn(loops)
                .doOnConnection(connection -> connection.onDispose(disconnect::countDown))
                .handle(new ReactorHttpHandlerAdapter(RouterFunctions.toHttpHandler(routes)))
                .bindNow();
    }
}
