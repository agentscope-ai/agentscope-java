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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** Local receipt lifecycle. Only a confirmed ACK opens preparation; execution owns its credit. */
public final class ManagedDeliveryHandoff implements AutoCloseable {
    private final CompletableFuture<Void> acknowledged = new CompletableFuture<>();
    private final Runnable executionRelease;
    private final Runnable handoffRelease;
    private boolean transferred;
    private boolean executionReleased;
    private boolean closed;

    public ManagedDeliveryHandoff() {
        this(null, () -> {});
    }

    ManagedDeliveryHandoff(Runnable executionRelease, Runnable handoffRelease) {
        this.executionRelease = executionRelease;
        this.handoffRelease = handoffRelease;
    }

    /** Only for direct invocations without a broker; transports must use an unconfirmed gate. */
    static ManagedDeliveryHandoff direct() {
        var gate = new ManagedDeliveryHandoff();
        gate.confirm();
        return gate;
    }

    public synchronized boolean reserved() {
        return executionRelease != null && !transferred && !executionReleased;
    }

    public synchronized Runnable takeExecution() {
        if (closed || transferred || executionRelease == null)
            throw new IllegalStateException("Execution reservation is unavailable");
        transferred = true;
        return this::releaseExecution;
    }

    private synchronized void releaseExecution() {
        if (!executionReleased && executionRelease != null) {
            executionReleased = true;
            executionRelease.run();
        }
    }

    public void await(Duration timeout) throws Exception {
        acknowledged.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void whenAcknowledged(BiConsumer<Void, Throwable> action) {
        acknowledged.whenComplete(action);
    }

    public void afterAcknowledged(Runnable action) {
        acknowledged.thenRun(action);
    }

    public synchronized void confirm() {
        if (closed) return;
        acknowledged.complete(null);
        close();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        acknowledged.completeExceptionally(
                new IllegalStateException("Receipt closed without confirmed ACK"));
        handoffRelease.run();
        if (!transferred) releaseExecution();
    }
}
