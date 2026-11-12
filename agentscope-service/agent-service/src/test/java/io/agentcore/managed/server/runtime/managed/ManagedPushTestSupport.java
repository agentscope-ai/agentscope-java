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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Test-only control of actual Push callback lifetime; no receive/ACK transport emulation. */
final class ManagedPushTestSupport {
    static SessionCommitReceipt commitToolResult(
            ManagedJournalStore journal, ManagedToolResult result) {
        journal.stageToolResult(result);
        return journal.commitToolResult(result);
    }

    static final class Probe implements AutoCloseable {
        private final BlockingQueue<Invocation> deliveries = new LinkedBlockingQueue<>();
        private final ManagedMessageBus.Subscription subscription;

        Probe(ManagedMessageBus bus, Channel channel, int callbacks) {
            subscription =
                    bus.subscribe(
                            channel,
                            callbacks,
                            delivery -> {
                                var done = new CompletableFuture<Void>();
                                deliveries.add(new Invocation(delivery, done));
                                try {
                                    done.get(10, TimeUnit.SECONDS);
                                } catch (ExecutionException failed) {
                                    if (failed.getCause()
                                            instanceof ManagedDeliverySuspended waiting)
                                        throw waiting;
                                    throw failed;
                                }
                            },
                            (delivery, failure) -> {});
        }

        Invocation next() {
            try {
                var next = poll(Duration.ofSeconds(3));
                assertNotNull(next, "Expected Push callback");
                return next;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Expected callback interrupted", interrupted);
            }
        }

        Invocation poll(Duration timeout) throws InterruptedException {
            return deliveries.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            subscription.close();
        }
    }

    record Invocation(ManagedPushDelivery delivery, CompletableFuture<Void> done) {
        void succeed() {
            done.complete(null);
        }

        void suspend(Duration delay) {
            done.completeExceptionally(new ManagedDeliverySuspended(delay));
        }

        void fail() {
            done.completeExceptionally(new IllegalStateException("Explicit test failure"));
        }
    }
}
