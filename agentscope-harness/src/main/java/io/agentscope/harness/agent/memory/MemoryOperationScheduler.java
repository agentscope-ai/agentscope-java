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
package io.agentscope.harness.agent.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Runs post-call memory operations in the background while preserving submission order within
 * each memory isolation key. Different users or sessions can progress concurrently.
 *
 * <p>The total number of running and queued operations is bounded. When the limit is reached,
 * submitters wait for capacity, propagating backpressure to agent calls instead of retaining an
 * unbounded chain of tasks while a memory LLM is slow.
 *
 * <p>{@link #close()} stops accepting new work and waits for all accepted operations. This keeps
 * asynchronous memory writes durable when their owning {@code HarnessAgent} is closed.
 */
public final class MemoryOperationScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryOperationScheduler.class);

    private final ConcurrentHashMap<String, CompletableFuture<Void>> tails =
            new ConcurrentHashMap<>();
    private final Semaphore capacity;
    private final Object lifecycleLock = new Object();
    private boolean accepting = true;

    public MemoryOperationScheduler() {
        this(MemoryConfig.DEFAULT_MAX_PENDING_MEMORY_OPERATIONS);
    }

    public MemoryOperationScheduler(int maxPendingOperations) {
        if (maxPendingOperations <= 0) {
            throw new IllegalArgumentException(
                    "maxPendingOperations must be positive, got " + maxPendingOperations);
        }
        this.capacity = new Semaphore(maxPendingOperations, true);
    }

    /**
     * Queues an operation after all previously submitted work for {@code isolationKey}.
     * Submission waits when the configured operation limit is already in use.
     *
     * @return {@code true} when accepted, or {@code false} after this scheduler has closed
     */
    public boolean submit(String isolationKey, Supplier<Mono<Void>> operation) {
        if (operation == null) {
            throw new IllegalArgumentException("operation must not be null");
        }
        String key = isolationKey != null ? isolationKey : "";
        AtomicReference<CompletableFuture<Void>> submitted = new AtomicReference<>();
        capacity.acquireUninterruptibly();
        synchronized (lifecycleLock) {
            if (!accepting) {
                capacity.release();
                return false;
            }
            try {
                tails.compute(
                        key,
                        (ignored, previous) -> {
                            CompletableFuture<Void> predecessor =
                                    previous == null
                                            ? CompletableFuture.completedFuture(null)
                                            : previous.handle((result, error) -> null);
                            CompletableFuture<Void> next =
                                    predecessor.thenComposeAsync(
                                            unused -> invoke(operation),
                                            command ->
                                                    Schedulers.boundedElastic().schedule(command));
                            submitted.set(next);
                            return next;
                        });
            } catch (RuntimeException | Error error) {
                capacity.release();
                throw error;
            }
        }

        CompletableFuture<Void> future = submitted.get();
        future.whenComplete(
                (unused, error) -> {
                    tails.remove(key, future);
                    capacity.release();
                    if (error != null) {
                        log.warn(
                                "Asynchronous memory operation failed for key {}: {}",
                                key,
                                unwrap(error).getMessage());
                    }
                });
        return true;
    }

    private CompletableFuture<Void> invoke(Supplier<Mono<Void>> operation) {
        try {
            Mono<Void> task = operation.get();
            return task != null
                    ? task.subscribeOn(Schedulers.boundedElastic()).toFuture()
                    : CompletableFuture.completedFuture(null);
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
    }

    @Override
    public void close() {
        List<CompletableFuture<Void>> pending;
        synchronized (lifecycleLock) {
            if (!accepting && tails.isEmpty()) {
                return;
            }
            accepting = false;
            pending = new ArrayList<>(tails.values());
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                .handle((unused, error) -> null)
                .join();
    }
}
