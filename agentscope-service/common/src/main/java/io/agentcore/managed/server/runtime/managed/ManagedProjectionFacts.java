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
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One ordered manifest per execution transaction; payloads are already immutable objects. */
final class ManagedProjectionFacts {
    record Entry(
            long sequence,
            String source,
            String type,
            String hash,
            long createdAt,
            String bundle,
            int ordinal) {}

    record Manifest(
            long version,
            long first,
            long through,
            Map<String, RuntimeObjectStore.Reference> objects,
            List<Entry> entries,
            Map<String, String> inline) {}

    record Page(long version, long through, List<ManagedSessionProjections.Fact> facts) {}

    record Attached(String manifest, long version, long through) {}

    Attached attach(ManagedScope scope) {
        var values =
                (Map<String, Buffer>) TransactionSynchronizationManager.getResource(resourceKey);
        var buffer = values == null ? null : values.get(scope.messageGroup());
        if (buffer == null || buffer.entries.isEmpty()) return null;
        buffer.attached = true;
        return new Attached(
                encode(buffer.manifest()), buffer.version, buffer.initial + buffer.entries.size());
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ManagedFactBundles bundles;
    private final Object resourceKey;

    ManagedProjectionFacts(DataSource source, ObjectMapper mapper, ManagedFactBundles bundles) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.bundles = bundles;
        resourceKey = List.of(ManagedProjectionFacts.class, source);
    }

    /** Resolve after capturing every public fact. Private-only commits do not create a version. */
    @SuppressWarnings("unchecked")
    long boundary(ManagedScope scope) {
        var values =
                (Map<String, Buffer>) TransactionSynchronizationManager.getResource(resourceKey);
        var value = values == null ? null : values.get(scope.messageGroup());
        return value == null
                ? acceptedVersion(scope)
                : value.entries.isEmpty() ? value.version - 1 : value.version;
    }

    private final class Buffer {
        final ManagedScope scope;
        final long initial;
        final long version;
        final Map<String, Entry> entries = new LinkedHashMap<>();
        final Map<String, RuntimeObjectStore.Reference> references = new LinkedHashMap<>();
        final Map<String, String> inline = new LinkedHashMap<>();
        boolean attached;

        Buffer(ManagedScope scope, Map<String, Object> snapshot) {
            this.scope = scope;
            var row =
                    snapshot != null
                            ? snapshot
                            : jdbc.queryForMap(
                                    "SELECT accepted_sequence,commit_version FROM ma.session"
                                            + " WHERE scope_key=? FOR UPDATE",
                                    scope.messageGroup());
            initial = ((Number) row.get("accepted_sequence")).longValue();
            version = ((Number) row.get("commit_version")).longValue() + 1;
        }

        Manifest manifest() {
            var manifest =
                    new Manifest(
                            version,
                            initial + 1,
                            initial + entries.size(),
                            Map.copyOf(references),
                            List.copyOf(entries.values()),
                            Map.copyOf(inline));
            if (deliveryBytes(manifest) > 64L * 1024 * 1024 || entries.size() > 4096)
                throw new IllegalArgumentException("Projection boundary exceeds byte/fact budget");
            // Reserve wire space for object locators materialized from command payloads.
            var metadata =
                    new Manifest(
                            version,
                            manifest.first(),
                            manifest.through(),
                            manifest.objects(),
                            manifest.entries(),
                            Map.of());
            if (encode(metadata).getBytes(StandardCharsets.UTF_8).length > 384 * 1024)
                throw new IllegalArgumentException("Projection manifest exceeds message budget");
            return manifest;
        }

        void flush() {
            if (entries.isEmpty()) return;
            if (!attached) {
                String manifest = encode(manifest());
                String hash = ManagedScope.fingerprint(manifest);
                String id = "public_" + hash;
                jdbc.update(
                        "INSERT INTO"
                            + " ma.runtime_commit(scope_key,commit_id,content_hash,receipt,projection_version,public_commit_version,projection_manifest,publication_state,next_attempt_at)"
                            + " VALUES(?,?,?,?,?,?,?,'pending',?)",
                        scope.messageGroup(),
                        id,
                        hash,
                        "{}",
                        version,
                        version,
                        manifest,
                        System.currentTimeMillis());
                jdbc.update(
                        "UPDATE ma.session SET accepted_sequence=?,commit_version=? WHERE"
                                + " scope_key=?",
                        initial + entries.size(),
                        version,
                        scope.messageGroup());
            }
            ManagedProjectionSignals.committed(jdbc.getDataSource());
        }
    }

    @SuppressWarnings("unchecked")
    private Buffer buffer(ManagedScope scope) {
        return buffer(scope, null);
    }

    void lockedHead(ManagedScope scope, Map<String, Object> snapshot) {
        buffer(scope, snapshot);
    }

    @SuppressWarnings("unchecked")
    private Buffer buffer(ManagedScope scope, Map<String, Object> snapshot) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            throw new IllegalStateException("Projection facts require the execution transaction");
        var values =
                (Map<String, Buffer>) TransactionSynchronizationManager.getResource(resourceKey);
        if (values == null) {
            values = new LinkedHashMap<>();
            var registered = values;
            TransactionSynchronizationManager.bindResource(resourceKey, values);
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            registered.values().forEach(Buffer::flush);
                        }

                        @Override
                        public void afterCompletion(int status) {
                            TransactionSynchronizationManager.unbindResourceIfPossible(resourceKey);
                        }

                        @Override
                        public void suspend() {
                            TransactionSynchronizationManager.unbindResourceIfPossible(resourceKey);
                        }

                        @Override
                        public void resume() {
                            TransactionSynchronizationManager.bindResource(resourceKey, registered);
                        }
                    });
        }
        return values.computeIfAbsent(scope.messageGroup(), ignored -> new Buffer(scope, snapshot));
    }

    long capture(
            ManagedScope scope,
            String id,
            String type,
            String hash,
            String semantic,
            long now,
            ManagedFactBundles.Prepared staged) {
        var buffer = buffer(scope);
        // Shared tool completion is a business identity crossing runtime and tool-result commits.
        if (semantic != null) {
            var existing =
                    jdbc.queryForList(
                            "SELECT semantic_hash FROM ma.projection_tool_identity WHERE"
                                    + " scope_key=? AND source_id=?",
                            String.class,
                            scope.messageGroup(),
                            id);
            if (!existing.isEmpty()) {
                if (!semantic.equals(existing.get(0)))
                    throw new ManagedStateException(
                            "projection_conflict", "Tool result identity content differs");
                return -1;
            }
            jdbc.update(
                    "INSERT INTO ma.projection_tool_identity(scope_key,source_id,semantic_hash)"
                            + " VALUES(?,?,?)",
                    scope.messageGroup(),
                    id,
                    semantic);
        }
        var previous = buffer.entries.get(id);
        if (previous != null) {
            if (!previous.hash().equals(hash))
                throw new ManagedStateException(
                        "projection_conflict", "Source identity content differs");
            return -1;
        }
        if (buffer.entries.size() >= 4096)
            throw new IllegalArgumentException("Projection boundary exceeds fact limit");
        long next = buffer.initial + buffer.entries.size() + 1;
        var reference = staged.reference();
        buffer.references.putIfAbsent(reference.key(), reference);
        buffer.entries.put(
                id, new Entry(next, id, type, hash, now, reference.key(), staged.ordinal()));
        return next;
    }

    static void protocolCommand(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ManagedScope scope,
            long commandSequence,
            String payload) {
        var facts = new ManagedProjectionFacts(jdbc.getDataSource(), mapper, null);
        var buffer = facts.buffer(scope);
        String id = "command_" + commandSequence;
        buffer.inline.put(id, payload);
        buffer.entries.put(
                id,
                new Entry(
                        buffer.initial + buffer.entries.size() + 1,
                        id,
                        "protocol.event",
                        ManagedScope.fingerprint("protocol.event", payload),
                        System.currentTimeMillis(),
                        "inline:" + id,
                        0));
    }

    static void catalog(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ManagedScope scope,
            String turn,
            String id,
            String catalog) {
        var facts = new ManagedProjectionFacts(jdbc.getDataSource(), mapper, null);
        var buffer = facts.buffer(scope);
        String payload = catalogPayload(facts.mapper, turn, catalog);
        String source = "catalog_" + ManagedScope.fingerprint(turn, id);
        buffer.inline.put(source, payload);
        buffer.entries.put(
                source,
                new Entry(
                        buffer.initial + buffer.entries.size() + 1,
                        source,
                        "protocol.catalog",
                        ManagedScope.fingerprint("protocol.catalog", payload),
                        System.currentTimeMillis(),
                        "inline:" + source,
                        0));
    }

    private static String catalogPayload(ObjectMapper mapper, String turn, String catalog) {
        try {
            var tools = new ArrayList<Map<String, String>>();
            for (var tool : mapper.readTree(catalog).path("tools"))
                tools.add(
                        Map.of(
                                "name",
                                tool.path("name").asText(),
                                "server",
                                tool.path("server").asText(),
                                "remoteName",
                                tool.path("remoteName").asText()));
            return mapper.writeValueAsString(Map.of("turn_id", turn, "tools", tools));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid catalog reference", invalid);
        }
    }

    long acceptedVersion(ManagedScope scope) {
        return jdbc.queryForObject(
                "SELECT commit_version FROM ma.session WHERE scope_key=?",
                Long.class,
                scope.messageGroup());
    }

    List<ManagedProjectionNotice.Commit> commits(ManagedScope scope, long after, int limit) {
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("Invalid rebuild batch");
        String sql =
                "SELECT commit_id,projection_manifest FROM ma.runtime_commit WHERE scope_key=? AND"
                        + " public_commit_version>? ORDER BY public_commit_version LIMIT ?";
        var rows = jdbc.queryForList(sql, scope.messageGroup(), after, Math.min(limit, 32));
        if (rows.isEmpty() && acceptedVersion(scope) > after) {
            // A concurrently committed head may follow the first range snapshot.
            rows = jdbc.queryForList(sql, scope.messageGroup(), after, Math.min(limit, 32));
            if (rows.isEmpty())
                throw new ManagedStateException("projection_gap", "Committed manifest missing");
        }
        var result = new ArrayList<ManagedProjectionNotice.Commit>();
        long bytes = 0;
        for (var row : rows) {
            try {
                var m = mapper.readValue((String) row.get("projection_manifest"), Manifest.class);
                if (m.version() != after + result.size() + 1)
                    throw new ManagedStateException("projection_gap", "Committed version missing");
                long size = deliveryBytes(m);
                if (!result.isEmpty() && bytes + size > 64L * 1024 * 1024) break;
                result.add(
                        new ManagedProjectionNotice.Commit(
                                (String) row.get("commit_id"), identity(m), m));
                bytes += size;
            } catch (IOException invalid) {
                throw new IllegalArgumentException("Invalid manifest", invalid);
            }
        }
        return List.copyOf(result);
    }

    static long deliveryBytes(Manifest manifest) {
        long size = 0;
        for (var ref : manifest.objects().values()) size = Math.addExact(size, ref.size());
        for (var text : manifest.inline().values())
            size = Math.addExact(size, text.getBytes(StandardCharsets.UTF_8).length);
        return size;
    }

    Page next(ManagedScope scope, long after) {
        var pages = next(scope, after, 1);
        return pages.isEmpty() ? null : pages.get(0);
    }

    List<Page> next(ManagedScope scope, long after, int limit) {
        if (limit < 1 || limit > 128)
            throw new IllegalArgumentException("Manifest batch must be 1..128");
        String sql =
                "SELECT projection_manifest FROM ma.runtime_commit WHERE scope_key=? AND"
                        + " public_commit_version>? ORDER BY public_commit_version LIMIT ?";
        var rows = jdbc.queryForList(sql, String.class, scope.messageGroup(), after, limit);
        if (rows.isEmpty()) {
            if (acceptedVersion(scope) <= after) return List.of();
            // READ COMMITTED may observe a new Head after the earlier empty range read.
            // Its manifest was committed atomically with that Head; re-read before declaring loss.
            rows = jdbc.queryForList(sql, String.class, scope.messageGroup(), after, limit);
            if (rows.isEmpty())
                throw new ManagedStateException("projection_gap", "Committed manifest missing");
        }
        var pages = new ArrayList<Page>();
        long bytes = 0, version = after, through = -1;
        for (var row : rows) {
            Manifest manifest;
            try {
                manifest = mapper.readValue(row, Manifest.class);
            } catch (Exception e) {
                throw new ManagedStateException("projection_object_invalid", "Invalid manifest");
            }
            if (manifest.version() != version + 1
                    || manifest.through() != manifest.first() - 1 + manifest.entries().size()
                    || (through >= 0 && manifest.first() != through + 1))
                throw new ManagedStateException("projection_gap", "Manifest range differs");
            long size = row.getBytes(StandardCharsets.UTF_8).length;
            for (var ref : manifest.objects().values()) size = Math.addExact(size, ref.size());
            if (!pages.isEmpty() && bytes + size > 64L * 1024 * 1024) break;
            bytes += size;
            pages.add(read(scope, manifest));
            version = manifest.version();
            through = manifest.through();
        }
        return List.copyOf(pages);
    }

    Page read(ManagedScope scope, Manifest manifest) {
        return read(scope, manifest, new LinkedHashMap<>());
    }

    Page read(
            ManagedScope scope,
            Manifest manifest,
            Map<RuntimeObjectStore.Reference, ManagedFactBundles.Bundle> cache) {
        if (manifest.version() < 1
                || manifest.first() < 1
                || manifest.entries().isEmpty()
                || manifest.entries().size() > 4096
                || manifest.through() != manifest.first() - 1 + manifest.entries().size())
            throw new ManagedStateException("projection_object_invalid", "Invalid fact range");
        var packages = new LinkedHashMap<String, ManagedFactBundles.Bundle>();
        long bytes = 0;
        for (var ref : manifest.objects().values()) {
            bytes += ref.size();
            if (bytes > 64L * 1024 * 1024)
                throw new ManagedStateException(
                        "projection_object_invalid", "Fact boundary exceeds byte budget");
            packages.put(
                    ref.key(), cache.computeIfAbsent(ref, ignored -> bundles.read(scope, ref)));
        }
        var facts = new ArrayList<ManagedSessionProjections.Fact>();
        long seq = manifest.first();
        for (var entry : manifest.entries()) {
            if (entry.bundle().startsWith("inline:")) {
                var payload = manifest.inline().get(entry.source());
                if (payload == null
                        || entry.sequence() != seq++
                        || !ManagedScope.fingerprint(entry.type(), payload).equals(entry.hash()))
                    throw new ManagedStateException(
                            "projection_object_invalid", "Inline fact differs");
                facts.add(
                        new ManagedSessionProjections.Fact(
                                entry.sequence(),
                                entry.source(),
                                entry.type(),
                                payload,
                                entry.hash(),
                                entry.createdAt()));
                continue;
            }
            var pack = packages.get(entry.bundle());
            if (entry.sequence() != seq++
                    || pack == null
                    || entry.ordinal() < 0
                    || entry.ordinal() >= pack.entries().size())
                throw new ManagedStateException(
                        "projection_object_invalid", "Manifest entry differs");
            var content = pack.entries().get(entry.ordinal());
            if (!content.type().equals(entry.type()) || !content.hash().equals(entry.hash()))
                throw new ManagedStateException(
                        "projection_object_invalid", "Fact identity differs");
            facts.add(
                    new ManagedSessionProjections.Fact(
                            entry.sequence(),
                            entry.source(),
                            entry.type(),
                            content.payload(),
                            entry.hash(),
                            entry.createdAt()));
        }
        return new Page(manifest.version(), manifest.through(), List.copyOf(facts));
    }

    static String identity(Manifest manifest) {
        var parts = new ArrayList<String>();
        parts.add(Long.toString(manifest.version()));
        parts.add(Long.toString(manifest.first()));
        parts.add(Long.toString(manifest.through()));
        for (var e : manifest.entries())
            parts.add(
                    ManagedScope.fingerprint(
                            Long.toString(e.sequence()),
                            e.source(),
                            e.type(),
                            e.hash(),
                            Long.toString(e.createdAt())));
        return ManagedScope.fingerprint(parts.toArray(String[]::new));
    }

    Manifest prepareDelivery(ManagedScope scope, Manifest manifest) {
        if (manifest.inline().isEmpty()) return manifest;
        var contents = new ArrayList<ManagedFactBundles.Content>();
        for (var e : manifest.entries())
            if (e.bundle().startsWith("inline:"))
                contents.add(
                        new ManagedFactBundles.Content(
                                e.type(), manifest.inline().get(e.source())));
        var staged = bundles.stage(scope, contents);
        var refs = new LinkedHashMap<>(manifest.objects());
        var entries = new ArrayList<Entry>();
        for (var e : manifest.entries()) {
            if (!e.bundle().startsWith("inline:")) {
                entries.add(e);
                continue;
            }
            var value = staged.get(e.hash());
            refs.put(value.reference().key(), value.reference());
            entries.add(
                    new Entry(
                            e.sequence(),
                            e.source(),
                            e.type(),
                            e.hash(),
                            e.createdAt(),
                            value.reference().key(),
                            value.ordinal()));
        }
        return new Manifest(
                manifest.version(),
                manifest.first(),
                manifest.through(),
                Map.copyOf(refs),
                List.copyOf(entries),
                Map.of());
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot encode manifest", e);
        }
    }
}
