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
package io.agentscope.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class ReActAgentCancelledCallStateWriteTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static class EchoModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "echo";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("answer").build()))
                            .build());
        }
    }

    private static final class BlockingVersionedStore extends InMemoryAgentStateStore {
        final AtomicBoolean hold = new AtomicBoolean(true);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        final AtomicInteger sameKeyLoads = new AtomicInteger();
        final boolean fail;

        BlockingVersionedStore(boolean fail) {
            this.fail = fail;
        }

        @Override
        public <T extends State> VersionedState<T> getVersioned(
                String uid, String sid, String key, Class<T> type) {
            if ("s1".equals(sid)) sameKeyLoads.incrementAndGet();
            return super.getVersioned(uid, sid, key, type);
        }

        @Override
        public long saveIfVersion(String uid, String sid, String key, State value, long version) {
            if ("s1".equals(sid) && "agent_state".equals(key) && hold.compareAndSet(true, false)) {
                entered.countDown();
                awaitUninterruptibly(release);
                try {
                    if (fail) throw new IllegalStateException("write failed");
                    return super.saveIfVersion(uid, sid, key, value, version);
                } finally {
                    finished.complete(null);
                }
            }
            return super.saveIfVersion(uid, sid, key, value, version);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationWaitsForActualWriteAndCancelledMiddleEntryKeepsPredecessor(boolean fail)
            throws Exception {
        BlockingVersionedStore store = new BlockingVersionedStore(fail);
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(new EchoModel())
                        .stateStore(store)
                        .build()) {
            RuntimeContext ctx = context("s1");
            Disposable a = agent.call(List.of(userMsg("A")), ctx).subscribe(msg -> {}, error -> {});
            CompletableFuture<Msg> b = null;
            CompletableFuture<Msg> c = null;
            try {
                assertTrue(store.entered.await(5, TimeUnit.SECONDS));
                a.dispose();
                // Subscribe synchronously: both entries are enqueued before the assertions.
                b = agent.call(List.of(userMsg("B-cancelled")), context("s1")).toFuture();
                b.cancel(true);
                c = agent.call(List.of(userMsg("C")), context("s1")).toFuture();
                Msg other = agent.call(List.of(userMsg("other")), context("s2")).block(TIMEOUT);
                assertNotNull(other, "another key must make progress while s1 is held");
                assertEquals(1, store.sameKeyLoads.get(), "neither B nor C may activate yet");
                assertFalse(c.isDone(), "C must wait behind A even though B was cancelled");
                assertFalse(store.finished.isDone(), "the blocked write really is still running");
                store.release.countDown();
                assertNotNull(
                        c.get(5, TimeUnit.SECONDS), "a thrown write must also release the gate");
                assertTrue(store.finished.isDone());
                List<String> texts =
                        agent.getAgentState("u1", "s1").getContext().stream()
                                .map(Msg::getTextContent)
                                .toList();
                assertTrue(texts.contains("C"));
                assertFalse(texts.contains("B-cancelled"));
                if (!fail) assertTrue(texts.contains("A"), "A's write must land before C loads");
                assertEquals(0, agent.getStateConflictCount());
            } finally {
                store.release.countDown();
                a.dispose();
                if (b != null) b.cancel(true);
                if (c != null) c.cancel(true);
            }
        }
    }

    @Test
    void cancelledSchedulingBeforeWriteStartsDoesNotLeaveAnInFlightFuture() throws Exception {
        try (ReActAgent agent = ReActAgent.builder().name("asst").model(new EchoModel()).build()) {
            AgentState state = AgentState.builder().build();
            ReActAgent.CallExecution scope =
                    agent
                    .new CallExecution(
                            state, new PermissionEngine(state.getPermissionContext()), "u1/s1");
            Scheduler scheduler = Schedulers.newSingle("queued-state-write");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean wrote = new AtomicBoolean();
            try {
                scheduler.schedule(
                        () -> {
                            entered.countDown();
                            awaitUninterruptibly(release);
                        });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Disposable queuedWrite =
                        Mono.fromRunnable(
                                        () -> {
                                            CompletableFuture<Void> write = scope.beginStateWrite();
                                            if (write != null) {
                                                try {
                                                    wrote.set(true);
                                                } finally {
                                                    write.complete(null);
                                                }
                                            }
                                        })
                                .subscribeOn(scheduler)
                                .subscribe();
                queuedWrite.dispose();
                scope.closeStateWrites().block(TIMEOUT);
                assertNull(
                        scope.beginStateWrite(),
                        "a scheduling/cancel race cannot start a late write");
                release.countDown();
                Mono.fromRunnable(() -> {}).subscribeOn(scheduler).block(TIMEOUT);
                assertFalse(wrote.get());
            } finally {
                release.countDown();
                scheduler.dispose();
            }
        }
    }

    @Test
    void cleanupErrorStillReleasesGate() {
        AgentBase agent =
                new AgentBase("cleanup-error") {
                    @Override
                    protected Object callSerializationKey(RuntimeContext rc) {
                        return "key";
                    }

                    @Override
                    protected Object beforeAgentExecution(
                            List<Msg> msgs,
                            RuntimeContext rc,
                            io.agentscope.core.agent.RunControl control) {
                        return new Object();
                    }

                    @Override
                    protected Mono<Void> onCallCancelled(Object scope) {
                        return Mono.error(new IllegalStateException("cleanup failed"));
                    }

                    @Override
                    protected Mono<Msg> doCall(List<Msg> msgs) {
                        return "wait".equals(msgs.get(0).getTextContent())
                                ? Mono.never()
                                : Mono.just(msgs.get(0));
                    }

                    @Override
                    protected Mono<Msg> handleInterrupt(InterruptContext ctx, Msg... msgs) {
                        return Mono.empty();
                    }
                };
        Disposable first = agent.call(List.of(userMsg("wait"))).subscribe();
        CompletableFuture<Msg> next = agent.call(List.of(userMsg("next"))).toFuture();
        assertFalse(next.isDone());
        first.dispose();
        assertEquals("next", Mono.fromFuture(next).block(TIMEOUT).getTextContent());
    }

    private static RuntimeContext context(String sid) {
        return RuntimeContext.builder().userId("u1").sessionId(sid).build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (latch.getCount() != 0) {
                try {
                    if (!latch.await(
                            Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        throw new AssertionError("blocking fixture was not released");
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
