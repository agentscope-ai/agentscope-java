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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Per-task event bus for Agent Protocol SSE streaming. Uses a replay buffer so late subscribers /
 * reconnects can catch up from {@code fromSeq}.
 *
 * <p>A channel is created on first use and is <em>retained</em> once the task completes, so a
 * subscriber that arrives after completion still drains the replay buffer and is then completed
 * instead of being left open until the client-side SSE timeout. Retention is bounded by {@link
 * #maxChannels}: completed channels are evicted before live ones, oldest first. Ids of completed
 * tasks are remembered past their channel so that a reconnect to an evicted-but-finished task
 * terminates immediately rather than opening a channel that will never complete.
 */
public final class AgentProtocolTaskEventBus implements AgentProtocolEventBus {

    private static final Logger log = LoggerFactory.getLogger(AgentProtocolTaskEventBus.class);

    /** Default upper bound on the number of channels a single bus retains. */
    public static final int DEFAULT_MAX_CHANNELS = 1024;

    private final Object lock = new Object();
    private final int replayBufferSize;
    private final int maxChannels;
    private final int maxCompletedIds;

    /** Insertion-ordered so eviction can prefer the oldest channel. Guarded by {@link #lock}. */
    private final Map<String, Channel> channels = new LinkedHashMap<>();

    /** Ids of completed tasks, oldest first. Guarded by {@link #lock}. */
    private final Set<String> completedIds = new LinkedHashSet<>();

    public AgentProtocolTaskEventBus() {
        this(256);
    }

    public AgentProtocolTaskEventBus(int replayBufferSize) {
        this(replayBufferSize, DEFAULT_MAX_CHANNELS);
    }

    public AgentProtocolTaskEventBus(int replayBufferSize, int maxChannels) {
        this.replayBufferSize = Math.max(16, replayBufferSize);
        this.maxChannels = Math.max(1, maxChannels);
        this.maxCompletedIds = this.maxChannels * 4;
    }

    /** Publishes an event, assigning a monotonic {@code seq}. */
    @Override
    public RemoteAgentEvent publish(String taskId, RemoteAgentEvent event) {
        Channel ch = channel(taskId);
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
     * fromSeq}. Returns an already-completed stream when {@code taskId} has finished and its
     * channel is no longer retained.
     */
    @Override
    public Flux<RemoteAgentEvent> subscribe(String taskId, long fromSeq) {
        Channel ch;
        boolean finishedWithoutChannel;
        synchronized (lock) {
            ch = channels.get(taskId);
            finishedWithoutChannel = ch == null && completedIds.contains(taskId);
        }
        if (ch == null) {
            if (finishedWithoutChannel) {
                return Flux.empty();
            }
            ch = channel(taskId);
        }
        Flux<RemoteAgentEvent> flux = ch.sink.asFlux();
        if (fromSeq > 0) {
            return flux.filter(e -> e.getSeq() > fromSeq);
        }
        return flux;
    }

    /**
     * Completes the channel for {@code taskId}. The channel is retained so that a later subscriber
     * replays the buffered events and is then completed; it is released once the bus evicts it.
     */
    @Override
    public void complete(String taskId) {
        Channel ch;
        synchronized (lock) {
            ch = channels.get(taskId);
            if (ch != null) {
                ch.completed = true;
            }
            completedIds.add(taskId);
            while (completedIds.size() > maxCompletedIds) {
                Iterator<String> oldest = completedIds.iterator();
                oldest.next();
                oldest.remove();
            }
        }
        if (ch != null) {
            ch.sink.tryEmitComplete();
        }
    }

    /** Visible for tests - the number of channels currently retained. */
    int channelCount() {
        synchronized (lock) {
            return channels.size();
        }
    }

    private Channel channel(String taskId) {
        synchronized (lock) {
            Channel existing = channels.get(taskId);
            if (existing != null) {
                return existing;
            }
            while (channels.size() >= maxChannels && evictOne()) {
                // Keep evicting until there is room for the new channel.
            }
            Channel created = new Channel(replayBufferSize);
            channels.put(taskId, created);
            return created;
        }
    }

    /** Caller must hold {@link #lock}. Completed channels are evicted first, oldest first. */
    private boolean evictOne() {
        Iterator<Map.Entry<String, Channel>> it = channels.entrySet().iterator();
        String eldestKey = null;
        while (it.hasNext()) {
            Map.Entry<String, Channel> entry = it.next();
            if (eldestKey == null) {
                eldestKey = entry.getKey();
            }
            if (entry.getValue().completed) {
                it.remove();
                return true;
            }
        }
        if (eldestKey != null) {
            channels.remove(eldestKey);
            return true;
        }
        return false;
    }

    private static final class Channel {
        private final Sinks.Many<RemoteAgentEvent> sink;
        private final AtomicLong seq = new AtomicLong();
        private volatile boolean completed;

        Channel(int replayBufferSize) {
            this.sink = Sinks.many().replay().limit(replayBufferSize);
        }
    }
}
