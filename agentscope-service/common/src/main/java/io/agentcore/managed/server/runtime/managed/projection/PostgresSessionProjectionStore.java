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
package io.agentcore.managed.server.runtime.managed.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** PostgreSQL baseline on an independently supplied query DataSource, with no execution FKs. */
public final class PostgresSessionProjectionStore implements SessionProjectionStore, AutoCloseable {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean ownSource;

    public PostgresSessionProjectionStore(DataSource source, ObjectMapper mapper) {
        this(source, mapper, false);
    }

    public PostgresSessionProjectionStore(
            DataSource source, ObjectMapper mapper, boolean ownSource) {
        this.ownSource = ownSource;
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        this.mapper = mapper;
    }

    @Override
    public void close() throws Exception {
        if (ownSource && jdbc.getDataSource() instanceof AutoCloseable closeable) closeable.close();
    }

    public static void initialize(DataSource source) {
        new ResourceDatabasePopulator(new ClassPathResource("managed-query-store.sql"))
                .execute(source);
    }

    @Override
    public void putIndexes(Build b, List<IndexEntry> indexes) {
        for (var row : indexes) {
            var value = json(row);
            jdbc.update(
                    "INSERT INTO"
                        + " ma_query.resource_index(scope_key,build_id,kind,position,sequence,data)"
                        + " VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    b.scope().messageGroup(),
                    b.id(),
                    row.kind(),
                    row.position(),
                    row.sequence(),
                    value);
            identical(
                    value,
                    jdbc.queryForObject(
                            "SELECT data FROM ma_query.resource_index WHERE scope_key=? AND"
                                    + " build_id=? AND kind=? AND position=?",
                            String.class,
                            b.scope().messageGroup(),
                            b.id(),
                            row.kind(),
                            row.position()));
        }
    }

    @Override
    public List<IndexEntry> index(
            Progress p, String kind, String after, boolean descending, int limit) {
        validateSnapshot(p);
        bounds(p, 0, limit);
        String compare = descending ? "<" : ">";
        var args =
                new ArrayList<Object>(
                        List.of(
                                p.build().scope().messageGroup(),
                                p.build().id(),
                                kind,
                                p.visibleSequence()));
        if (after != null) args.add(after);
        args.add(limit);
        return jdbc.query(
                "SELECT data FROM ma_query.resource_index WHERE scope_key=? AND build_id=? AND"
                        + " kind=? AND sequence<=?"
                        + (after == null ? "" : " AND position" + compare + "?")
                        + " ORDER BY position "
                        + (descending ? "DESC" : "ASC")
                        + " LIMIT ?",
                (r, n) -> parse(r.getString(1), IndexEntry.class),
                args.toArray());
    }

    @Override
    public Progress open(Build build) {
        jdbc.update(
                "INSERT INTO ma_query.progress(scope_key,build_id,event_span,directory_span)"
                        + " VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
                build.scope().messageGroup(),
                build.id(),
                build.eventSegmentSpan(),
                build.directorySegmentSpan());
        return progress(build);
    }

    @Override
    public Progress progress(Build build) {
        return jdbc.queryForObject(
                "SELECT * FROM ma_query.progress WHERE scope_key=? AND build_id=?",
                (row, index) -> {
                    if (row.getInt("event_span") != build.eventSegmentSpan()
                            || row.getInt("directory_span") != build.directorySegmentSpan())
                        throw new ManagedStateException(
                                "projection_layout_conflict", "Build layout is immutable");
                    return new Progress(
                            build,
                            row.getLong("visible_sequence"),
                            row.getLong("commit_version"),
                            row.getLong("progress_version"));
                },
                build.scope().messageGroup(),
                build.id());
    }

    @Override
    public void putEvent(Build build, Event event) {
        String value = json(event);
        jdbc.update(
                "INSERT INTO ma_query.event_history(scope_key,build_id,segment,sequence,data)"
                        + " VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                build.scope().messageGroup(),
                build.id(),
                segment(event.sequence(), build.eventSegmentSpan()),
                event.sequence(),
                value);
        String old =
                jdbc.queryForObject(
                        "SELECT data FROM ma_query.event_history WHERE scope_key=? AND build_id=?"
                                + " AND segment=? AND sequence=?",
                        String.class,
                        build.scope().messageGroup(),
                        build.id(),
                        segment(event.sequence(), build.eventSegmentSpan()),
                        event.sequence());
        identical(value, old);
    }

    @Override
    public void putResource(Build build, Resource resource) {
        String value = json(resource);
        jdbc.update(
                "INSERT INTO"
                    + " ma_query.resource_version(scope_key,build_id,kind,resource_id,sequence,data)"
                    + " VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                build.scope().messageGroup(),
                build.id(),
                resource.kind(),
                resource.id(),
                resource.sequence(),
                value);
        identical(
                value,
                jdbc.queryForObject(
                        "SELECT data FROM ma_query.resource_version WHERE scope_key=? AND"
                                + " build_id=? AND kind=? AND resource_id=? AND sequence=?",
                        String.class,
                        build.scope().messageGroup(),
                        build.id(),
                        resource.kind(),
                        resource.id(),
                        resource.sequence()));
        // One immutable creation identity per resource, even when older versions arrive late.
        jdbc.update(
                "INSERT INTO"
                    + " ma_query.resource_directory(scope_key,build_id,kind,resource_id,segment,created_sequence)"
                    + " VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                build.scope().messageGroup(),
                build.id(),
                resource.kind(),
                resource.id(),
                segment(resource.createdSequence(), build.directorySegmentSpan()),
                resource.createdSequence());
        Long created =
                jdbc.queryForObject(
                        "SELECT created_sequence FROM ma_query.resource_directory WHERE scope_key=?"
                                + " AND build_id=? AND kind=? AND resource_id=?",
                        Long.class,
                        build.scope().messageGroup(),
                        build.id(),
                        resource.kind(),
                        resource.id());
        if (created == null || created != resource.createdSequence())
            throw new ManagedStateException(
                    "projection_content_conflict", "Resource creation identity differs");
    }

    @Override
    public boolean advanceBatch(Progress expected, List<Commit> commits) {
        SessionProjectionStore.validateCommits(expected, commits);
        var last = commits.get(commits.size() - 1);
        long through = last.through(), commitVersion = last.version();
        var build = expected.build();
        return jdbc.update(
                        "UPDATE ma_query.progress SET"
                            + " visible_sequence=?,commit_version=?,progress_version=progress_version+1"
                            + " WHERE scope_key=? AND build_id=? AND visible_sequence=? AND"
                            + " commit_version=? AND progress_version=?",
                        through,
                        commitVersion,
                        build.scope().messageGroup(),
                        build.id(),
                        expected.visibleSequence(),
                        expected.commitVersion(),
                        expected.version())
                == 1;
    }

    @Override
    public List<Event> events(Progress snapshot, long after, int limit) {
        validateSnapshot(snapshot);
        bounds(snapshot, after, limit);
        var b = snapshot.build();
        return jdbc.query(
                "SELECT data FROM ma_query.event_history WHERE scope_key=? AND build_id=? AND"
                        + " sequence>? AND sequence<=? ORDER BY sequence LIMIT ?",
                (row, index) -> parse(row.getString(1), Event.class),
                b.scope().messageGroup(),
                b.id(),
                after,
                snapshot.visibleSequence(),
                limit);
    }

    @Override
    public Resource resource(Progress snapshot, String kind, String id) {
        validateSnapshot(snapshot);
        var b = snapshot.build();
        var values =
                jdbc.query(
                        "SELECT data FROM ma_query.resource_version WHERE scope_key=? AND"
                            + " build_id=? AND kind=? AND resource_id=? AND sequence<=? ORDER BY"
                            + " sequence DESC LIMIT 1",
                        (row, index) -> parse(row.getString(1), Resource.class),
                        b.scope().messageGroup(),
                        b.id(),
                        kind,
                        id,
                        snapshot.visibleSequence());
        return values.isEmpty() ? null : values.get(0);
    }

    @Override
    public List<DirectoryEntry> directory(
            Progress snapshot, String kind, long afterCreated, String afterId, int limit) {
        validateSnapshot(snapshot);
        bounds(snapshot, afterCreated, limit);
        var b = snapshot.build();
        return jdbc.query(
                "SELECT created_sequence,resource_id FROM ma_query.resource_directory WHERE"
                        + " scope_key=? AND build_id=? AND kind=? AND created_sequence<=? AND"
                        + " (created_sequence>? OR (created_sequence=? AND resource_id>?)) ORDER BY"
                        + " created_sequence,resource_id LIMIT ?",
                (row, index) -> new DirectoryEntry(row.getLong(1), row.getString(2)),
                b.scope().messageGroup(),
                b.id(),
                kind,
                snapshot.visibleSequence(),
                afterCreated,
                afterCreated,
                afterId,
                limit);
    }

    private void validateSnapshot(Progress snapshot) {
        var current = progress(snapshot.build());
        if (snapshot.visibleSequence() > current.visibleSequence()
                || snapshot.commitVersion() > current.commitVersion()
                || snapshot.version() > current.version())
            throw new ManagedStateException(
                    "projection_snapshot_invalid", "Snapshot exceeds the published prefix");
    }

    private static long segment(long sequence, int span) {
        return (sequence - 1) / span;
    }

    private static void bounds(Progress snapshot, long after, int limit) {
        if (after < 0 || after > snapshot.visibleSequence() || limit < 1 || limit > 256)
            throw new IllegalArgumentException("Invalid query bounds");
    }

    private static void identical(String expected, String actual) {
        if (!expected.equals(actual))
            throw new ManagedStateException("projection_content_conflict", "Immutable row differs");
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid projection data", e);
        }
    }

    private <T> T parse(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new ManagedStateException(
                    "projection_content_invalid", "Cannot read projection row");
        }
    }
}
