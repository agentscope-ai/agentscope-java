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
package io.agentscope.core.bus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Single-process, in-memory implementation of {@link MessageBus}.
 *
 * <p>Suitable for standalone deployments and testing. All state lives in memory and is lost on JVM
 * shutdown. For distributed deployments, use a Redis-backed implementation instead.
 *
 * <p>Thread-safe: all operations use concurrent data structures.
 */
public class InMemoryMessageBus implements MessageBus {

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<BusEntry>> queues =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Sinks.Many<Map<String, Object>>> channels =
            new ConcurrentHashMap<>();

    @Override
    public Mono<String> queuePush(String key, Map<String, Object> payload) {
        return Mono.fromCallable(
                () -> {
                    String entryId = UUID.randomUUID().toString().replace("-", "");
                    queues.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>())
                            .add(new BusEntry(entryId, payload));
                    return entryId;
                });
    }

    @Override
    public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
        return Mono.fromCallable(
                () -> {
                    ConcurrentLinkedQueue<BusEntry> queue = queues.get(key);
                    if (queue == null) {
                        return List.<BusEntry>of();
                    }
                    List<BusEntry> drained = new ArrayList<>(Math.min(maxCount, 64));
                    for (int i = 0; i < maxCount; i++) {
                        BusEntry entry = queue.poll();
                        if (entry == null) {
                            break;
                        }
                        drained.add(entry);
                    }
                    return drained;
                });
    }

    @Override
    public Mono<Void> queueDelete(String key) {
        return Mono.fromRunnable(() -> queues.remove(key));
    }

    @Override
    public Mono<Void> publish(String key, Map<String, Object> payload) {
        return Mono.fromRunnable(
                () -> {
                    Sinks.Many<Map<String, Object>> sink = channels.get(key);
                    if (sink != null) {
                        sink.tryEmitNext(payload);
                    }
                });
    }

    @Override
    public Flux<Map<String, Object>> subscribe(String key) {
        Sinks.Many<Map<String, Object>> sink =
                channels.computeIfAbsent(key, k -> Sinks.many().multicast().onBackpressureBuffer());
        return sink.asFlux();
    }
}
