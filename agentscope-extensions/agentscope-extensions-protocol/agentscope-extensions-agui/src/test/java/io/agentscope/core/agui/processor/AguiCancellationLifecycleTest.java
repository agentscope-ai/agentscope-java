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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class AguiCancellationLifecycleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void closeSchedulingFailureCannotReleaseAnExecutionThatWasNotClosed() {
        InMemoryAguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        AguiRunLifecycle lifecycle =
                new AguiRunLifecycle(
                        new AguiResumeCoordinator(store),
                        RunAgentInput.builder().threadId("T").runId("A").build());
        AtomicInteger interrupts = new AtomicInteger();
        assertFalse(
                lifecycle
                        .call(
                                () -> {
                                    var result = lifecycle.begin();
                                    lifecycle.setCancelAction(interrupts::incrementAndGet);
                                    return result;
                                })
                        .block(TIMEOUT)
                        .isError());
        Scheduler delegate = Schedulers.immediate();
        Scheduler scheduler = mock(Scheduler.class);
        AtomicInteger scheduled = new AtomicInteger();
        when(scheduler.schedule(any(Runnable.class)))
                .thenAnswer(
                        call -> {
                            if (scheduled.incrementAndGet() == 1)
                                throw new RejectedExecutionException("close rejected");
                            return delegate.schedule(call.getArgument(0));
                        });
        RuntimeException primary = new IllegalStateException("primary");
        List<Throwable> dropped = new CopyOnWriteArrayList<>();
        Hooks.onErrorDropped(dropped::add);
        try {
            try (MockedStatic<Schedulers> schedulers =
                    mockStatic(Schedulers.class, Mockito.CALLS_REAL_METHODS)) {
                schedulers.when(Schedulers::boundedElastic).thenReturn(scheduler);
                lifecycle.finish("cancel", primary).block(TIMEOUT);
            }
            // The scheduler is usable again, but joining cannot invent another cleanup budget.
            lifecycle.finish("observe", primary).block(TIMEOUT);
            assertEquals(1, scheduled.get());
            assertEquals(0, interrupts.get());
            assertEquals("A", store.claimRun("T", "B").activeRunId());
            verify(store, never()).releaseRun("T", "A");
            assertEquals(1, primary.getSuppressed().length);
            assertTrue(primary.getSuppressed()[0] instanceof RejectedExecutionException);
            assertTrue(dropped.isEmpty());
        } finally {
            Hooks.resetOnErrorDropped();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void interruptCompletesBeforeReleaseAndIsNotRetried(int failedReleases) throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch interruptEntered = new CountDownLatch(1);
            CountDownLatch finishInterrupt = new CountDownLatch(1);
            doAnswer(
                            call -> {
                                assertFalse(Schedulers.isInNonBlockingThread());
                                RuntimeContext context = call.getArgument(0);
                                assertEquals("U", context.getUserId());
                                assertEquals("T", context.getSessionId());
                                interruptEntered.countDown();
                                await(finishInterrupt);
                                return null;
                            })
                    .when(fixture.agent)
                    .interrupt(any(RuntimeContext.class));
            AtomicInteger attempts = new AtomicInteger();
            doAnswer(
                            call -> {
                                assertEquals(0, finishInterrupt.getCount());
                                if (attempts.incrementAndGet() <= failedReleases)
                                    throw new IllegalStateException("release unavailable");
                                call.callRealMethod();
                                fixture.released.countDown();
                                return null;
                            })
                    .when(fixture.store)
                    .releaseRun("T", "A");
            fixture.start(true, Flux.never(), null);
            try {
                await(fixture.running);
                fixture.cancelOnEventLoop();
                await(interruptEntered);
                assertEquals("A", fixture.store.claimRun("T", "B").activeRunId());
                assertEquals(0, attempts.get());
                finishInterrupt.countDown();
                await(fixture.released);
                assertEquals(failedReleases + 1, attempts.get());
                verify(fixture.agent, times(1)).interrupt(any(RuntimeContext.class));
                assertTrue(fixture.store.claimRun("T", "B").claimed());
                fixture.cancelOnEventLoop();
                assertEquals("B", fixture.store.claimRun("T", "probe").activeRunId());
                verify(fixture.agent, times(1)).interrupt(any(RuntimeContext.class));
            } finally {
                finishInterrupt.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptFailureStillReleasesOwnership(boolean nonfatalError) throws Exception {
        List<Throwable> dropped = new CopyOnWriteArrayList<>();
        Hooks.onErrorDropped(dropped::add);
        try (Fixture fixture = new Fixture()) {
            doAnswer(
                            call -> {
                                if (nonfatalError) throw new AssertionError("interrupt failed");
                                throw new IllegalStateException("interrupt failed");
                            })
                    .when(fixture.agent)
                    .interrupt(any(RuntimeContext.class));
            fixture.start(true, Flux.never(), null);
            await(fixture.running);
            fixture.cancelOnEventLoop();
            await(fixture.released);
            verify(fixture.agent, times(1)).interrupt(any(RuntimeContext.class));
            assertTrue(fixture.store.claimRun("T", "B").claimed());
            assertTrue(dropped.isEmpty());
        } finally {
            Hooks.resetOnErrorDropped();
        }
    }

    @Test
    void cancelWaitsForValidSetupWithoutBlockingEventLoop() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch setupEntered = new CountDownLatch(1);
            CountDownLatch finishSetup = new CountDownLatch(1);
            fixture.start(
                    true,
                    Flux.never(),
                    () -> {
                        setupEntered.countDown();
                        awaitUninterruptibly(finishSetup);
                    });
            try {
                await(setupEntered);
                fixture.cancelOnEventLoop();
                assertEquals("A", fixture.store.claimRun("T", "B").activeRunId());
                verify(fixture.agent, never()).interrupt(any(RuntimeContext.class));
                finishSetup.countDown();
                await(fixture.released);
                verify(fixture.agent, times(1)).interrupt(any(RuntimeContext.class));
                assertTrue(fixture.store.claimRun("T", "B").claimed());
            } finally {
                finishSetup.countDown();
            }
        }
    }

    @Test
    void lateCancelJoinsNormalCleanupWithoutInterrupting() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch releaseEntered = new CountDownLatch(1);
            CountDownLatch finishRelease = new CountDownLatch(1);
            doAnswer(
                            call -> {
                                releaseEntered.countDown();
                                await(finishRelease);
                                call.callRealMethod();
                                fixture.released.countDown();
                                return null;
                            })
                    .when(fixture.store)
                    .releaseRun("T", "A");
            fixture.start(true, Flux.empty(), null);
            try {
                await(releaseEntered);
                fixture.cancelOnEventLoop();
                assertEquals("A", fixture.store.claimRun("T", "B").activeRunId());
                finishRelease.countDown();
                await(fixture.released);
                assertTrue(fixture.store.claimRun("T", "B").claimed());
                fixture.cancelOnEventLoop();
                verify(fixture.agent, never()).interrupt(any(RuntimeContext.class));
                verify(fixture.store, times(1)).releaseRun("T", "A");
                assertEquals("B", fixture.store.claimRun("T", "probe").activeRunId());
            } finally {
                finishRelease.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "rejected", "unknown"})
    void unownedOrInvalidRunCannotInterrupt(String phase) throws Exception {
        try (Fixture fixture = new Fixture()) {
            if (phase.equals("rejected")) assertTrue(fixture.store.claimRun("T", "B").claimed());
            if (phase.equals("unknown")) {
                doAnswer(
                                call -> {
                                    call.callRealMethod();
                                    throw new IllegalStateException("claim reply lost");
                                })
                        .when(fixture.store)
                        .claimRun("T", "A");
            }
            RunAgentInput input =
                    RunAgentInput.builder()
                            .threadId("T")
                            .runId("A")
                            .resume(List.of(new AguiResume("missing", "resolved", true)))
                            .build();
            CountDownLatch first = new CountDownLatch(1);
            BaseSubscriber<AguiEvent> subscriber =
                    new BaseSubscriber<>() {
                        @Override
                        protected void hookOnSubscribe(Subscription s) {
                            request(1);
                        }

                        @Override
                        protected void hookOnNext(AguiEvent e) {
                            first.countDown();
                        }
                    };
            fixture.subscription = subscriber;
            fixture.builder(true, Flux.never(), null)
                    .build()
                    .process(AguiRuntimeContextRequest.builder().input(input).build())
                    .events()
                    .subscribe(subscriber);
            await(first); // Contract/error cleanup has finished before its first error-lifecycle
            // event.
            fixture.cancelOnEventLoop();
            verify(fixture.agent, never()).interrupt(any(RuntimeContext.class));
            assertEquals(1, fixture.running.getCount());
            if (!phase.equals("invalid")) verify(fixture.store, never()).releaseRun("T", "A");
        }
    }

    @Test
    void processorDefaultCancellationDoesNotInterruptAgent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start(false, Flux.never(), null);
            await(fixture.running);
            fixture.cancelOnEventLoop();
            await(fixture.released);
            verify(fixture.agent, never()).interrupt(any(RuntimeContext.class));
            assertTrue(fixture.store.claimRun("T", "B").claimed());
        }
    }

    private static class Fixture implements AutoCloseable {
        final InMemoryAguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        final ReActAgent agent = mock(ReActAgent.class);
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        Disposable subscription;

        Fixture() {
            doAnswer(
                            call -> {
                                call.callRealMethod();
                                released.countDown();
                                return null;
                            })
                    .when(store)
                    .releaseRun("T", "A");
        }

        AguiRequestProcessor.Builder builder(
                boolean interrupt, Flux<AguiEvent> events, Runnable setup) {
            AgentResolver resolver = mock(AgentResolver.class);
            when(resolver.resolveAgent(any(), any(), any())).thenReturn(agent);
            AguiRequestProcessor.Builder builder =
                    AguiRequestProcessor.builder()
                            .agentResolver(resolver)
                            .runtimeContextResolver(
                                    request -> RuntimeContext.builder().userId("U").build())
                            .resumeStateStore(store)
                            .adapterFactory(
                                    (a, c) ->
                                            new AguiAgentAdapter(a, c) {
                                                @Override
                                                public Flux<AguiEvent> run(
                                                        RunAgentInput input,
                                                        RuntimeContext context) {
                                                    if (setup != null) setup.run();
                                                    return events.doOnSubscribe(
                                                            s -> running.countDown());
                                                }
                                            });
            if (interrupt) builder.interruptOnCancel(true);
            return builder;
        }

        void start(boolean interrupt, Flux<AguiEvent> events, Runnable setup) {
            subscription =
                    builder(interrupt, events, setup)
                            .build()
                            .process(
                                    AguiRuntimeContextRequest.builder()
                                            .input(
                                                    RunAgentInput.builder()
                                                            .threadId("T")
                                                            .runId("A")
                                                            .build())
                                            .build())
                            .events()
                            .subscribe();
        }

        void cancelOnEventLoop() {
            Mono.fromRunnable(subscription::dispose)
                    .subscribeOn(Schedulers.parallel())
                    .block(TIMEOUT);
        }

        @Override
        public void close() {
            if (subscription != null) subscription.dispose();
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "gate did not open");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        boolean interrupted = false;
        try {
            while (latch.getCount() != 0) {
                try {
                    long remaining = deadline - System.nanoTime();
                    assertTrue(remaining > 0 && latch.await(remaining, TimeUnit.NANOSECONDS));
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
