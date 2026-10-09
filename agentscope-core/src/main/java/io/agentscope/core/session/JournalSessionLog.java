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
package io.agentscope.core.session;

import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Immutable commit chain made visible by one fenced CAS of its head. No listing-based recovery. */
public final class JournalSessionLog implements SessionLog {
    private final AtomicSessionStorage storage;
    private final String prefix;
    private final Clock clock;

    public record Commit(
            String batchId,
            String parent,
            long firstSeq,
            long lastSeq,
            String hash,
            List<SessionEvent> events,
            Map<String, String> payloadBlobs) {}

    public JournalSessionLog(AtomicSessionStorage storage, String prefix) {
        this(storage, prefix, Clock.systemUTC());
    }

    public JournalSessionLog(AtomicSessionStorage storage, String prefix, Clock clock) {
        this.storage = Objects.requireNonNull(storage);
        this.prefix = prefix;
        this.clock = clock;
    }

    private String path(String suffix) {
        return prefix + "/" + suffix;
    }

    @Override
    public SessionLog inbox() {
        return new JournalSessionLog(storage, path("inbox"), clock);
    }

    private static byte[] bytes(Object value) {
        return JsonUtils.getJsonCodec().toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    private static <T> T parse(byte[] bytes, Class<T> type) {
        return JsonUtils.getJsonCodec().fromJson(new String(bytes, StandardCharsets.UTF_8), type);
    }

    private Head head(AtomicSessionStorage.Value value) {
        return value == null ? new Head(0, 0, null, 0, null) : parse(value.bytes(), Head.class);
    }

    @Override
    public Head head() {
        var header = storage.read(path("session.json"));
        if (header != null) parse(header.bytes(), SessionHeader.class).validate();
        return head(storage.read(path("head.json")));
    }

    private String sealPath(String owner) {
        return path("writer-seals/" + hash(owner.getBytes(StandardCharsets.UTF_8)) + ".json");
    }

    private boolean sealed(String owner) {
        return storage.read(sealPath(owner)) != null;
    }

    private void owned(Head head, Writer writer) {
        if (sealed(writer.owner()))
            throw new SessionLogException("Session writer is permanently sealed");
        if (head.epoch() != writer.epoch()
                || !Objects.equals(head.owner(), writer.owner())
                || head.leaseUntil() <= clock.millis())
            throw new SessionLogException("Session writer ownership expired or fenced");
    }

    @Override
    public Writer acquire(String owner, Duration lease) {
        SessionHeader desired = SessionHeader.current();
        storage.compareAndSet(path("session.json"), 0, bytes(desired));
        var header = parse(storage.read(path("session.json")).bytes(), SessionHeader.class);
        header.validate();
        if (!header.authority().equals(desired.authority()))
            throw new SessionLogException(
                    "Session authority mismatch; migrate into a new session instead of changing"
                            + " mode in place");
        Objects.requireNonNull(owner);
        if (lease.isNegative() || lease.isZero()) throw new IllegalArgumentException("lease");
        for (int i = 0; i < 32; i++) {
            if (sealed(owner))
                throw new SessionLogException("Session writer is permanently sealed");
            var value = storage.read(path("head.json"));
            var h = head(value);
            if (h.owner() != null && h.leaseUntil() > clock.millis())
                throw new SessionLogException("Session already has an active writer");
            var next =
                    new Head(
                            h.seq(),
                            h.epoch() + 1,
                            owner,
                            clock.millis() + lease.toMillis(),
                            h.commitId());
            if (storage.compareAndSet(
                    path("head.json"), value == null ? 0 : value.version(), bytes(next))) {
                // A seal can land between the initial check and reading this head. Do not
                // return a writer until that race is resolved, and release any raced claim.
                if (sealed(owner)) {
                    sealWriter(owner);
                    throw new SessionLogException("Session writer is permanently sealed");
                }
                return new Writer(owner, next.epoch());
            }
        }
        throw new SessionLogException("Writer acquisition contention");
    }

    @Override
    public Head sealWriter(String owner) {
        Objects.requireNonNull(owner);
        storage.compareAndSet(sealPath(owner), 0, bytes(Map.of("owner", owner)));
        for (int i = 0; i < 32; i++) {
            var value = storage.read(path("head.json"));
            var h = head(value);
            var next =
                    owner.equals(h.owner())
                            ? new Head(h.seq(), h.epoch() + 1, null, 0, h.commitId())
                            : h;
            // Even an unchanged head must advance its storage version. This fences a
            // paused acquisition/commit without revoking an unrelated active writer.
            if (storage.compareAndSet(
                    path("head.json"), value == null ? 0 : value.version(), bytes(next)))
                return next;
        }
        throw new SessionLogException("Writer sealing contention");
    }

    @Override
    public void renew(Writer writer, Duration lease) {
        updateOwner(writer, lease, false);
    }

    @Override
    public void release(Writer writer) {
        updateOwner(writer, Duration.ZERO, true);
    }

    private void updateOwner(Writer writer, Duration lease, boolean release) {
        for (int i = 0; i < 32; i++) {
            var value = storage.read(path("head.json"));
            var h = head(value);
            owned(h, writer);
            var next =
                    new Head(
                            h.seq(),
                            h.epoch(),
                            release ? null : h.owner(),
                            release ? 0 : clock.millis() + lease.toMillis(),
                            h.commitId());
            if (storage.compareAndSet(path("head.json"), value.version(), bytes(next))) return;
        }
        throw new SessionLogException("Writer renewal contention");
    }

    @Override
    public Head commit(Writer writer, String batchId, long expectedSeq, List<SessionEvent> events) {
        if (events.isEmpty()) return head();
        List<SessionEvent> frozen = List.copyOf(events);
        long seq = expectedSeq;
        for (var event : frozen) {
            if (event.seq() != ++seq) throw new SessionLogException("Non-contiguous event batch");
        }
        String hash = hash(bytes(frozen));
        String id = hash(batchId.getBytes(StandardCharsets.UTF_8));
        String commitPath = path("commits/" + id + ".json");
        for (int i = 0; i < 32; i++) {
            var value = storage.read(path("head.json"));
            var h = head(value);
            // Resolve a lost commit ACK even when a later batch has committed.
            if (h.seq() >= seq) {
                for (String cursor = h.commitId(); cursor != null; ) {
                    var c = readCommit(cursor);
                    if (c.batchId().equals(batchId)) {
                        if (!c.hash().equals(hash) || c.firstSeq() != expectedSeq + 1)
                            throw new SessionLogException("Batch ID reused with different content");
                        owned(h, writer);
                        return h;
                    }
                    if (c.lastSeq() < expectedSeq + 1) break;
                    cursor = c.parent();
                }
            }
            owned(h, writer);
            if (h.seq() != expectedSeq) throw new SessionLogException("Stale committed prefix");
            var blobs = new LinkedHashMap<String, String>();
            var stored = new ArrayList<SessionEvent>();
            for (var event : frozen) {
                byte[] payload = event.payloadJson().getBytes(StandardCharsets.UTF_8);
                if (payload.length > 65536) {
                    String blob = hash(payload);
                    String blobPath = path("blobs/" + blob);
                    if (!storage.compareAndSet(blobPath, 0, payload)) {
                        var existing = storage.read(blobPath);
                        if (existing == null || !Arrays.equals(existing.bytes(), payload))
                            throw new SessionLogException("Immutable payload blob conflict");
                    }
                    blobs.put(event.eventId(), blob);
                    stored.add(withPayload(event, "{}"));
                } else stored.add(event);
            }
            var commit =
                    new Commit(
                            batchId,
                            h.commitId(),
                            expectedSeq + 1,
                            seq,
                            hash,
                            List.copyOf(stored),
                            Map.copyOf(blobs));
            byte[] data = bytes(commit);
            if (!storage.compareAndSet(commitPath, 0, data)) {
                var existing = storage.read(commitPath);
                if (existing == null || !Arrays.equals(existing.bytes(), data))
                    throw new SessionLogException("Immutable commit conflict");
            }
            owned(h, writer);
            var next = new Head(seq, h.epoch(), h.owner(), h.leaseUntil(), id);
            try {
                if (storage.compareAndSet(path("head.json"), value.version(), bytes(next)))
                    return next;
            } catch (RuntimeException error) {
                try {
                    var observed = head();
                    if (id.equals(observed.commitId()) && observed.seq() == seq) {
                        owned(observed, writer);
                        return observed;
                    }
                } catch (RuntimeException inspection) {
                    error.addSuppressed(inspection);
                }
                throw error;
            }
        }
        throw new SessionLogException("Commit contention");
    }

    private Commit readCommit(String id) {
        var value = storage.read(path("commits/" + id + ".json"));
        if (value == null) throw new SessionLogException("Missing committed segment: " + id);
        var c = parse(value.bytes(), Commit.class);
        if (c.payloadBlobs() != null && !c.payloadBlobs().isEmpty()) {
            var hydrated = new ArrayList<SessionEvent>();
            for (var event : c.events()) {
                String blob = c.payloadBlobs().get(event.eventId());
                if (blob == null) hydrated.add(event);
                else {
                    var payload = storage.read(path("blobs/" + blob));
                    if (payload == null || !hash(payload.bytes()).equals(blob))
                        throw new SessionLogException("Missing or corrupt committed payload blob");
                    hydrated.add(
                            withPayload(
                                    event, new String(payload.bytes(), StandardCharsets.UTF_8)));
                }
            }
            c =
                    new Commit(
                            c.batchId(),
                            c.parent(),
                            c.firstSeq(),
                            c.lastSeq(),
                            c.hash(),
                            List.copyOf(hydrated),
                            c.payloadBlobs());
        }
        if (!hash(bytes(c.events())).equals(c.hash()))
            throw new SessionLogException("Committed segment checksum mismatch");
        return c;
    }

    private static SessionEvent withPayload(SessionEvent e, String json) {
        return new SessionEvent(
                e.schemaVersion(),
                e.eventId(),
                e.seq(),
                e.occurredAt(),
                e.type(),
                e.executionRunId(),
                e.turnId(),
                e.required(),
                json);
    }

    @Override
    public Iterable<SessionEvent> scan(long after, long through) {
        var head = head();
        if (after < 0 || through < after || through > head.seq())
            throw new IllegalArgumentException("Invalid committed prefix");
        var ids = new ArrayDeque<String>();
        var seen = new HashSet<String>();
        long expected = head.seq();
        for (String id = head.commitId(); id != null && expected > after; ) {
            if (!seen.add(id)) throw new SessionLogException("Commit chain cycle");
            var stored = storage.read(path("commits/" + id + ".json"));
            if (stored == null) throw new SessionLogException("Missing committed segment");
            var commit = parse(stored.bytes(), Commit.class);
            if (commit.lastSeq() != expected
                    || commit.firstSeq() + commit.events().size() - 1 != expected
                    || commit.events().isEmpty())
                throw new SessionLogException("Broken committed prefix");
            if (commit.firstSeq() <= through) ids.addFirst(id);
            expected = commit.firstSeq() - 1;
            id = commit.parent();
        }
        if (expected > after) throw new SessionLogException("Incomplete committed chain");
        return () ->
                new Iterator<SessionEvent>() {
                    final Iterator<String> segments = ids.iterator();
                    Iterator<SessionEvent> batch = List.<SessionEvent>of().iterator();
                    SessionEvent next;
                    long cursor = after;

                    public boolean hasNext() {
                        if (next != null) return true;
                        if (cursor >= through) return false;
                        while (true) {
                            while (batch.hasNext()) {
                                var candidate = batch.next();
                                if (candidate.seq() <= cursor) continue;
                                if (candidate.seq() != cursor + 1)
                                    throw new SessionLogException("Non-contiguous event sequence");
                                next = candidate;
                                return true;
                            }
                            if (!segments.hasNext())
                                throw new SessionLogException("Missing committed events");
                            batch = readCommit(segments.next()).events().iterator();
                        }
                    }

                    public SessionEvent next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        var event = next;
                        next = null;
                        cursor = event.seq();
                        return event;
                    }
                };
    }

    @Override
    public List<SessionEvent> readAfter(long after, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Invalid limit");
        long through = Math.min(head().seq(), after + (long) limit);
        var result = new ArrayList<SessionEvent>();
        for (var event : scan(after, through)) result.add(event);
        return List.copyOf(result);
    }

    @Override
    public long exportCursor(String sink) {
        var value =
                storage.read(
                        path("exports/" + hash(sink.getBytes(StandardCharsets.UTF_8)) + ".json"));
        return value == null ? 0 : parse(value.bytes(), Long.class);
    }

    @Override
    public void advanceExportCursor(String sink, long expected, long next) {
        if (next <= expected || next > head().seq())
            throw new IllegalArgumentException("Invalid export watermark");
        String key = path("exports/" + hash(sink.getBytes(StandardCharsets.UTF_8)) + ".json");
        for (int i = 0; i < 32; i++) {
            var value = storage.read(key);
            long current = value == null ? 0 : parse(value.bytes(), Long.class);
            if (current >= next) return;
            if (current != expected) throw new SessionLogException("Export cursor conflict");
            if (storage.compareAndSet(key, value == null ? 0 : value.version(), bytes(next)))
                return;
        }
        throw new SessionLogException("Export cursor contention");
    }

    public static String hash(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
