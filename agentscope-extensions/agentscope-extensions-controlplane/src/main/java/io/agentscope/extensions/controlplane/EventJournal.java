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

/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.extensions.controlplane;

import io.agentscope.controlplane.proto.SessionEventMsg;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Crash-safe local outbox for Level-2 events. Callers serialize access. */
final class EventJournal {

    private static final int MAX_RECORD_BYTES = 16 * 1024 * 1024;

    private final Path path;
    private final Path sequencePath;
    private final List<SessionEventMsg> pending = new ArrayList<>();
    private final Map<String, Integer> latestSequences = new LinkedHashMap<>();
    private final Map<String, Long> sourceWatermarks = new LinkedHashMap<>();

    long sourceWatermark(String source) {
        return sourceWatermarks.getOrDefault(source, 0L);
    }

    private void trackSource(SessionEventMsg event) {
        if (event.getFrameworkMeta().isEmpty()) return;
        try {
            var metadata =
                    io.agentscope.core.util.JsonUtils.getJsonCodec()
                            .fromJson(event.getFrameworkMeta().toStringUtf8(), Map.class);
            Object source = metadata.get("native_source");
            Object seq = metadata.get("native_seq");
            if (source instanceof String key && seq instanceof Number number)
                sourceWatermarks.merge(key, number.longValue(), Math::max);
        } catch (RuntimeException ignored) {
            /* Legacy framework metadata is not a native envelope. */
        }
    }

    EventJournal(ControlPlaneConfig config) throws IOException {
        Path root =
                config.eventJournalDir().isBlank()
                        ? Path.of(
                                System.getProperty("user.home"),
                                ".agentscope",
                                "controlplane",
                                "event-journal")
                        : Path.of(config.eventJournalDir());
        Files.createDirectories(root);
        String identity =
                String.join(
                        "\u0000",
                        config.tenant(),
                        config.namespace(),
                        config.agentKey(),
                        config.instanceKey());
        this.path = root.resolve(sha256(identity) + ".events");
        this.sequencePath = root.resolve(sha256(identity) + ".sequences");
        load();
    }

    Map<String, Integer> latestSequences() {
        return Map.copyOf(latestSequences);
    }

    void append(SessionEventMsg event) throws IOException {
        byte[] payload = event.toByteArray();
        ByteBuffer record = ByteBuffer.allocate(Integer.BYTES + payload.length);
        record.putInt(payload.length).put(payload).flip();
        try (FileChannel channel =
                FileChannel.open(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND)) {
            while (record.hasRemaining()) {
                channel.write(record);
            }
            channel.force(true);
        }
        pending.add(event);
        trackSource(event);
        latestSequences.merge(event.getSessionId(), event.getSeq(), Math::max);
    }

    List<SessionEventMsg> first(int limit) {
        return List.copyOf(pending.subList(0, Math.min(limit, pending.size())));
    }

    boolean isEmpty() {
        return pending.isEmpty();
    }

    int size() {
        return pending.size();
    }

    void acknowledge(Map<String, Integer> committed) throws IOException {
        if (committed.isEmpty()) {
            return;
        }
        persistSequences();
        pending.removeIf(
                event ->
                        event.getSeq()
                                <= committed.getOrDefault(event.getSessionId(), Integer.MIN_VALUE));
        rewrite();
    }

    private void load() throws IOException {
        if (Files.exists(sequencePath)) {
            Properties properties = new Properties();
            try (var input = Files.newInputStream(sequencePath)) {
                properties.load(input);
            }
            for (String sessionId : properties.stringPropertyNames()) {
                if (sessionId.startsWith("__native__")) {
                    sourceWatermarks.put(
                            sessionId.substring(10),
                            Long.parseLong(properties.getProperty(sessionId)));
                    continue;
                }
                try {
                    latestSequences.put(
                            sessionId, Integer.parseInt(properties.getProperty(sessionId)));
                } catch (NumberFormatException ignored) {
                    // Ignore only the corrupt checkpoint entry; pending events remain recoverable.
                }
            }
        }
        if (!Files.exists(path)) {
            return;
        }
        try (DataInputStream input =
                new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            while (true) {
                int length;
                try {
                    length = input.readInt();
                } catch (EOFException end) {
                    break;
                }
                if (length <= 0 || length > MAX_RECORD_BYTES) {
                    break;
                }
                byte[] payload = input.readNBytes(length);
                if (payload.length != length) {
                    break; // Ignore a torn tail; acknowledged records are never rewritten in place.
                }
                SessionEventMsg event = SessionEventMsg.parseFrom(payload);
                pending.add(event);
                trackSource(event);
                latestSequences.merge(event.getSessionId(), event.getSeq(), Math::max);
            }
        }
        // Normalize a torn tail before the next append.
        rewrite();
    }

    private void persistSequences() throws IOException {
        Properties properties = new Properties();
        latestSequences.forEach(
                (sessionId, seq) -> properties.setProperty(sessionId, String.valueOf(seq)));
        sourceWatermarks.forEach(
                (source, seq) -> properties.setProperty("__native__" + source, Long.toString(seq)));
        Path temp = sequencePath.resolveSibling(sequencePath.getFileName() + ".tmp");
        try (var output =
                Files.newOutputStream(
                        temp,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            properties.store(output, "controlplane session event sequence watermarks");
        }
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(
                    temp,
                    sequencePath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, sequencePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void rewrite() throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (FileChannel channel =
                FileChannel.open(
                        temp,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            for (SessionEventMsg event : pending) {
                byte[] payload = event.toByteArray();
                ByteBuffer record = ByteBuffer.allocate(Integer.BYTES + payload.length);
                record.putInt(payload.length).put(payload).flip();
                while (record.hasRemaining()) {
                    channel.write(record);
                }
            }
            channel.force(true);
        }
        try {
            Files.move(
                    temp,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
