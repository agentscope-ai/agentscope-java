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

package io.agentscope.extensions.durable.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jna.Platform;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.store.RecordStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/** Local filesystem records. A scope snapshot publishes its parts and ready work together. */
final class FileRecordStore implements RecordStore {
    private final Path root;
    private volatile boolean closed;

    public void close() {
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Local record store is closed");
    }

    // FileChannel locks overlap rather than block within one JVM.
    private static final Object[] locks = Stream.generate(Object::new).limit(64).toArray();

    FileRecordStore(Path directory) {
        try {
            root = Files.createDirectories(directory).toRealPath();
        } catch (IOException failure) {
            throw unavailable(failure);
        }
    }

    public Capabilities capabilities() {
        return new Capabilities("files", true, false);
    }

    public <T> T transaction(String scope, Function<Transaction, T> action) {
        ensureOpen();
        var path = root.resolve(DurableJson.digest(scope) + ".json");
        return locked(
                path,
                () -> {
                    var state =
                            Files.exists(path)
                                    ? read(path)
                                    : DurableJson.value(
                                            Map.of(
                                                    "scope", scope, "parts", Map.of(), "queues",
                                                    Map.of()));
                    var tx = new Tx((ObjectNode) state);
                    T result = action.apply(tx);
                    if (tx.changed) publish(path, state);
                    return result;
                });
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T run() throws IOException;
    }

    private <T> T locked(Path path, LockedOperation<T> operation) {
        synchronized (locks[Math.floorMod(path.hashCode(), locks.length)]) {
            try (var channel =
                            FileChannel.open(
                                    root.resolve(path.getFileName() + ".lock"),
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                return operation.run();
            } catch (IOException failure) {
                throw unavailable(failure);
            }
        }
    }

    private static final class Tx implements Transaction {
        final long now = System.currentTimeMillis();
        final ObjectNode parts;
        final ObjectNode queues;
        boolean changed;

        Tx(ObjectNode state) {
            parts = (ObjectNode) state.required("parts");
            queues = (ObjectNode) state.required("queues");
        }

        public long now() {
            return now;
        }

        public JsonNode get(String part) {
            var value = parts.get(part);
            return value == null ? null : value.deepCopy();
        }

        public void put(String part, JsonNode value) {
            if (value == null || value.isNull()) changed |= parts.remove(part) != null;
            else if (!value.equals(parts.get(part))) {
                parts.set(part, value.deepCopy());
                changed = true;
            }
        }

        public List<String> keys(String prefix, String after, int limit) {
            return page(
                    parts.properties().stream().map(Map.Entry::getKey).filter(k -> !k.isEmpty()),
                    prefix,
                    after,
                    limit);
        }

        public void schedule(String queue, String id, long at, JsonNode value) {
            if (at < 0) {
                if (queues.get(queue) instanceof ObjectNode items) {
                    changed |= items.remove(id) != null;
                    if (items.isEmpty()) queues.remove(queue);
                }
            } else {
                queues.withObjectProperty(queue)
                        .set(id, DurableJson.value(Map.of("at", at, "value", value)));
                changed = true;
            }
        }
    }

    private static JsonNode read(Path path) throws IOException {
        return DurableJson.parse(Files.readString(path));
    }

    private void publish(Path path, JsonNode state) throws IOException {
        var stage = path.resolveSibling(path.getFileName() + ".tmp");
        try (var out =
                FileChannel.open(
                        stage,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            var bytes =
                    ByteBuffer.wrap(
                            state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) out.write(bytes);
            out.force(true);
        }
        if (Platform.isWindows()) FileObjectStore.moveWindows(stage, path, true);
        else {
            Files.move(
                    stage,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            try (var directory = FileChannel.open(root, StandardOpenOption.READ)) {
                directory.force(true);
            }
        }
    }

    private <T> T scan(Function<Stream<JsonNode>, T> query) {
        ensureOpen();
        try (var files = Files.list(root)) {
            return query.apply(
                    files.filter(p -> p.getFileName().toString().endsWith(".json"))
                            .map(path -> locked(path, () -> read(path))));
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    private static int limit(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Limit must be 1..1000");
        return limit;
    }

    private static List<String> page(Stream<String> keys, String prefix, String after, int limit) {
        return keys.filter(k -> k.startsWith(prefix) && (after == null || k.compareTo(after) > 0))
                .sorted()
                .limit(limit(limit))
                .toList();
    }

    public List<String> keys(String prefix, String after, int limit) {
        return scan(
                records ->
                        page(
                                records.filter(r -> r.path("parts").has(""))
                                        .map(r -> r.required("scope").asText()),
                                prefix,
                                after,
                                limit));
    }

    public List<Work> ready(String queue, int limit) {
        long now = System.currentTimeMillis();
        return scan(
                records ->
                        records.flatMap(
                                        r ->
                                                r.path("queues").path(queue).properties().stream()
                                                        .map(
                                                                entry ->
                                                                        new Work(
                                                                                r.required("scope")
                                                                                        .asText(),
                                                                                entry.getKey(),
                                                                                entry.getValue()
                                                                                        .required(
                                                                                                "at")
                                                                                        .asLong(),
                                                                                entry.getValue()
                                                                                        .required(
                                                                                                "value"))))
                                .filter(w -> w.readyAt() <= now)
                                .sorted(
                                        Comparator.comparingLong(Work::readyAt)
                                                .thenComparing(Work::scope)
                                                .thenComparing(Work::workId))
                                .limit(limit(limit))
                                .toList());
    }

    private static DurableException unavailable(IOException e) {
        return new DurableException(
                DurableException.Code.UNAVAILABLE, "Local record I/O failed", e);
    }
}
