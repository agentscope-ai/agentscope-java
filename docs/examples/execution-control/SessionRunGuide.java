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
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.bus.MessageBus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Companion to the execution-control blog; model configuration belongs to the calling app. */
public final class SessionRunGuide {
    private SessionRunGuide() {}

    public static RuntimeContext context(String ownerId, String sessionId) {
        return RuntimeContext.builder().userId(ownerId).sessionId(sessionId).build();
    }

    /** Register before subscribing. Never trust a caller-supplied ownerId without authentication. */
    public static final class Runs {
        private record Entry(String ownerId, String sessionId, AgentRun<?> run) {}
        private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

        public <T> AgentRun<T> register(String ownerId, String sessionId, AgentRun<T> run) {
            Entry entry = new Entry(ownerId, sessionId, run);
            if (entries.putIfAbsent(run.runId(), entry) != null) {
                throw new IllegalStateException("Run is already registered");
            }
            run.termination().subscribe(status -> entries.remove(run.runId(), entry));
            return run;
        }

        public Optional<AgentRun<?>> find(String ownerId, String sessionId, String runId) {
            Entry entry = entries.get(runId);
            return entry != null && entry.ownerId().equals(ownerId)
                    && entry.sessionId().equals(sessionId)
                    ? Optional.of(entry.run()) : Optional.empty();
        }

        public boolean cancel(String ownerId, String sessionId, String runId) {
            return find(ownerId, sessionId, runId).map(AgentRun::cancel).orElse(false);
        }

        public boolean interrupt(String ownerId, String sessionId, String runId) {
            return find(ownerId, sessionId, runId).map(AgentRun::interrupt).orElse(false);
        }
    }

    /** The single subscription owns the timeout and cancels the underlying run on expiry. */
    public static Mono<Msg> replyWithDeadline(AgentRun<Msg> run, Duration deadline) {
        return run.stream().single().timeout(deadline);
    }

    /**
     * Subscribe once. Ask the old run to stop, then send an explicit new user message.
     * On grace-period expiry, cancel the old run. Existing queued runs are not removed.
     */
    public static Mono<Msg> interruptThenFollowUp(
            ReActAgent agent, Runs runs, AgentRun<?> current,
            String ownerId, String sessionId, String correction, Duration grace) {
        return Mono.defer(() -> {
            current.interrupt();
            return current.termination()
                    .timeout(grace, Mono.defer(() -> {
                        current.cancel();
                        return current.termination();
                    }))
                    .then(Mono.defer(() -> {
                        AgentRun<Msg> next = runs.register(ownerId, sessionId,
                                agent.prepareCall(List.of(new UserMessage(correction)),
                                        context(ownerId, sessionId)));
                        return next.stream().single();
                    }));
        });
    }

    /** Sends a session-scoped hint; enqueue success is not proof of model consumption. */
    public static Mono<Void> supplement(MessageBus bus, String sessionId, String text) {
        return bus.inboxPush(sessionId, Map.of(
                "id", UUID.randomUUID().toString(),
                "source", "user-supplement",
                "hint", text));
    }

    /** A bounded, in-memory fan-out example. Use a durable event log for reliable reconnection. */
    public record LiveRun(AgentRun<AgentEvent> run, Flux<AgentEvent> events,
                          Disposable executionSubscription) {}

    public static LiveRun startDetached(AgentRun<AgentEvent> run) {
        Sinks.Many<AgentEvent> output = Sinks.many().replay().limit(256);
        Disposable execution = run.stream().subscribe(
                event -> output.tryEmitNext(event),
                error -> output.tryEmitError(error),
                () -> output.tryEmitComplete());
        return new LiveRun(run, output.asFlux(), execution);
    }
}
