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

import io.agentscope.core.interruption.InterruptControl;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.message.Msg;
import java.util.concurrent.CancellationException;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Runtime-only control for one execution; never stored in conversation state. */
public final class RunControl {
    static final Object CONTEXT_KEY = new Object();
    private final String agentId;
    private final InterruptControl interruption = new InterruptControl();
    private final Sinks.Empty<Void> cancellation = Sinks.empty();
    private final Sinks.One<AgentRun.Status> termination = Sinks.one();
    private volatile AgentRun.Status status = AgentRun.Status.CREATED;

    public RunControl(String agentId) {
        this.agentId = agentId;
    }

    boolean belongsTo(String id) {
        return agentId.equals(id);
    }

    public AgentRun.Status status() {
        return status;
    }

    public InterruptControl interruption() {
        return interruption;
    }

    synchronized boolean queue() {
        if (status != AgentRun.Status.CREATED) {
            return false;
        }
        status = AgentRun.Status.QUEUED;
        return true;
    }

    synchronized boolean start() {
        if (status.isTerminal()) {
            return false;
        }
        status = AgentRun.Status.RUNNING;
        return true;
    }

    /** Cancel this execution immediately, including before subscription or while queued. */
    public boolean cancel() {
        synchronized (this) {
            if (status.isTerminal()) {
                return false;
            }
            status = AgentRun.Status.CANCELLED;
        }
        cancellation.tryEmitEmpty();
        termination.tryEmitValue(AgentRun.Status.CANCELLED);
        return true;
    }

    /** Running calls interrupt cooperatively; calls not yet admitted are cancelled. */
    public boolean interrupt(InterruptSource source, Msg message) {
        synchronized (this) {
            if (status.isTerminal()) {
                return false;
            }
            if (status == AgentRun.Status.RUNNING) {
                interruption.trigger(source, message);
                return true;
            }
        }
        return cancel();
    }

    void finish(AgentRun.Status outcome) {
        synchronized (this) {
            if (status.isTerminal()) {
                return;
            }
            status = outcome;
        }
        termination.tryEmitValue(outcome);
    }

    /** Cancel upstream first, then report cancellation instead of a successful empty completion. */
    <T> Flux<T> guard(Publisher<T> source) {
        return Flux.from(source)
                .takeUntilOther(cancellation.asMono())
                .concatWith(
                        Mono.defer(
                                () ->
                                        status == AgentRun.Status.CANCELLED
                                                ? Mono.error(
                                                        new CancellationException(
                                                                "Agent run cancelled"))
                                                : Mono.empty()));
    }

    public Mono<AgentRun.Status> termination() {
        return termination.asMono();
    }
}
