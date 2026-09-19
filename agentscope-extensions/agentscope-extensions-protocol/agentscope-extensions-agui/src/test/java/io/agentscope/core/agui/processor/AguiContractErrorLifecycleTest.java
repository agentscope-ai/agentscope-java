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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class AguiContractErrorLifecycleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Map<String, AguiEvent.Interrupt> PENDING =
            Map.of(
                    "I",
                    new AguiEvent.Interrupt(
                            "I", "tool_call", "approve", "tool-1", null, null, null));
    private static final Map<String, AguiEvent.Interrupt> NEW_PENDING =
            Map.of(
                    "J",
                    new AguiEvent.Interrupt("J", "tool_call", "next", "tool-2", null, null, null));

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateContractErrorTerminationPreservesNewOwner(boolean cancel) throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.subscribe();
            fixture.awaitRelease();
            fixture.assertUntouched();
            CountDownLatch running = new CountDownLatch(1);
            AguiRequestProcessor second =
                    processor(
                            fixture.store,
                            mock(Agent.class),
                            Flux.<AguiEvent>never().doOnSubscribe(s -> running.countDown()),
                            false);
            Disposable owner = second.process(request(input("B", true))).events().subscribe();
            try {
                await(running);
                assertEquals("B", fixture.store.claimRun("T", "probe").activeRunId());
                assertTrue(fixture.store.replacePendingInterrupts("T", "B", NEW_PENDING));
                if (cancel) {
                    fixture.subscriber.cancel();
                } else {
                    fixture.subscriber.request(3);
                    await(fixture.subscriber.terminated);
                    assertProtocol(fixture.subscriber.events, true);
                }
                assertEquals("B", fixture.store.claimRun("T", "probe").activeRunId());
                assertEquals(NEW_PENDING, fixture.store.getPendingInterrupts("T"));
                assertEquals(1, fixture.releases.get());
            } finally {
                owner.dispose();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialContractErrorDemandPreservesProtocol(boolean legacyFinish) throws Exception {
        try (Fixture fixture = new Fixture(legacyFinish)) {
            fixture.subscribe();
            fixture.awaitRelease();
            fixture.assertUntouched();
            fixture.subscriber.request(1);
            await(fixture.subscriber.firstEvent);
            assertEquals(1, fixture.subscriber.events.size());
            assertInstanceOf(AguiEvent.RunStarted.class, fixture.subscriber.events.get(0));
            assertFalse(fixture.subscriber.isDisposed());
            assertTrue(fixture.store.claimRun("T", "B").claimed());
            fixture.subscriber.request(2);
            await(fixture.subscriber.terminated);
            assertProtocol(fixture.subscriber.events, legacyFinish);
            assertEquals(1, fixture.releases.get());
            assertEquals("B", fixture.store.claimRun("T", "probe").activeRunId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "other"})
    void rejectedClaimDoesNotReleaseOwnerOrChangePending(String existingOwner) throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            assertTrue(fixture.store.claimRun("T", existingOwner).claimed());
            fixture.subscribe();
            await(fixture.contractError);
            assertFalse(fixture.subscriber.isDisposed());
            assertTrue(fixture.subscriber.events.isEmpty());
            fixture.subscriber.request(2);
            await(fixture.subscriber.terminated);
            assertEquals(existingOwner, fixture.store.claimRun("T", "probe").activeRunId());
            assertEquals(0, fixture.releases.get());
            fixture.assertUntouched();
            assertTrue(
                    ((AguiEvent.RunError) fixture.subscriber.events.get(1))
                            .message()
                            .contains("Thread already has an active run"));
        }
    }

    @Test
    void indeterminateClaimDoesNotRetryOrRelease() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            doAnswer(
                            call -> {
                                call.callRealMethod();
                                throw new IllegalStateException("claim reply lost");
                            })
                    .when(fixture.store)
                    .claimRun("T", "A");
            fixture.subscribe();
            fixture.subscriber.request(2);
            await(fixture.subscriber.terminated);
            verify(fixture.store, times(1)).claimRun("T", "A");
            assertEquals(0, fixture.releases.get());
            verify(fixture.store, never()).getPendingInterrupts("T");
            assertEquals("A", fixture.store.claimRun("T", "probe").activeRunId());
            fixture.assertUntouched();
            assertEquals(
                    "claim reply lost",
                    ((AguiEvent.RunError) fixture.subscriber.events.get(1)).message());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void contractErrorReleaseRetriesWithoutDemand(boolean cancel) throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            AtomicInteger attempts = new AtomicInteger();
            doAnswer(
                            call -> {
                                assertFalse(Schedulers.isInNonBlockingThread());
                                if (attempts.incrementAndGet() < 3) {
                                    throw new IllegalStateException("temporary release failure");
                                }
                                call.callRealMethod();
                                fixture.released.countDown();
                                return null;
                            })
                    .when(fixture.store)
                    .releaseRun("T", "A");
            fixture.subscribe();
            fixture.awaitRelease();
            assertTrue(fixture.store.claimRun("T", "B").claimed());
            assertTrue(fixture.subscriber.events.isEmpty());
            assertFalse(fixture.subscriber.isDisposed());
            if (cancel) {
                fixture.subscriber.cancel();
            } else {
                fixture.subscriber.request(2);
                await(fixture.subscriber.terminated);
            }
            assertEquals(3, attempts.get());
            verify(fixture.store, times(1)).claimRun("T", "A");
            fixture.assertUntouched();
        }
    }

    @Test
    void lostEarlyReleaseReplyPreservesNewOwner() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            AtomicInteger attempts = new AtomicInteger();
            doAnswer(
                            call -> {
                                call.callRealMethod();
                                if (attempts.incrementAndGet() == 1) {
                                    assertTrue(fixture.store.claimRun("T", "B").claimed());
                                    assertTrue(
                                            fixture.store.replacePendingInterrupts(
                                                    "T", "B", NEW_PENDING));
                                    throw new IllegalStateException(
                                            "release reply lost after commit");
                                }
                                fixture.released.countDown();
                                return null;
                            })
                    .when(fixture.store)
                    .releaseRun("T", "A");
            fixture.subscribe();
            fixture.awaitRelease();
            assertTrue(fixture.subscriber.events.isEmpty());
            assertFalse(fixture.subscriber.isDisposed());
            assertEquals("B", fixture.store.claimRun("T", "probe").activeRunId());
            fixture.subscriber.request(2);
            await(fixture.subscriber.terminated);
            assertEquals(2, attempts.get());
            assertEquals("B", fixture.store.claimRun("T", "probe").activeRunId());
            assertEquals(NEW_PENDING, fixture.store.getPendingInterrupts("T"));
        }
    }

    @Test
    void exhaustedCleanupIsSharedAndPreservesLatePrimaryError() {
        AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        RuntimeException failure = new IllegalStateException("release unavailable");
        doThrow(failure).when(store).releaseRun("T", "A");
        AguiRunLifecycle lifecycle =
                new AguiRunLifecycle(new AguiResumeCoordinator(store), input("A", false));
        assertFalse(lifecycle.call(lifecycle::begin).block(TIMEOUT).isError());
        List<Throwable> dropped = new CopyOnWriteArrayList<>();
        Hooks.onErrorDropped(dropped::add);
        try {
            lifecycle.finish("validation", null).block(TIMEOUT);
            RuntimeException primary = new IllegalStateException("primary");
            Mono.when(
                            lifecycle.finish("complete", null),
                            lifecycle.finish("cancel", null),
                            lifecycle.finish("error", primary),
                            lifecycle.finish("error", primary))
                    .block(TIMEOUT);
            verify(store, times(3)).releaseRun("T", "A");
            assertArrayEquals(new Throwable[] {failure}, primary.getSuppressed());
            assertEquals("A", store.claimRun("T", "probe").activeRunId());
            assertTrue(dropped.isEmpty());
        } finally {
            Hooks.resetOnErrorDropped();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "cleanup"})
    void cancellationWaitsForInFlightOperationWithoutDuplicateRelease(String phase)
            throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            CountDownLatch returned = new CountDownLatch(1);
            if (phase.equals("pending")) {
                doAnswer(
                                call -> {
                                    Object result = call.callRealMethod();
                                    entered.countDown();
                                    awaitUninterruptibly(proceed);
                                    returned.countDown();
                                    return result;
                                })
                        .when(fixture.store)
                        .getPendingInterrupts("T");
            } else {
                doAnswer(
                                call -> {
                                    fixture.releases.incrementAndGet();
                                    entered.countDown();
                                    awaitUninterruptibly(proceed);
                                    call.callRealMethod();
                                    returned.countDown();
                                    fixture.released.countDown();
                                    return null;
                                })
                        .when(fixture.store)
                        .releaseRun("T", "A");
            }
            List<Throwable> dropped = new CopyOnWriteArrayList<>();
            Hooks.onErrorDropped(dropped::add);
            try {
                fixture.subscribe();
                await(entered);
                // Cancellation on an event loop must return even while the Store holds its monitor.
                Mono.fromRunnable(fixture.subscriber::cancel)
                        .subscribeOn(Schedulers.parallel())
                        .block(TIMEOUT);
                assertEquals(1, returned.getCount());
                proceed.countDown();
                await(returned);
                await(fixture.released);
                assertEquals(1, fixture.releases.get());
                assertTrue(fixture.store.claimRun("T", "B").claimed());
                verify(fixture.store, times(1)).claimRun("T", "A");
                fixture.assertUntouched();
                assertTrue(dropped.isEmpty());
            } finally {
                proceed.countDown();
                Hooks.resetOnErrorDropped();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledClaimJoinsCleanupBeforeCheckingOwnership(boolean unknown) throws Exception {
        AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        assertTrue(store.claimRun("T", "seed").claimed());
        assertTrue(store.replacePendingInterrupts("T", "seed", PENDING));
        store.releaseRun("T", "seed");
        if (!unknown) {
            assertTrue(store.claimRun("T", "owner").claimed());
        }
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        CountDownLatch cleanupSubscribed = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            Object result = call.callRealMethod();
                            entered.countDown();
                            awaitUninterruptibly(proceed);
                            if (unknown) {
                                throw new IllegalStateException("claim reply lost");
                            }
                            return result;
                        })
                .when(store)
                .claimRun("T", "A");
        AguiRunLifecycle lifecycle =
                spy(new AguiRunLifecycle(new AguiResumeCoordinator(store), input("A", false)));
        doAnswer(
                        call -> {
                            Mono<?> result = (Mono<?>) call.callRealMethod();
                            return result.doOnSubscribe(s -> cleanupSubscribed.countDown()).then();
                        })
                .when(lifecycle)
                .finish("cancel", null);
        DemandSubscriber subscriber = new DemandSubscriber();
        List<Throwable> dropped = new CopyOnWriteArrayList<>();
        Hooks.onErrorDropped(dropped::add);
        try {
            Flux.usingWhen(
                            Mono.just(lifecycle),
                            run -> run.call(run::begin).thenMany(Flux.<AguiEvent>never()),
                            run -> run.finish("complete", null),
                            (run, error) -> run.finish("error", error),
                            run -> run.finish("cancel", null))
                    .subscribe(subscriber);
            await(entered);
            Mono.fromRunnable(subscriber::cancel).subscribeOn(Schedulers.parallel()).block(TIMEOUT);
            // Observe the real cancellation subscriber before opening the claim gate.
            await(cleanupSubscribed);
            proceed.countDown();
            // Join that existing cleanup; this must not create a second process or retry budget.
            lifecycle.finish("observe", null).block(TIMEOUT);
            verify(store, times(1)).claimRun("T", "A");
            verify(store, never()).releaseRun("T", "A");
            assertEquals(unknown ? "A" : "owner", store.claimRun("T", "probe").activeRunId());
            assertEquals(PENDING, store.getPendingInterrupts("T"));
            assertTrue(subscriber.events.isEmpty());
            assertTrue(dropped.isEmpty());
        } finally {
            proceed.countDown();
            subscriber.cancel();
            Hooks.resetOnErrorDropped();
        }
    }

    @Test
    void validationReadFailureReleasesWithoutDemand() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            doThrow(new IllegalStateException("read failed"))
                    .when(fixture.store)
                    .getPendingInterrupts("T");
            fixture.subscribe();
            await(fixture.released);
            assertTrue(fixture.subscriber.events.isEmpty());
            assertFalse(fixture.subscriber.isDisposed());
            assertTrue(fixture.store.claimRun("T", "B").claimed());
            fixture.subscriber.request(2);
            await(fixture.subscriber.terminated);
            assertEquals(
                    "read failed",
                    ((AguiEvent.RunError) fixture.subscriber.events.get(1)).message());
            assertEquals(1, fixture.releases.get());
        }
    }

    @Test
    void ordinaryTokenEventsStayOnOriginalScheduler() {
        AguiResumeStateStore store = new InMemoryAguiResumeStateStore();
        List<Thread> source = new CopyOnWriteArrayList<>();
        List<Thread> delivered = new CopyOnWriteArrayList<>();
        Flux<AguiEvent> events =
                Flux.<AguiEvent>just(
                                new AguiEvent.TextMessageContent(
                                        "T", "A", "M", "first", null, null),
                                new AguiEvent.TextMessageContent(
                                        "T", "A", "M", "second", null, null))
                        .publishOn(Schedulers.parallel())
                        .doOnNext(e -> source.add(Thread.currentThread()));
        processor(store, mock(Agent.class), events, false)
                .process(request(input("A", false)))
                .events()
                .doOnNext(e -> delivered.add(Thread.currentThread()))
                .collectList()
                .block(TIMEOUT);
        assertEquals(2, source.size());
        assertEquals(source, delivered);
        assertTrue(source.get(0).getName().startsWith("parallel"));
    }

    private static class Fixture implements AutoCloseable {
        final AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        final Agent agent = mock(Agent.class);
        final AtomicInteger executions = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        final CountDownLatch released = new CountDownLatch(1);
        final CountDownLatch contractError = new CountDownLatch(1);
        final DemandSubscriber subscriber = new DemandSubscriber();
        final AguiRequestProcessor processor;
        final AguiResumeCoordinator coordinator;

        Fixture(boolean legacyFinish) throws Exception {
            assertTrue(store.claimRun("T", "seed").claimed());
            assertTrue(store.replacePendingInterrupts("T", "seed", PENDING));
            store.releaseRun("T", "seed");
            doAnswer(
                            call -> {
                                assertFalse(Schedulers.isInNonBlockingThread());
                                releases.incrementAndGet();
                                call.callRealMethod();
                                released.countDown();
                                return null;
                            })
                    .when(store)
                    .releaseRun("T", "A");
            processor =
                    processor(
                            store,
                            agent,
                            Flux.defer(
                                    () -> {
                                        executions.incrementAndGet();
                                        return Flux.never();
                                    }),
                            legacyFinish);
            Field field = AguiRequestProcessor.class.getDeclaredField("resumeCoordinator");
            field.setAccessible(true);
            coordinator = spy((AguiResumeCoordinator) field.get(processor));
            doAnswer(
                            call -> {
                                Object result = call.callRealMethod();
                                contractError.countDown();
                                return result;
                            })
                    .when(coordinator)
                    .contractErrorEvents(any(), anyString(), eq(legacyFinish));
            field.set(processor, coordinator);
        }

        void subscribe() {
            processor.process(request(input("A", false))).events().subscribe(subscriber);
        }

        void awaitRelease() throws Exception {
            await(contractError);
            await(released);
            assertTrue(subscriber.events.isEmpty());
            assertFalse(subscriber.isDisposed());
        }

        void assertUntouched() {
            assertEquals(0, executions.get());
            verifyNoInteractions(agent);
            verify(coordinator, never()).addResumeInterrupts(any(), any());
            verify(store, never()).replacePendingInterrupts(eq("T"), eq("A"), anyMap());
            assertEquals(PENDING, store.getPendingInterrupts("T"));
        }

        @Override
        public void close() {
            subscriber.cancel();
        }
    }

    private static final class DemandSubscriber extends BaseSubscriber<AguiEvent> {
        final List<AguiEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch firstEvent = new CountDownLatch(1);
        final CountDownLatch terminated = new CountDownLatch(1);
        volatile Throwable failure;

        @Override
        protected void hookOnSubscribe(Subscription subscription) {}

        @Override
        protected void hookOnNext(AguiEvent event) {
            events.add(event);
            firstEvent.countDown();
        }

        @Override
        protected void hookOnComplete() {
            terminated.countDown();
        }

        @Override
        protected void hookOnError(Throwable error) {
            failure = error;
            terminated.countDown();
        }
    }

    private static void assertProtocol(List<AguiEvent> events, boolean legacyFinish) {
        assertEquals(legacyFinish ? 3 : 2, events.size());
        AguiEvent.RunStarted started = assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        assertEquals("T", started.threadId());
        assertEquals("A", started.runId());
        AguiEvent.RunError error = assertInstanceOf(AguiEvent.RunError.class, events.get(1));
        assertEquals(AguiResumeCoordinator.CONTRACT_ERROR_CODE, error.code());
        assertTrue(error.message().contains("unresolved interrupts"));
        assertEquals("T", error.threadId());
        assertEquals("A", error.runId());
        assertTrue(error.timestamp() > 0);
        if (legacyFinish) {
            assertInstanceOf(AguiEvent.RunFinished.class, events.get(2));
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(
                latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                "lifecycle transition timed out");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        boolean interrupted = false;
        try {
            for (; ; ) {
                long left = deadline - System.nanoTime();
                assertTrue(left > 0, "in-flight operation was never allowed to return");
                try {
                    assertTrue(latch.await(left, TimeUnit.NANOSECONDS));
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static RunAgentInput input(String runId, boolean resume) {
        return RunAgentInput.builder()
                .threadId("T")
                .runId(runId)
                .resume(
                        resume
                                ? List.of(new AguiResume("I", AguiResume.STATUS_RESOLVED, true))
                                : List.of())
                .build();
    }

    private static AguiRuntimeContextRequest<?> request(RunAgentInput input) {
        return AguiRuntimeContextRequest.builder().input(input).build();
    }

    private static AguiRequestProcessor processor(
            AguiResumeStateStore store, Agent agent, Flux<AguiEvent> events, boolean legacyFinish) {
        AgentResolver resolver = mock(AgentResolver.class);
        when(resolver.resolveAgent(anyString(), anyString(), nullable(String.class)))
                .thenReturn(agent);
        return AguiRequestProcessor.builder()
                .agentResolver(resolver)
                .resumeStateStore(store)
                .config(AguiAdapterConfig.builder().emitRunFinishedAfterError(legacyFinish).build())
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
}
