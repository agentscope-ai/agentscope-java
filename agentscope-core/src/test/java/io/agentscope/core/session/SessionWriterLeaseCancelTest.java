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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * A call cancelled before its session scope exists must still hand the writer lease back, in both
 * windows where the reactive scope that would release it never materialises: while the session is
 * being opened, and while the first journal write is in flight.
 */
class SessionWriterLeaseCancelTest {

    private static final Duration LEASE = Duration.ofMinutes(2);

    private static RuntimeContext context() {
        return RuntimeContext.builder().userId("u").sessionId("s").build();
    }

    /** Never emits: parks the model call inside the acquisition window. */
    private static final class HangingModel implements Model {
        @Override
        public String getModelName() {
            return "hanging";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.never();
        }
    }

    /**
     * Delegates to a real log but parks inside the first commit — the last write of {@code
     * start()} — so the test controls the window while the journal is starting. If the writer was
     * released meanwhile (a cancel landed), the commit is not resurrected; the parked write just
     * hands back the unmodified head.
     */
    private static final class GatedLog implements SessionLog {
        private final SessionLog delegate;
        private final CountDownLatch committing;
        private final CountDownLatch proceed;

        GatedLog(SessionLog delegate, CountDownLatch committing, CountDownLatch proceed) {
            this.delegate = delegate;
            this.committing = committing;
            this.proceed = proceed;
        }

        @Override
        public Head head() {
            return delegate.head();
        }

        @Override
        public Writer acquire(String owner, Duration lease) {
            return delegate.acquire(owner, lease);
        }

        @Override
        public void renew(Writer writer, Duration lease) {
            delegate.renew(writer, lease);
        }

        @Override
        public void release(Writer writer) {
            delegate.release(writer);
        }

        @Override
        public Head commit(
                Writer writer, String batchId, long expectedSeq, List<SessionEvent> events) {
            committing.countDown();
            awaitUninterruptibly(proceed);
            if (!writer.owner().equals(delegate.head().owner())) return delegate.head();
            return delegate.commit(writer, batchId, expectedSeq, events);
        }

        @Override
        public List<SessionEvent> readAfter(long after, int limit) {
            return delegate.readAfter(after, limit);
        }
    }

    @Test
    void cancellingDuringAcquisitionReleasesTheWriterLease() throws Exception {
        var store = new InMemorySessionLogStore();
        var acquired = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        SessionLogStore gated =
                (key, ctx) -> {
                    SessionLog log = store.open(key, ctx);
                    acquired.countDown();
                    awaitUninterruptibly(proceed);
                    return log;
                };

        Disposable subscription =
                build(new HangingModel(), gated)
                        .call(List.of(new UserMessage("hello")), context())
                        .subscribe(reply -> {}, error -> {}, () -> {});
        try {
            assertTrue(acquired.await(10, TimeUnit.SECONDS), "session was never opened");
            subscription.dispose();
        } finally {
            proceed.countDown();
        }

        assertSuccessorAcquires(store);
    }

    @Test
    void cancellingWhileTheJournalStartsReleasesTheWriterLease() throws Exception {
        var store = new InMemorySessionLogStore();
        var committing = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        SessionLogStore gated =
                (key, ctx) -> new GatedLog(store.open(key, ctx), committing, proceed);

        Disposable subscription =
                build(new HangingModel(), gated)
                        .call(List.of(new UserMessage("hello")), context())
                        .subscribe(reply -> {}, error -> {}, () -> {});
        try {
            assertTrue(
                    committing.await(10, TimeUnit.SECONDS),
                    "the first journal commit never started");
            long startedAt = System.nanoTime();
            subscription.dispose();
            long elapsed = System.nanoTime() - startedAt;
            assertTrue(
                    elapsed < Duration.ofMillis(500).toNanos(),
                    "dispose() blocked on the parked journal flush for " + elapsed + " ns");
        } finally {
            proceed.countDown();
        }

        assertSuccessorAcquires(store);
    }

    private static ReActAgent build(Model model, SessionLogStore store) {
        return ReActAgent.builder().name("stable").model(model).sessionLogStore(store).build();
    }

    /** The next call on the same session must acquire the writer without waiting for the lease. */
    private static void assertSuccessorAcquires(InMemorySessionLogStore store) throws Exception {
        var successor = build(new MockModel("answer"), store);
        Throwable lastFailure = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            try {
                successor
                        .call(List.of(new UserMessage("continue")), context())
                        .block(Duration.ofSeconds(5));
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                Thread.sleep(50);
            }
        }
        fail(
                "the successor call never got the session writer within 15s; the lease is held"
                        + " until it expires ("
                        + LEASE
                        + "): "
                        + lastFailure);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
