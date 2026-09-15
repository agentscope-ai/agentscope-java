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
import reactor.core.scheduler.Schedulers;

class AguiResumeLifecycleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Map<String, AguiEvent.Interrupt> PENDING =
            Map.of(
                    "I",
                    new AguiEvent.Interrupt(
                            "I", "tool_call", "approve", "tool-1", null, null, null));

    @Test
    void contractErrorReleasesOwnershipWithoutDemand() throws Exception {
        AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        seed(store);
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        doAnswer(
                        call -> {
                            AguiResumeStateStore.RunClaim claim =
                                    (AguiResumeStateStore.RunClaim) call.callRealMethod();
                            if (claim.claimed()) {
                                acquired.incrementAndGet();
                            }
                            return claim;
                        })
                .when(store)
                .claimRun("T", "A");
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            released.countDown();
                            return null;
                        })
                .when(store)
                .releaseRun("T", "A");
        AguiRequestProcessor processor =
                processor(
                        store,
                        Flux.defer(
                                () -> {
                                    executed.incrementAndGet();
                                    return Flux.never();
                                }),
                        false);
        // Observe the real validation result without subscribing to or requesting error events.
        Field field = AguiRequestProcessor.class.getDeclaredField("resumeCoordinator");
        field.setAccessible(true);
        AguiResumeCoordinator coordinator = spy((AguiResumeCoordinator) field.get(processor));
        doAnswer(
                        call -> {
                            AguiResumeCoordinator.ResumeContractResult result =
                                    (AguiResumeCoordinator.ResumeContractResult)
                                            call.callRealMethod();
                            assertTrue(result.isError());
                            assertTrue(result.message().contains("unresolved interrupts"));
                            validated.countDown();
                            return result;
                        })
                .when(coordinator)
                .validate(any(RunAgentInput.class));
        field.set(processor, coordinator);
        List<AguiEvent> events = new CopyOnWriteArrayList<>();
        BaseSubscriber<AguiEvent> subscriber =
                new BaseSubscriber<>() {
                    @Override
                    protected void hookOnSubscribe(Subscription subscription) {
                        // Deliberately keep initial demand at zero.
                    }

                    @Override
                    protected void hookOnNext(AguiEvent event) {
                        events.add(event);
                    }
                };
        processor.process(request(input(false))).events().subscribe(subscriber);
        try {
            await(validated);
            assertEquals(1, acquired.get(), "the real claim must have succeeded");
            boolean cleanupObserved = released.await(1, TimeUnit.SECONDS);
            assertTrue(events.isEmpty());
            assertFalse(
                    subscriber.isDisposed(), "no cancellation or terminal delivery before check");
            assertEquals(0, executed.get());
            assertEquals(PENDING, store.getPendingInterrupts("T"));
            AguiResumeStateStore.RunClaim next = store.claimRun("T", "B");
            System.out.println(
                    "C01: acquired="
                            + acquired.get()
                            + ", validation=contract-error, consumed="
                            + events.size()
                            + ", disposed="
                            + subscriber.isDisposed()
                            + ", cleanup="
                            + cleanupObserved
                            + ", nextClaim="
                            + next);
            assertTrue(
                    next.claimed(),
                    "after confirmed claim and validation error, zero-demand A"
                            + " still owns the thread: "
                            + next.activeRunId());
            assertTrue(cleanupObserved);
        } finally {
            subscriber.cancel();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void claimFailureEmitsConfiguredErrorLifecycleWithoutReleasing(boolean legacyFinish) {
        AguiResumeStateStore store = mock(AguiResumeStateStore.class);
        RuntimeException failure = new RuntimeException("claim unavailable");
        when(store.claimRun("T", "A")).thenThrow(failure);
        List<AguiEvent> events =
                collect(processor(store, Flux.never(), legacyFinish), input(false));
        assertError(events, "claim unavailable", legacyFinish);
        verify(store, never()).releaseRun(anyString(), anyString());
        verify(store, never()).getPendingInterrupts(anyString());
    }

    @Test
    void validationAndCleanupFailurePreservePrimaryAndBoundRetries() {
        AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        RuntimeException primary = new RuntimeException("read unavailable");
        RuntimeException cleanup = new RuntimeException("release unavailable");
        doThrow(primary).when(store).getPendingInterrupts("T");
        doThrow(cleanup).when(store).releaseRun("T", "A");
        List<Throwable> dropped = new CopyOnWriteArrayList<>();
        Hooks.onErrorDropped(dropped::add);
        try {
            assertError(
                    collect(processor(store, Flux.never(), false), input(false)),
                    "read unavailable",
                    false);
            verify(store, times(3)).releaseRun("T", "A");
            assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed());
            assertEquals("A", store.claimRun("T", "B").activeRunId());
            assertTrue(dropped.isEmpty());
        } finally {
            Hooks.resetOnErrorDropped();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingWriteFailureKeepsOldPendingAndEmitsOneStarted(boolean legacyFinish) {
        AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        seed(store);
        doThrow(new RuntimeException("write unavailable"))
                .when(store)
                .replacePendingInterrupts("T", "A", Map.of());
        Flux<AguiEvent> upstream =
                Flux.<AguiEvent>just(
                        new AguiEvent.RunStarted("T", "A"), new AguiEvent.RunFinished("T", "A"));
        assertError(
                collect(processor(store, upstream, legacyFinish), input(true)),
                "write unavailable",
                legacyFinish);
        assertEquals(PENDING, store.getPendingInterrupts("T"));
        assertTrue(store.claimRun("T", "B").claimed());
    }

    @Test
    void resumedRunErrorDoesNotClearPending() {
        AguiResumeStateStore store = new InMemoryAguiResumeStateStore();
        seed(store);
        Flux<AguiEvent> upstream =
                Flux.<AguiEvent>just(
                        new AguiEvent.RunStarted("T", "A"),
                        new AguiEvent.RunError(
                                "T", "A", "agent error", "INTERNAL_ERROR", null, null),
                        new AguiEvent.RunFinished("T", "A"));
        assertEquals(3, collect(processor(store, upstream, true), input(true)).size());
        assertEquals(PENDING, store.getPendingInterrupts("T"));
        assertTrue(store.claimRun("T", "B").claimed());
    }

    @Test
    void releaseRecoversBeforeCompletionWithoutRepeatingAgentOrPendingWrite() {
        AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(
                        call -> {
                            if (attempts.incrementAndGet() < 3) {
                                throw new RuntimeException("temporary release failure");
                            }
                            return call.callRealMethod();
                        })
                .when(store)
                .releaseRun("T", "A");
        AtomicInteger executions = new AtomicInteger();
        Flux<AguiEvent> upstream =
                Flux.defer(
                        () -> {
                            executions.incrementAndGet();
                            return Flux.<AguiEvent>just(
                                    new AguiEvent.RunStarted("T", "A"),
                                    new AguiEvent.RunFinished("T", "A"));
                        });
        assertEquals(2, collect(processor(store, upstream, false), input(false)).size());
        assertEquals(3, attempts.get());
        assertEquals(1, executions.get());
        verify(store).replacePendingInterrupts("T", "A", Map.of());
        assertTrue(store.claimRun("T", "B").claimed());
    }

    @Test
    void lostReleaseReplyRetryCannotReleaseDifferentRun() {
        AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            if (attempts.incrementAndGet() == 1) {
                                assertTrue(store.claimRun("T", "B").claimed());
                                throw new RuntimeException("release reply lost");
                            }
                            return null;
                        })
                .when(store)
                .releaseRun("T", "A");
        collect(processor(store, Flux.empty(), false), input(false));
        assertEquals(2, attempts.get());
        assertEquals("B", store.claimRun("T", "C").activeRunId());
    }

    @Test
    void duplicateRejectedClaimDoesNotReleaseActiveOwnerWithSameRunId() throws Exception {
        AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            released.countDown();
                            return null;
                        })
                .when(store)
                .releaseRun("T", "A");
        AguiRequestProcessor processor =
                processor(
                        store,
                        Flux.<AguiEvent>never().doOnSubscribe(s -> started.countDown()),
                        false);
        Disposable owner = processor.process(request(input(false))).events().subscribe();
        try {
            await(started);
            List<AguiEvent> rejected = collect(processor, input(false));
            assertEquals(
                    AguiResumeCoordinator.CONTRACT_ERROR_CODE,
                    ((AguiEvent.RunError) rejected.get(1)).code());
            verify(store, never()).releaseRun("T", "A");
        } finally {
            owner.dispose();
        }
        await(released);
    }

    @Test
    void cancelDuringSuccessfulClaimWaitsForClaimAndReleasesWithoutRunningAgent() throws Exception {
        AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        CountDownLatch claiming = new CountDownLatch(1);
        CountDownLatch returnClaim = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            Object claim = call.callRealMethod();
                            claiming.countDown();
                            // Model a blocking client which completes even after
                            // interruption/cancellation.
                            boolean interrupted = false;
                            for (; ; ) {
                                try {
                                    if (!returnClaim.await(5, TimeUnit.SECONDS)) {
                                        throw new AssertionError("claim not allowed to return");
                                    }
                                    break;
                                } catch (InterruptedException ignored) {
                                    interrupted = true;
                                }
                            }
                            if (interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            return claim;
                        })
                .when(store)
                .claimRun("T", "A");
        doAnswer(
                        call -> {
                            assertFalse(Schedulers.isInNonBlockingThread());
                            call.callRealMethod();
                            released.countDown();
                            return null;
                        })
                .when(store)
                .releaseRun("T", "A");
        AtomicInteger executed = new AtomicInteger();
        Disposable subscription =
                processor(
                                store,
                                Flux.defer(
                                        () -> {
                                            executed.incrementAndGet();
                                            return Flux.never();
                                        }),
                                false)
                        .process(request(input(false)))
                        .events()
                        .subscribe();
        try {
            await(claiming);
            subscription.dispose();
            assertEquals(1, released.getCount());
        } finally {
            returnClaim.countDown();
            subscription.dispose();
        }
        await(released);
        assertEquals(0, executed.get());
        assertTrue(store.claimRun("T", "B").claimed());
    }

    @Test
    void allStoreOperationsAreOffloadedEvenWhenAgentFinishesOnNonblockingThread() {
        AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        seed(store);
        List<String> operations = new CopyOnWriteArrayList<>();
        org.mockito.stubbing.Answer<Object> checked =
                call -> {
                    assertFalse(Schedulers.isInNonBlockingThread(), call.getMethod().getName());
                    operations.add(call.getMethod().getName());
                    return call.callRealMethod();
                };
        doAnswer(checked).when(store).claimRun("T", "A");
        doAnswer(checked).when(store).getPendingInterrupts("T");
        doAnswer(checked).when(store).replacePendingInterrupts(eq("T"), eq("A"), anyMap());
        doAnswer(checked).when(store).releaseRun("T", "A");
        Flux<AguiEvent> upstream =
                Flux.<AguiEvent>just(
                                new AguiEvent.RunStarted("T", "A"),
                                new AguiEvent.RunFinished("T", "A"))
                        .publishOn(Schedulers.parallel());
        processor(store, upstream, false)
                .process(request(input(true)))
                .events()
                .subscribeOn(Schedulers.parallel())
                .collectList()
                .block(TIMEOUT);
        assertEquals(
                List.of(
                        "claimRun",
                        "getPendingInterrupts",
                        "getPendingInterrupts",
                        "replacePendingInterrupts",
                        "releaseRun"),
                operations);
    }

    @Test
    void cancellationRetriesOwnedReleaseWithoutDownstreamSubscriber() throws Exception {
        AguiResumeStateStore store = spy(new InMemoryAguiResumeStateStore());
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            assertFalse(Schedulers.isInNonBlockingThread());
                            if (attempts.incrementAndGet() == 1) {
                                throw new RuntimeException("temporary release failure");
                            }
                            call.callRealMethod();
                            released.countDown();
                            return null;
                        })
                .when(store)
                .releaseRun("T", "A");
        Disposable subscription =
                processor(
                                store,
                                Flux.<AguiEvent>never().doOnSubscribe(s -> started.countDown()),
                                false)
                        .process(request(input(false)))
                        .events()
                        .subscribe();
        try {
            await(started);
        } finally {
            subscription.dispose();
        }
        await(released);
        assertEquals(2, attempts.get());
        assertTrue(store.claimRun("T", "B").claimed());
    }

    private static void seed(AguiResumeStateStore store) {
        assertTrue(store.claimRun("T", "seed").claimed());
        assertTrue(store.replacePendingInterrupts("T", "seed", PENDING));
        store.releaseRun("T", "seed");
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for lifecycle transition");
    }

    private static void assertError(List<AguiEvent> events, String message, boolean legacyFinish) {
        assertEquals(legacyFinish ? 3 : 2, events.size());
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        AguiEvent.RunError error = assertInstanceOf(AguiEvent.RunError.class, events.get(1));
        assertEquals(message, error.message());
        assertEquals("INTERNAL_ERROR", error.code());
        if (legacyFinish) {
            assertInstanceOf(AguiEvent.RunFinished.class, events.get(2));
        }
    }

    private static RunAgentInput input(boolean resume) {
        return RunAgentInput.builder()
                .threadId("T")
                .runId("A")
                .resume(
                        resume
                                ? List.of(new AguiResume("I", AguiResume.STATUS_RESOLVED, true))
                                : List.of())
                .build();
    }

    private static AguiRuntimeContextRequest<?> request(RunAgentInput input) {
        return AguiRuntimeContextRequest.builder().input(input).build();
    }

    private static List<AguiEvent> collect(AguiRequestProcessor processor, RunAgentInput input) {
        return processor.process(request(input)).events().collectList().block(TIMEOUT);
    }

    private static AguiRequestProcessor processor(
            AguiResumeStateStore store, Flux<AguiEvent> upstream, boolean legacyFinish) {
        Agent agent = mock(Agent.class);
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
                                        return upstream;
                                    }
                                })
                .build();
    }
}
