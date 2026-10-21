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
package io.agentscope.builder.portal.session;

import static io.agentscope.builder.portal.session.PortalResourceProjection.map;

import io.agentscope.builder.portal.persistence.PortalResourceRepository;
import io.agentscope.builder.portal.persistence.PortalResourceVersionRepository;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.persistence.PortalSnapshotEntity;
import io.agentscope.builder.portal.persistence.PortalSnapshotRepository;
import io.agentscope.builder.portal.persistence.PortalTurnRepository;
import io.agentscope.builder.web.managed.SessionEventCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Fixed-cursor keyset pages. No request copies a Session's complete resource history. */
@Service
public class PortalResourceReads {
    private final PortalSessionRepository sessions;
    private final PortalTurnRepository turns;
    private final PortalResourceRepository resources;
    private final PortalResourceVersionRepository versions;
    private final PortalSnapshotRepository snapshots;
    private final PortalResourceProjection projection;
    private final PortalSessionAccess access;
    private final TransactionTemplate tx;
    private final long ttlMs;
    private final int maxSnapshots;

    public PortalResourceReads(
            PortalSessionRepository sessions,
            PortalTurnRepository turns,
            PortalResourceRepository resources,
            PortalResourceVersionRepository versions,
            PortalSnapshotRepository snapshots,
            PortalResourceProjection projection,
            PortalSessionAccess access,
            TransactionTemplate tx,
            @Value("${builder.portal.snapshot-ttl-seconds:120}") long ttlSeconds,
            @Value("${builder.portal.max-snapshots-per-session:16}") int maxSnapshots) {
        if (ttlSeconds < 10 || ttlSeconds > 900 || maxSnapshots < 1 || maxSnapshots > 64)
            throw new IllegalArgumentException("Invalid resource snapshot limits");
        this.sessions = sessions;
        this.turns = turns;
        this.resources = resources;
        this.versions = versions;
        this.snapshots = snapshots;
        this.projection = projection;
        this.access = access;
        this.tx = tx;
        this.ttlMs = ttlSeconds * 1000;
        this.maxSnapshots = maxSnapshots;
    }

    public Map<String, Object> snapshot(String session, int limit, HttpHeaders headers) {
        return snapshot(session, null, limit, headers);
    }

    /** Reads the committed aggregate without creating a retained pagination snapshot. */
    public Map<String, Object> usage(String session, String turn, HttpHeaders headers) {
        access.authorize(session, headers, "read");
        return tx.execute(
                ignored -> {
                    var snapshot = create(session, turn, headers, false);
                    var result = metadata(snapshot);
                    result.put("data", projection.read(snapshot.usage));
                    return result;
                });
    }

    public Map<String, Object> snapshot(
            String session, String turn, int limit, HttpHeaders headers) {
        validate("items", limit);
        access.authorize(session, headers, "read");
        return tx.execute(
                ignored -> {
                    var snapshot = create(session, turn, headers, true);
                    var result = metadata(snapshot);
                    result.put("usage", projection.read(snapshot.usage));
                    var pages = new LinkedHashMap<String, Object>();
                    for (String kind : PortalResourceProjection.PUBLIC_KINDS)
                        pages.put(kind, page(snapshot, kind, "", limit));
                    result.put("resources", pages);
                    ensureLive(snapshot);
                    return result;
                });
    }

    public Map<String, Object> page(
            String session,
            String kind,
            String snapshotId,
            String after,
            int limit,
            HttpHeaders headers) {
        return page(session, null, kind, snapshotId, after, limit, headers);
    }

    public Map<String, Object> page(
            String session,
            String turn,
            String kind,
            String snapshotId,
            String after,
            int limit,
            HttpHeaders headers) {
        validate(kind, limit);
        access.authorize(session, headers, "read");
        return tx.execute(
                ignored -> {
                    String key = "";
                    String id = snapshotId;
                    if (after != null && !after.isBlank()) {
                        var cursor = cursor(after);
                        if (!session.equals(cursor.get("session"))
                                || !kind.equals(cursor.get("kind"))
                                || !(turn == null ? "" : turn)
                                        .equals(cursor.getOrDefault("turn", "")))
                            throw invalidCursor();
                        if (id != null && !id.equals(cursor.get("snapshot"))) throw invalidCursor();
                        id = String.valueOf(cursor.get("snapshot"));
                        key = String.valueOf(cursor.get("key"));
                        if (key.isBlank() || key.length() > 512) throw invalidCursor();
                    }
                    var snapshot =
                            id == null
                                    ? create(session, turn, headers, true)
                                    : resolve(session, turn, id);
                    var result = page(snapshot, kind, key, limit);
                    ensureLive(snapshot);
                    return result;
                });
    }

    public Map<String, Object> get(
            String session, String kind, String key, String snapshotId, HttpHeaders headers) {
        return get(session, null, kind, key, snapshotId, headers);
    }

    public Map<String, Object> get(
            String session,
            String turn,
            String kind,
            String key,
            String snapshotId,
            HttpHeaders headers) {
        validate(kind, 1);
        if (key == null || key.isBlank() || key.length() > 512)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid resource ID");
        access.authorize(session, headers, "read");
        return tx.execute(
                ignored -> {
                    var snapshot =
                            snapshotId == null
                                    ? create(session, turn, headers, false)
                                    : resolve(session, turn, snapshotId);
                    var value = related(snapshot, kind, Set.of(key)).get(key);
                    if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    var result = metadata(snapshot);
                    result.put("data", normalize(snapshot, kind, List.of(value)).get(0));
                    ensureLive(snapshot);
                    return result;
                });
    }

    private PortalSnapshotEntity create(
            String session, String turn, HttpHeaders headers, boolean persist) {
        var row =
                sessions.lockById(session)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        access.authorize(session, headers, "read");
        var selectedTurn =
                turn == null
                        ? null
                        : turns.findById(turn)
                                .filter(value -> value.sessionId.equals(session))
                                .orElseThrow(
                                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        long now = System.currentTimeMillis();
        if (persist
                && snapshots
                                .findBySessionIdAndExpiresAtGreaterThan(
                                        session, now, PageRequest.of(0, maxSnapshots))
                                .size()
                        >= maxSnapshots)
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Active snapshot limit reached; reuse snapshot_id");
        var snapshot = new PortalSnapshotEntity();
        snapshot.id = persist ? UUID.randomUUID().toString() : null;
        snapshot.sessionId = session;
        snapshot.turnId = turn;
        snapshot.sequence = row.nextEventSequence;
        snapshot.expiresAt = now + ttlMs;
        snapshot.usage = selectedTurn == null ? row.usageSummary : selectedTurn.usageSummary;
        return persist ? snapshots.saveAndFlush(snapshot) : snapshot;
    }

    private PortalSnapshotEntity resolve(String session, String turn, String id) {
        if (id.length() > 64) throw invalidCursor();
        var row =
                snapshots
                        .findById(id)
                        .filter(s -> s.sessionId.equals(session) && Objects.equals(turn, s.turnId))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.GONE,
                                                "Snapshot unavailable; restart pagination"));
        ensureLive(row);
        return row;
    }

    private Map<String, Object> page(
            PortalSnapshotEntity snapshot, String kind, String after, int limit) {
        var values = new TreeMap<String, Map<String, Object>>();
        for (var row :
                resources.page(
                        snapshot.sessionId,
                        kind,
                        snapshot.turnId,
                        after,
                        snapshot.sequence,
                        PageRequest.of(0, limit + 1)))
            values.put(row.resourceKey, projection.read(row.data));
        for (var row :
                versions.page(
                        snapshot.sessionId,
                        kind,
                        snapshot.turnId,
                        after,
                        snapshot.sequence,
                        System.currentTimeMillis(),
                        PageRequest.of(0, limit + 1)))
            values.put(row.resourceKey, projection.read(row.data));
        boolean more = values.size() > limit;
        var keys = new ArrayList<>(values.keySet());
        var selected = keys.subList(0, Math.min(keys.size(), limit));
        var result = metadata(snapshot);
        result.put("data", normalize(snapshot, kind, selected.stream().map(values::get).toList()));
        result.put("has_more", more);
        result.put(
                "next_cursor",
                more ? encode(snapshot, kind, selected.get(selected.size() - 1)) : null);
        return result;
    }

    private Map<String, Map<String, Object>> related(
            PortalSnapshotEntity snapshot, String kind, Set<String> keys) {
        if (keys.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, Map<String, Object>>();
        var ids =
                keys.stream()
                        .map(key -> projection.resourceId(snapshot.sessionId, kind, key))
                        .toList();
        for (var row : resources.findAllById(ids))
            if (row.validFrom <= snapshot.sequence
                    && (snapshot.turnId == null || snapshot.turnId.equals(row.turnId)))
                result.put(row.resourceKey, projection.read(row.data));
        for (var row :
                versions.atKeys(
                        snapshot.sessionId,
                        kind,
                        snapshot.turnId,
                        keys,
                        snapshot.sequence,
                        System.currentTimeMillis()))
            result.put(row.resourceKey, projection.read(row.data));
        return result;
    }

    private List<Map<String, Object>> normalize(
            PortalSnapshotEntity snapshot, String kind, List<Map<String, Object>> values) {
        var runIds = new LinkedHashSet<String>();
        var modelIds = new LinkedHashSet<String>();
        for (var value : values) {
            var data = map(value.get("data"));
            if (data.get("run_id") != null) runIds.add(String.valueOf(data.get("run_id")));
            if (data.get("model_call_id") != null)
                modelIds.add(String.valueOf(data.get("model_call_id")));
        }
        var runs = related(snapshot, "runs", runIds);
        var models = related(snapshot, "model_usage", modelIds);
        for (var value : values) {
            if (value.get("created_at") instanceof Number created)
                value.put("created_at", Instant.ofEpochMilli(created.longValue()).toString());
            var data = map(value.get("data"));
            var run = runs.get(String.valueOf(data.get("run_id")));
            boolean ended = run != null && "run.ended".equals(run.get("type"));
            var model = models.get(String.valueOf(data.get("model_call_id")));
            boolean failedModel =
                    model != null
                            && Set.of("failed", "cancelled", "interrupted")
                                    .contains(String.valueOf(model.get("status")));
            if (kind.equals("items")) {
                var item = map(data.get("item"));
                if ("in_progress".equals(item.get("status")) && (ended || failedModel))
                    item.put(
                            "status",
                            !failedModel && "completed".equals(map(run.get("data")).get("status"))
                                    ? "completed"
                                    : "incomplete");
            } else if (kind.equals("tools")
                    && (ended || failedModel)
                    && Set.of("running", "requested", "generating_arguments")
                            .contains(String.valueOf(data.get("status"))))
                data.put("status", "unknown");
        }
        return values;
    }

    private Map<String, Object> metadata(PortalSnapshotEntity row) {
        var result = new LinkedHashMap<String, Object>();
        if (row.id != null) result.put("snapshot_id", row.id);
        result.put("as_of", SessionEventCursor.encode(row.sessionId, row.sequence));
        if (row.turnId != null) result.put("turn_id", row.turnId);
        result.put("expires_at", row.expiresAt);
        return result;
    }

    private String encode(PortalSnapshotEntity row, String kind, String key) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        projection
                                .json(
                                        new TreeMap<>(
                                                Map.of(
                                                        "v",
                                                        1,
                                                        "session",
                                                        row.sessionId,
                                                        "kind",
                                                        kind,
                                                        "turn",
                                                        row.turnId == null ? "" : row.turnId,
                                                        "snapshot",
                                                        row.id,
                                                        "key",
                                                        key)))
                                .getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> cursor(String cursor) {
        try {
            if (cursor.length() > 2048) throw invalidCursor();
            var decoded =
                    projection.read(
                            new String(
                                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            if (!Integer.valueOf(1).equals(decoded.get("v"))) throw invalidCursor();
            return decoded;
        } catch (RuntimeException error) {
            throw invalidCursor();
        }
    }

    private static void validate(String kind, int limit) {
        if (!PortalResourceProjection.PUBLIC_KINDS.contains(kind) || limit < 1 || limit > 256)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid resource or limit; limit must be 1..256");
    }

    private static void ensureLive(PortalSnapshotEntity snapshot) {
        if (snapshot.expiresAt <= System.currentTimeMillis())
            throw new ResponseStatusException(
                    HttpStatus.GONE, "Snapshot expired; restart pagination");
    }

    private static ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid resource cursor");
    }

    @Scheduled(
            fixedDelayString = "${builder.portal.snapshot-cleanup-ms:1000}",
            scheduler = "portalSnapshotScheduler")
    public void expire() {
        tx.executeWithoutResult(
                ignored -> {
                    long now = System.currentTimeMillis();
                    var oldVersions = versions.expired(now, PageRequest.of(0, 256));
                    if (!oldVersions.isEmpty()) versions.deleteAllByIdInBatch(oldVersions);
                    var oldSnapshots = snapshots.expired(now, PageRequest.of(0, 256));
                    if (!oldSnapshots.isEmpty()) snapshots.deleteAllByIdInBatch(oldSnapshots);
                });
    }
}
