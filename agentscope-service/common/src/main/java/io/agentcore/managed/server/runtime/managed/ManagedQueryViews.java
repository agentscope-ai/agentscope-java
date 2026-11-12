/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Build;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Progress;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Resource;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionWriter;
import io.agentcore.managed.server.web.managed.AgentSessionView;
import io.agentcore.managed.server.web.managed.SessionEventDto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Query reducer and stateless signed snapshots. No execution database writes or locks. */
public final class ManagedQueryViews {
    record SnapshotToken(Progress progress, long expiresAt, String turn) {}

    record PageToken(String snapshot, String kind, long created, String id) {}

    private final SessionProjectionStore store;
    private final ObjectMapper mapper;
    private final byte[] signingKey;
    private final String build;
    private final int segmentSpan;
    private final ManagedPublicResources reducerHelpers;

    public ManagedQueryViews(
            SessionProjectionStore store,
            ObjectMapper mapper,
            byte[] signingKey,
            String build,
            int segmentSpan,
            ManagedPublicResources reducerHelpers) {
        if (signingKey.length < 32)
            throw new IllegalArgumentException("Query signing key needs at least 32 bytes");
        this.store = store;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.signingKey = signingKey.clone();
        this.build = build;
        this.segmentSpan = segmentSpan;
        this.reducerHelpers = reducerHelpers;
    }

    ManagedProtocolQuery protocol() {
        return new ManagedProtocolQuery(this, store, mapper);
    }

    public Progress progress(ManagedScope scope) {
        var selected = new Build(scope, build, segmentSpan, segmentSpan);
        try {
            return store.progress(selected);
        } catch (EmptyResultDataAccessException missing) {
            return store.open(selected);
        } catch (ManagedStateException missing) {
            if (!"projection_missing".equals(missing.code())) throw missing;
            return store.open(selected);
        }
    }

    public List<SessionProjectionStore.Event> events(ManagedScope scope, long after, int limit) {
        var p = progress(scope);
        if (after >= p.visibleSequence()) return List.of();
        return store.events(p, after, Math.min(limit, 256));
    }

    public boolean apply(ManagedScope scope, List<ManagedSessionProjections.Fact> facts) {
        return apply(scope, progress(scope).commitVersion() + 1, facts);
    }

    public boolean apply(
            ManagedScope scope, long commitVersion, List<ManagedSessionProjections.Fact> facts) {
        var p = progress(scope);
        if (commitVersion <= p.commitVersion()) return false;
        var remaining = facts.stream().filter(f -> f.sequence() > p.visibleSequence()).toList();
        if (remaining.isEmpty() && !facts.isEmpty()) return false;
        facts = remaining;
        long through =
                facts.isEmpty() ? p.visibleSequence() : facts.get(facts.size() - 1).sequence();
        return applyBatch(
                scope, p, List.of(new ManagedProjectionFacts.Page(commitVersion, through, facts)));
    }

    void verifyCommit(ManagedScope scope, Progress p, ManagedProjectionNotice.Commit commit) {
        var marker =
                store.resource(p, "projection_commit", Long.toString(commit.manifest().version()));
        if (marker == null
                || !marker.payload().equals(json(Map.of("id", commit.id(), "hash", commit.hash()))))
            throw new ManagedStateException(
                    "projection_conflict", "Visible commit identity differs");
    }

    boolean applyDelivery(
            ManagedScope scope,
            Progress p,
            List<ManagedProjectionFacts.Page> pages,
            List<ManagedProjectionNotice.Commit> receipts) {
        return applyBatch(scope, p, pages, receipts);
    }

    boolean applyBatch(ManagedScope scope, Progress p, List<ManagedProjectionFacts.Page> pages) {
        return applyBatch(scope, p, pages, List.of());
    }

    private boolean applyBatch(
            ManagedScope scope,
            Progress p,
            List<ManagedProjectionFacts.Page> pages,
            List<ManagedProjectionNotice.Commit> receipts) {

        if (!scope.equals(p.build().scope()))
            throw new IllegalArgumentException("Projection scope differs");
        var commits =
                pages.stream()
                        .map(
                                page ->
                                        new SessionProjectionStore.Commit(
                                                page.version(), page.through()))
                        .toList();
        SessionProjectionStore.validateCommits(p, commits);
        var selected = new ArrayList<ManagedSessionProjections.Fact>();
        long through = p.visibleSequence();
        for (var page : pages) {
            for (var fact : page.facts()) {
                if (fact.sequence() != ++through)
                    throw new ManagedStateException("projection_gap", "Manifest event gap");
                selected.add(fact);
            }
            if (through != page.through())
                throw new ManagedStateException("projection_gap", "Manifest boundary differs");
        }
        var overlay = new LinkedHashMap<ManagedPublicResources.Key, Resource>();
        var resources = new ArrayList<Resource>();
        var events = new ArrayList<SessionProjectionStore.Event>();
        var indexes = new ArrayList<SessionProjectionStore.IndexEntry>();
        var protocolOverlay = new LinkedHashMap<String, Resource>();
        for (var f : selected) {
            var payload = map(parse(f.payload()));
            var event =
                    new SessionEventDto(
                            f.source(),
                            scope.sessionId(),
                            f.sequence(),
                            f.type(),
                            payload,
                            null,
                            f.createdAt());
            resources.addAll(reduce(p, event, overlay));
            try (var protocol =
                    new ManagedProtocolProjection(
                            store, mapper, p, f, protocolOverlay, resources, indexes)) {
                protocol.apply(new JdbcTemplate(reducerHelpers.source()));
            }
            events.add(
                    new SessionProjectionStore.Event(
                            f.sequence(),
                            f.source(),
                            0,
                            f.source(),
                            f.type(),
                            f.payload(),
                            f.createdAt()));
        }
        for (var receipt : receipts)
            resources.add(
                    new Resource(
                            "projection_commit",
                            Long.toString(receipt.manifest().version()),
                            receipt.manifest().through(),
                            receipt.manifest().through(),
                            false,
                            json(Map.of("id", receipt.id(), "hash", receipt.hash()))));
        return new SessionProjectionWriter(store)
                .applyBatch(p, commits, events, resources, indexes);
    }

    private List<Resource> reduce(
            Progress p, SessionEventDto event, Map<ManagedPublicResources.Key, Resource> overlay) {
        var data = new LinkedHashMap<>(event.payload());
        data.putIfAbsent("source", Map.of("kind", "hands"));
        var keys = ManagedPublicResources.keys(event, data);
        var state = new LinkedHashMap<String, Object>();
        var before = new LinkedHashMap<ManagedPublicResources.Key, Resource>();
        for (var key : keys) load(p, key, overlay, before, state);
        String item = text(data.getOrDefault("item_id", map(data.get("item")).get("id")));
        Object current = item == null ? null : map(state.get("current_tools")).get(item);
        if (current != null)
            load(
                    p,
                    new ManagedPublicResources.Key("tools", data.get("turn_id") + ":" + current),
                    overlay,
                    before,
                    state);
        var reducer = new AgentSessionView(state);
        reducer.applyIncremental(
                new SessionEventDto(
                        event.id(),
                        event.sessionId(),
                        event.seq(),
                        event.type(),
                        data,
                        null,
                        event.createdAt()));
        var after = reducer.state();
        var changed = new LinkedHashSet<>(before.keySet());
        after.forEach(
                (kind, value) -> {
                    if (value instanceof Map<?, ?> index)
                        for (Object id : index.keySet())
                            changed.add(new ManagedPublicResources.Key(kind, String.valueOf(id)));
                });
        var result = new ArrayList<Resource>();
        for (var key : changed) {
            if (key.id() == null || key.id().isBlank() || key.id().length() > 512)
                throw new IllegalArgumentException("Invalid public resource identity");
            var prior = before.get(key);
            Object next = map(after.get(key.kind())).get(key.id());
            if (Objects.equals(
                    prior == null || prior.deleted() ? null : parse(prior.payload()), next))
                continue;
            if (next instanceof Map<?, ?> raw && raw.get("schema_version") != null) {
                var envelope = new LinkedHashMap<>(map(next));
                envelope.put(
                        "cursor",
                        ManagedPublicResources.eventCursor(p.build().scope(), event.seq()));
                next = envelope;
            }
            var value =
                    new Resource(
                            key.kind(),
                            key.id(),
                            event.seq(),
                            prior == null ? event.seq() : prior.createdSequence(),
                            next == null,
                            json(next));
            overlay.put(key, value);
            result.add(value);
            if (key.kind().equals("model_usage")) {
                var old = prior == null || prior.deleted() ? null : map(parse(prior.payload()));
                var updated = next == null ? null : map(next);
                updateUsage(p, event, overlay, result, "session", old, updated, "session_only");
                String oldTurn = old == null ? null : text(old.get("turn_id")),
                        newTurn = updated == null ? null : text(updated.get("turn_id"));
                if (Objects.equals(oldTurn, newTurn)) {
                    if (newTurn != null)
                        updateUsage(
                                p,
                                event,
                                overlay,
                                result,
                                "turn:" + newTurn,
                                old,
                                updated,
                                "turn_only");
                } else {
                    if (oldTurn != null)
                        updateUsage(
                                p,
                                event,
                                overlay,
                                result,
                                "turn:" + oldTurn,
                                old,
                                null,
                                "turn_only");
                    if (newTurn != null)
                        updateUsage(
                                p,
                                event,
                                overlay,
                                result,
                                "turn:" + newTurn,
                                null,
                                updated,
                                "turn_only");
                }
            }
        }
        return result;
    }

    private void updateUsage(
            Progress p,
            SessionEventDto event,
            Map<ManagedPublicResources.Key, Resource> overlay,
            List<Resource> result,
            String id,
            Map<String, Object> before,
            Map<String, Object> after,
            String label) {
        var key = new ManagedPublicResources.Key("usage", id);
        var prior = get(p, key, overlay);
        var value =
                new Resource(
                        "usage",
                        id,
                        event.seq(),
                        prior == null ? event.seq() : prior.createdSequence(),
                        false,
                        reducerHelpers.aggregate(
                                prior == null ? "{}" : prior.payload(), before, after, label));
        overlay.put(key, value);
        result.removeIf(
                r -> r.kind().equals("usage") && r.id().equals(id) && r.sequence() == event.seq());
        result.add(value);
    }

    private Resource get(
            Progress p,
            ManagedPublicResources.Key key,
            Map<ManagedPublicResources.Key, Resource> overlay) {
        if (!overlay.containsKey(key)) overlay.put(key, store.resource(p, key.kind(), key.id()));
        return overlay.get(key);
    }

    private void load(
            Progress p,
            ManagedPublicResources.Key key,
            Map<ManagedPublicResources.Key, Resource> overlay,
            Map<ManagedPublicResources.Key, Resource> before,
            Map<String, Object> state) {
        if (before.containsKey(key)) return;
        var prior = get(p, key, overlay);
        before.put(key, prior);
        if (prior != null && !prior.deleted())
            map(state.computeIfAbsent(key.kind(), ignored -> new LinkedHashMap<>()))
                    .put(key.id(), parse(prior.payload()));
    }

    public ManagedPublicResources.Snapshot snapshot(ManagedScope scope, String turn) {
        var token =
                new SnapshotToken(
                        progress(scope),
                        System.currentTimeMillis() + Duration.ofMinutes(10).toMillis(),
                        turn);
        return snapshot(sign(token), token);
    }

    private ManagedPublicResources.Snapshot snapshot(String id, SnapshotToken token) {
        return new ManagedPublicResources.Snapshot(
                id,
                token.progress().visibleSequence(),
                token.expiresAt(),
                usage(token.progress(), token.turn()),
                token.turn());
    }

    public ManagedPublicResources.Usage usage(ManagedScope scope, String turn) {
        var p = progress(scope);
        return new ManagedPublicResources.Usage(p.visibleSequence(), usage(p, turn));
    }

    private Map<String, Object> usage(Progress p, String turn) {
        var r = store.resource(p, "usage", turn == null ? "session" : "turn:" + turn);
        return r == null ? Map.of() : map(parse(r.payload()));
    }

    public ManagedPublicResources.Page page(
            ManagedScope scope, String snapshotId, String kind, String after, int limit) {
        checkKind(kind);
        if (limit < 1 || limit > 256)
            throw new IllegalArgumentException("Resource limit must be 1..256");
        var token = token(scope, snapshotId);
        long created = 0;
        String id = "";
        if (after != null && !after.isBlank()) {
            var cursor = verify(after, PageToken.class);
            if (!snapshotId.equals(cursor.snapshot()) || !kind.equals(cursor.kind()))
                throw new IllegalArgumentException("Cursor query differs");
            created = cursor.created();
            id = cursor.id();
        }
        var data = new ArrayList<Map<String, Object>>();
        boolean more = false;
        for (int scanned = 0; scanned < 4096; ) {
            var rows =
                    store.directory(token.progress(), kind, created, id, Math.min(256, limit + 1));
            if (rows.isEmpty()) {
                more = false;
                break;
            }
            boolean full = false;
            for (var row : rows) {
                var r = store.resource(token.progress(), kind, row.id());
                var value = r == null || r.deleted() ? null : map(parse(r.payload()));
                boolean matches = value != null && matches(value, token.turn());
                if (matches && data.size() == limit) {
                    more = true;
                    full = true;
                    break;
                }
                created = row.createdSequence();
                id = row.id();
                scanned++;
                if (matches) data.add(settle(token.progress(), kind, value));
            }
            if (full) break;
            more = rows.size() == Math.min(256, limit + 1);
            if (!more) break;
        }
        String next = more ? sign(new PageToken(snapshotId, kind, created, id)) : null;
        return new ManagedPublicResources.Page(
                snapshot(snapshotId, token), List.copyOf(data), next, more);
    }

    public Map<String, Object> resource(
            ManagedScope scope, String snapshotId, String kind, String id) {
        checkKind(kind);
        var token =
                snapshotId == null
                        ? new SnapshotToken(progress(scope), Long.MAX_VALUE, null)
                        : token(scope, snapshotId);
        var r = store.resource(token.progress(), kind, id);
        var value = r == null || r.deleted() ? null : map(parse(r.payload()));
        if (value == null || !matches(value, token.turn()))
            throw new ManagedStateException("resource_not_found", "Public resource not found");
        return settle(token.progress(), kind, value);
    }

    private Map<String, Object> settle(Progress progress, String kind, Map<String, Object> value) {
        if (!Set.of("items", "tools").contains(kind)) return value;
        var data = map(value.get("data"));
        String run = text(data.get("run_id"));
        if (run == null) return value;
        var resource = store.resource(progress, "runs", run);
        if (resource == null || resource.deleted()) return value;
        var ended = map(parse(resource.payload()));
        if (!"run.ended".equals(ended.get("type"))) return value;
        if (kind.equals("items") && "in_progress".equals(map(data.get("item")).get("status")))
            map(data.get("item"))
                    .put(
                            "status",
                            "completed".equals(map(ended.get("data")).get("status"))
                                    ? "completed"
                                    : "incomplete");
        else if (kind.equals("tools")
                && Set.of("running", "requested", "generating_arguments")
                        .contains(String.valueOf(data.get("status"))))
            data.put("status", "unknown");
        return value;
    }

    private static boolean matches(Map<String, Object> value, String turn) {
        return turn == null
                || turn.equals(
                        value.getOrDefault("turn_id", map(value.get("data")).get("turn_id")));
    }

    private static void checkKind(String kind) {
        if (!ManagedPublicResources.KINDS.contains(kind))
            throw new IllegalArgumentException("Unknown public resource kind");
    }

    private SnapshotToken token(ManagedScope scope, String value) {
        var token = verify(value, SnapshotToken.class);
        if (!scope.equals(token.progress().build().scope())
                || token.expiresAt() <= System.currentTimeMillis())
            throw new ManagedStateException(
                    "snapshot_expired", "Snapshot expired or scope differs");
        return token;
    }

    String sign(Object value) {
        String body =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(json(value).getBytes(StandardCharsets.UTF_8));
        return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(body));
    }

    <T> T verify(String value, Class<T> type) {
        try {
            if (value == null || value.length() > 16384) throw new IllegalArgumentException();
            var parts = value.split("\\.", -1);
            if (parts.length != 2
                    || !MessageDigest.isEqual(
                            mac(parts[0]), Base64.getUrlDecoder().decode(parts[1])))
                throw new IllegalArgumentException();
            return mapper.readValue(Base64.getUrlDecoder().decode(parts[0]), type);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid signed query cursor", invalid);
        }
    }

    private byte[] mac(String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Object parse(String value) {
        try {
            return mapper.readValue(value, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
