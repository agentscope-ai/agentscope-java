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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.session.SessionEvent;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/** Optional diagnostics. Failure, loss, duplication and shutdown never participate in a commit. */
public final class ManagedSessionLogArchive implements AutoCloseable {
    private static final Set<String> DIAGNOSTICS = Set.of("model/chunk", "tool/chunk", "step/end");
    private static final int MAX_RECORD = 256 * 1024;
    private static final int MAX_BUFFER = 8 * 1024 * 1024;
    private final ArrayBlockingQueue<Record> pending = new ArrayBlockingQueue<>(4096);

    private record Record(String scope, byte[] bytes) {}

    private final RuntimeObjectStore objects;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final boolean chunks;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong buffered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong uploads = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final Thread worker;

    public ManagedSessionLogArchive(
            RuntimeObjectStore objects, ObjectMapper mapper, boolean enabled, boolean chunks) {
        this.objects = objects;
        this.mapper = mapper;
        this.enabled = enabled;
        this.chunks = chunks;
        worker = new Thread(this::upload, "managed-session-log-archive");
        worker.setDaemon(true);
        if (enabled) worker.start();
    }

    static boolean diagnostic(SessionEvent event) {
        return DIAGNOSTICS.contains(event.type());
    }

    public void offer(ManagedScope scope, SessionEvent event) {
        if (!enabled || closed.get() || (!chunks && event.type().endsWith("/chunk"))) return;
        try {
            var data = new LinkedHashMap<String, Object>(event.data());
            if (data.get("chunk") instanceof Map<?, ?> chunk) {
                var safe = new LinkedHashMap<String, Object>();
                for (String key : List.of("id", "usage", "finishReason"))
                    if (chunk.get(key) != null) safe.put(key, chunk.get(key));
                if (chunk.get("content") instanceof List<?> blocks)
                    safe.put(
                            "content",
                            blocks.stream()
                                    .filter(
                                            block ->
                                                    !(block instanceof Map<?, ?> map)
                                                            || !"thinking".equals(map.get("type")))
                                    .toList());
                data.put("chunk", safe);
            }
            byte[] bytes =
                    (mapper.writeValueAsString(
                                            Map.of(
                                                    "type",
                                                    event.type(),
                                                    "time",
                                                    event.occurredAt(),
                                                    "run",
                                                    event.executionRunId() == null
                                                            ? ""
                                                            : event.executionRunId(),
                                                    "turn",
                                                    event.turnId() == null ? "" : event.turnId(),
                                                    "data",
                                                    data))
                                    + "\n")
                            .getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_RECORD) {
                dropped.incrementAndGet();
                return;
            }
            if (buffered.addAndGet(bytes.length) > MAX_BUFFER) {
                buffered.addAndGet(-bytes.length);
                dropped.incrementAndGet();
                return;
            }
            if (!pending.offer(new Record(scope.messageGroup(), bytes))) {
                buffered.addAndGet(-bytes.length);
                dropped.incrementAndGet();
            }
        } catch (Exception ignored) {
            dropped.incrementAndGet();
        }
    }

    private void upload() {
        while (!closed.get()) {
            try {
                var first = pending.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;
                var records = new ArrayList<Record>();
                records.add(first);
                // Short bounded batch window; interruption or upload failure simply loses logs.
                pending.drainTo(records, 255);
                var groups = new LinkedHashMap<String, ByteArrayOutputStream>();
                for (var record : records) {
                    buffered.updateAndGet(value -> Math.max(0, value - record.bytes().length));
                    groups.computeIfAbsent(record.scope(), key -> new ByteArrayOutputStream())
                            .writeBytes(record.bytes());
                }
                for (var entry : groups.entrySet()) {
                    if (closed.get()) return;
                    try {
                        var bytes = new ByteArrayOutputStream();
                        try (var zip = new GZIPOutputStream(bytes)) {
                            entry.getValue().writeTo(zip);
                        }
                        objects.put(
                                entry.getKey(),
                                "session-log",
                                System.currentTimeMillis() + "-" + UUID.randomUUID() + ".jsonl.gz",
                                bytes.toByteArray());
                        uploads.incrementAndGet();
                    } catch (Exception unavailable) {
                        errors.incrementAndGet();
                    }
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
                errors.incrementAndGet();
            }
        }
    }

    public long dropped() {
        return dropped.get();
    }

    public long uploads() {
        return uploads.get();
    }

    public long errors() {
        return errors.get();
    }

    public long bufferedBytes() {
        return buffered.get();
    }

    @Override
    public void close() {
        closed.set(true);
        worker.interrupt();
        pending.clear();
        buffered.set(0);
    }
}
