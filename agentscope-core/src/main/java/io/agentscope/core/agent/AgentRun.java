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
package io.agentscope.core.agent;

import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.message.Msg;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A single-use, lazy execution handle. Register it before subscribing to {@link #stream()}.
 * Each handle owns its cancellation and status; it never registers RuntimeContext on an agent.
 * A second subscription is rejected, so one runId can never identify multiple executions.
 * Cancelling a subscription also cancels this handle. Create another handle to retry.
 *
 * @param <T> the execution's output type (typically Msg or AgentEvent)
 */
public final class AgentRun<T> {
    /** Execution state. QUEUED includes setup before admission to the core lifecycle. */
    public enum Status {
        CREATED,
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED;

        /** Whether no further execution can start through this handle. */
        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    private final String runId = UUID.randomUUID().toString();
    private final RunControl control;
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final AtomicReference<Supplier<? extends Publisher<T>>> pendingSource;
    private final Flux<T> stream = Flux.defer(this::subscribeOnce);

    private AgentRun(String agentId, Supplier<? extends Publisher<T>> source) {
        control = new RunControl(Objects.requireNonNull(agentId, "agentId"));
        pendingSource = new AtomicReference<>(Objects.requireNonNull(source, "source"));
        control.termination().subscribe(status -> pendingSource.set(null));
    }

    private Flux<T> subscribeOnce() {
        if (!subscribed.compareAndSet(false, true)) {
            return Flux.error(new IllegalStateException("AgentRun permits one subscription"));
        }
        if (!control.queue()) {
            return Flux.error(new CancellationException("Agent run cancelled before subscription"));
        }
        Flux<T> invocation =
                Flux.defer(this::invokeSource)
                        .contextWrite(ctx -> ctx.put(RunControl.CONTEXT_KEY, control));
        return control.guard(invocation)
                .doOnComplete(
                        () -> {
                            control.finish(Status.COMPLETED);
                            if (control.status() == Status.CANCELLED) {
                                throw new CancellationException("Agent run cancelled");
                            }
                        })
                .onErrorMap(
                        error -> {
                            control.finish(Status.FAILED);
                            return control.status() == Status.CANCELLED
                                    ? new CancellationException("Agent run cancelled")
                                    : error;
                        })
                .doOnCancel(control::cancel);
    }

    private Publisher<T> invokeSource() {
        Supplier<? extends Publisher<T>> invocation = pendingSource.getAndSet(null);
        if (invocation == null || control.status().isTerminal()) {
            return Flux.error(new CancellationException("Agent run cancelled"));
        }
        return invocation.get();
    }

    /** Wrap an agent invocation without starting it. Prefer the agent's prepareRun/prepareCall APIs. */
    public static <T> AgentRun<T> create(String agentId, Supplier<? extends Publisher<T>> source) {
        return new AgentRun<>(agentId, source);
    }

    /** Stable opaque identifier for this single execution. */
    public String runId() {
        return runId;
    }

    /** Current execution state; a cooperative interrupt may still be RUNNING until observed. */
    public Status status() {
        return control.status();
    }

    /** Subscribe once to start execution. Cancellation is reported as CancellationException. */
    public Flux<T> stream() {
        return stream;
    }

    /** A replayable terminal-status notification; observing it does not start execution. */
    public Mono<Status> termination() {
        return control.termination();
    }

    /** Immediately cancel this run's reactive subscription, including a queued run. */
    public boolean cancel() {
        return control.cancel();
    }

    /** Cooperatively interrupt a running run, or cancel it if it has not started. */
    public boolean interrupt(Msg message) {
        return control.interrupt(InterruptSource.USER, message);
    }

    /** Cooperatively interrupt without an associated user message. */
    public boolean interrupt() {
        return interrupt(null);
    }
}
