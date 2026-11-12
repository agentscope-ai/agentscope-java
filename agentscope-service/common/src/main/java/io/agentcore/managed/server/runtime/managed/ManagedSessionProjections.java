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
import io.agentcore.managed.server.web.managed.SessionEventDto;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Consumes self-contained MQ fact references; retained execution receipts support explicit rebuilds. */
public final class ManagedSessionProjections {
    public record Progress(long acceptedThrough, long projectedThrough) {}

    public record Fact(
            long sequence,
            String source,
            String type,
            String payload,
            String hash,
            long createdAt) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ManagedPublicResources resources;
    private final ManagedFactBundles bundles;
    private final ManagedProjectionFacts facts;
    private ManagedQueryViews queryViews;
    private Consumer<ManagedScope> visible;

    public void visible(Consumer<ManagedScope> callback) {
        visible = callback;
    }

    public void queryViews(ManagedQueryViews views) {
        this.queryViews = views;
    }

    private final ThreadLocal<Map<String, ManagedFactBundles.Prepared>> prepared =
            new ThreadLocal<>();

    public ManagedSessionProjections(
            DataSource source,
            ObjectMapper mapper,
            SessionEventNotifier notifications,
            RuntimeObjectStore objects) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        this.mapper = mapper;
        this.resources = new ManagedPublicResources(source, mapper);
        this.visible = scope -> notifications.refresh(scope.messageGroup());
        this.bundles = new ManagedFactBundles(objects, mapper);
        this.facts = new ManagedProjectionFacts(source, mapper, bundles);
    }

    void lockedHead(ManagedScope scope, Map<String, Object> snapshot) {
        facts.lockedHead(scope, snapshot);
    }

    ManagedProjectionOutbox outbox() {
        return new ManagedProjectionOutbox(jdbc.getDataSource(), mapper, facts);
    }

    /** Normal delivery never reads or writes the execution database. */
    public void deliver(ManagedProjectionNotice delivery) {
        if (queryViews == null)
            throw new IllegalStateException("Reliable projection requires a query backend");
        var progress = queryViews.progress(delivery.scope());
        var pages = new ArrayList<ManagedProjectionFacts.Page>();
        var objects = new LinkedHashMap<RuntimeObjectStore.Reference, ManagedFactBundles.Bundle>();
        var receipts = new ArrayList<ManagedProjectionNotice.Commit>();
        for (var commit : delivery.commits()) {
            if (commit.manifest().version() <= progress.commitVersion()) {
                queryViews.verifyCommit(delivery.scope(), progress, commit);
                continue;
            }
            long expected = progress.commitVersion() + pages.size() + 1;
            if (commit.manifest().version() != expected)
                throw new ManagedDeliverySuspended(Duration.ofMillis(500));
            pages.add(facts.read(delivery.scope(), commit.manifest(), objects));
            receipts.add(commit);
        }
        if (pages.isEmpty()) return;
        if (!queryViews.applyDelivery(delivery.scope(), progress, pages, receipts))
            throw new ManagedDeliverySuspended(Duration.ofMillis(250));
        visible.accept(delivery.scope());
    }

    public Progress progress(ManagedScope scope) {
        return new Progress(sourceHead(scope).through(), query().progress(scope).visibleSequence());
    }

    private ManagedQueryViews query() {
        if (queryViews == null)
            throw new IllegalStateException("A separate projection query backend is required");
        return queryViews;
    }

    /** Explicit maintenance enumeration; never called by normal consumers. */
    public List<ManagedScope> rebuildScopes(int limit) {
        return jdbc.query(
                "SELECT tenant,namespace,session_id FROM ma.session WHERE commit_version>0 ORDER BY"
                        + " scope_key LIMIT ?",
                (r, n) -> new ManagedScope(r.getString(1), r.getString(2), r.getString(3)),
                limit);
    }

    /** Freeze public payloads before entering the execution transaction. */
    <T> T prepared(
            ManagedScope scope, List<ManagedFactBundles.Content> contents, Supplier<T> work) {
        var previous = prepared.get();
        var values = new LinkedHashMap<String, ManagedFactBundles.Prepared>();
        if (previous != null) values.putAll(previous);
        var missing =
                contents.stream()
                        .filter(content -> !ephemeral(content.type()))
                        .filter(
                                content ->
                                        !values.containsKey(content.hash())
                                                || !scope.messageGroup()
                                                        .equals(
                                                                values.get(content.hash())
                                                                        .reference()
                                                                        .scope()))
                        .toList();
        values.putAll(
                ManagedBoundaryMetrics.measure(
                        ManagedBoundaryMetrics.Phase.PROJECTION_STAGE,
                        () -> bundles.stage(scope, missing)));
        prepared.set(values);
        try {
            return work.get();
        } finally {
            if (previous == null) prepared.remove();
            else prepared.set(previous);
        }
    }

    boolean preparing() {
        return prepared.get() != null;
    }

    private static boolean ephemeral(String type) {
        return Set.of("item.delta", "event_start", "event_delta").contains(type);
    }

    public void capture(ManagedScope scope, String id, String type, String payload, long now) {
        if (ephemeral(type)) return;
        var content = new ManagedFactBundles.Content(type, payload);
        String hash = content.hash();
        String semantic = semanticHash(scope, id, type, payload);
        var staged = prepared.get() == null ? null : prepared.get().get(hash);
        if (staged == null && prepared.get() != null)
            throw new ManagedStateException(
                    "projection_preparation_changed",
                    "Fact changed after object preparation; retry submission");
        if (staged == null) staged = bundles.stage(scope, List.of(content)).get(hash);
        var ref = staged.reference();
        if (!scope.messageGroup().equals(ref.scope()))
            throw new ManagedStateException("projection_object_invalid", "Prepared scope differs");
        long next = facts.capture(scope, id, type, hash, semantic, now, staged);
        if (next < 0) return;
        Map<String, Object> data = data(payload);
        resources.accountUsage(
                scope, new SessionEventDto(id, scope.sessionId(), next, type, data, null, now));
        new ManagedSessionStoreOutput(jdbc, mapper).capture(scope, type, data, now);
    }

    private String semanticHash(ManagedScope scope, String id, String type, String payload) {
        if (!"tool.completed".equals(type)) return null;
        var data = data(payload);
        if (!id.equals(
                "tool_completed_"
                        + ManagedScope.fingerprint(
                                String.valueOf(data.get("turn_id")),
                                String.valueOf(data.get("tool_call_id"))))) return null;
        var values = new LinkedHashMap<String, Object>();
        for (String key :
                List.of("schema_version", "turn_id", "tool_call_id", "name", "status", "result"))
            values.put(key, data.get(key));
        return ManagedScope.fingerprint(new String(bundles.encode(values), StandardCharsets.UTF_8));
    }

    /** Explicit rebuild only; normal MQ delivery uses no execution SQL. */
    public int project(ManagedScope scope, int limit) {
        var progress = query().progress(scope);
        var commits = facts.commits(scope, progress.commitVersion(), limit);
        if (commits.isEmpty()) return 0;
        var pages = commits.stream().map(c -> facts.read(scope, c.manifest())).toList();
        if (!query().applyDelivery(scope, progress, pages, commits)) return 0;
        visible.accept(scope);
        return pages.stream().mapToInt(p -> p.facts().size()).sum();
    }

    ManagedProjectionFacts.Attached attach(ManagedScope scope) {
        return facts.attach(scope);
    }

    long boundary(ManagedScope scope) {
        return facts.boundary(scope);
    }

    public record SourceHead(long through, long version) {}

    public SourceHead sourceHead(ManagedScope scope) {
        return jdbc.queryForObject(
                "SELECT accepted_sequence,commit_version FROM ma.session WHERE" + " scope_key=?",
                (row, index) -> new SourceHead(row.getLong(1), row.getLong(2)),
                scope.messageGroup());
    }

    public long acceptedVersion(ManagedScope scope) {
        return facts.acceptedVersion(scope);
    }

    public long projectedVersion(ManagedScope scope) {
        return query().progress(scope).commitVersion();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new ManagedStateException(
                    "projection_payload_invalid", "Invalid projection payload");
        }
    }
}
