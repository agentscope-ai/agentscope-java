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
package io.agentscope.extensions.agentprotocol;

import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Per-task event bus for Agent Protocol SSE streaming. Uses a replay buffer so late subscribers /
 * reconnects can catch up from {@code fromSeq}.
 */
public final class AgentProtocolTaskEventBus implements AgentProtocolEventBus {

    private static final Logger log = LoggerFactory.getLogger(AgentProtocolTaskEventBus.class);

    private final int replayBufferSize;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    public AgentProtocolTaskEventBus() {
        this(256);
    }

    public AgentProtocolTaskEventBus(int replayBufferSize) {
        this.replayBufferSize = Math.max(16, replayBufferSize);
    }

    /** Publishes an event, assigning a monotonic {@code seq}. */
    @Override
    public RemoteAgentEvent publish(String taskId, RemoteAgentEvent event) {
        Channel ch = channels.computeIfAbsent(taskId, id -> new Channel(replayBufferSize));
        long seq = ch.seq.incrementAndGet();
        event.setSeq(seq);
        event.setTaskId(taskId);
        Sinks.EmitResult result = ch.sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.debug("Failed to emit event seq={} for task {}: {}", seq, taskId, result);
        }
        return event;
    }

    /**
     * Subscribes to events for {@code taskId}, optionally skipping those with {@code seq <=
     * fromSeq}.
     */
    @Override
    public Flux<RemoteAgentEvent> subscribe(String taskId, long fromSeq) {
        Channel ch = channels.computeIfAbsent(taskId, id -> new Channel(replayBufferSize));
        Flux<RemoteAgentEvent> flux = ch.sink.asFlux();
        if (fromSeq > 0) {
            return flux.filter(e -> e.getSeq() > fromSeq);
        }
        return flux;
    }

    /** Completes and removes the channel after a terminal event has been published. */
    @Override
    public void complete(String taskId) {
        Channel ch = channels.remove(taskId);
        if (ch != null) {
            ch.sink.tryEmitComplete();
        }
    }

    private static final class Channel {
        private final Sinks.Many<RemoteAgentEvent> sink;
        private final AtomicLong seq = new AtomicLong();

        Channel(int replayBufferSize) {
            this.sink = Sinks.many().replay().limit(replayBufferSize);
        }
    }
}
