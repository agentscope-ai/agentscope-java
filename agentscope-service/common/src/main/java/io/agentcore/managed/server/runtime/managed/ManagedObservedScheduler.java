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

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Queue latency starts at the requested due time, excluding intentional model/timer delays. */
final class ManagedObservedScheduler extends AbstractExecutorService
        implements ScheduledExecutorService {
    private final ScheduledExecutorService delegate;

    ManagedObservedScheduler(ScheduledExecutorService delegate) {
        this.delegate = delegate;
    }

    private <T> Callable<T> observe(Callable<T> work, long delay, TimeUnit unit) {
        var trace = ManagedExecutionTrace.current();
        long due = System.nanoTime() + Math.max(0, unit.toNanos(delay));
        if (trace == null) return work;
        return () -> {
            long now = System.nanoTime();
            String thread = Thread.currentThread().getName();
            String lane =
                    (thread.startsWith("agentscope-model-")
                                    || thread.startsWith("agentscope-session-"))
                            ? thread.replaceFirst("-[0-9]+$", "")
                            : thread.startsWith("boundedElastic") ? "boundedElastic" : "other";
            trace.record("scheduler_queue:" + lane, Math.min(now, due), now);
            return work.call();
        };
    }

    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return delegate.schedule(
                observe(
                        () -> {
                            command.run();
                            return null;
                        },
                        delay,
                        unit),
                delay,
                unit);
    }

    public <T> ScheduledFuture<T> schedule(Callable<T> command, long delay, TimeUnit unit) {
        return delegate.schedule(observe(command, delay, unit), delay, unit);
    }

    // Periodic maintenance is not a Turn's critical-path queue; preserve scheduling unchanged.
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable r, long d, long p, TimeUnit u) {
        return delegate.scheduleAtFixedRate(r, d, p, u);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable r, long d, long p, TimeUnit u) {
        return delegate.scheduleWithFixedDelay(r, d, p, u);
    }

    public void execute(Runnable r) {
        var observed =
                observe(
                        () -> {
                            r.run();
                            return null;
                        },
                        0,
                        TimeUnit.NANOSECONDS);
        // Reactor adapts non-scheduled executors too; zero-delay schedule is not execute.
        delegate.execute(
                () -> {
                    try {
                        observed.call();
                    } catch (RuntimeException | Error error) {
                        throw error;
                    } catch (Exception impossible) {
                        throw new IllegalStateException(impossible);
                    }
                });
    }

    public void shutdown() {
        delegate.shutdown();
    }

    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    public boolean awaitTermination(long t, TimeUnit u) throws InterruptedException {
        return delegate.awaitTermination(t, u);
    }
}
