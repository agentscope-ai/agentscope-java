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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable scoped files use the shared database; no live HarnessRuntime or Sandbox is needed for upload. */
public final class ManagedFiles {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final long SESSION_BYTES = 256L * 1024 * 1024;
    private static final long INPUT_BYTES = 32L * 1024 * 1024;
    private static final String COLUMNS = "file_id,name,media_type,size,sha256,created_at";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ManagedJournalStore journal;

    ManagedFiles(DataSource source, ManagedJournalStore journal) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        this.journal = journal;
    }

    public Map<String, Object> upload(
            ManagedScope scope,
            String actor,
            String key,
            String name,
            String mediaType,
            byte[] bytes) {
        ManagedScope.requireText(actor, "actor");
        ManagedScope.requireText(key, "idempotency key");
        if (key.length() > 256
                || name == null
                || name.isBlank()
                || name.length() > 512
                || name.contains("\r")
                || name.contains("\n"))
            throw failure("file_invalid", "Valid file name and Idempotency-Key are required");
        if (bytes.length > MAX_BYTES) throw failure("file_too_large", "File exceeds 16 MiB");
        String media;
        try {
            media = MediaType.parseMediaType(mediaType).toString();
        } catch (IllegalArgumentException invalid) {
            throw failure("file_invalid", "Invalid media type");
        }
        if (media.length() > 512)
            throw failure("file_invalid", "Media type exceeds 512 characters");
        String id = "file_" + ManagedScope.fingerprint(scope.messageGroup(), actor, key);
        String hash = sha256(bytes);
        long createdAt = journal.resourceTimestamp();
        var fact = new LinkedHashMap<String, Object>();
        fact.put("file_id", id);
        fact.put("name", name);
        fact.put("media_type", media);
        fact.put("size", (long) bytes.length);
        fact.put("sha256", hash);
        fact.put("created_at", createdAt);
        fact.put("download_url", "/v1/sessions/" + scope.sessionId() + "/files/" + id + "/content");
        return journal.prepareResourceEvent(
                scope,
                "file.created",
                fact,
                () ->
                        tx.execute(
                                ignored -> {
                                    var lifecycle =
                                            jdbc.queryForObject(
                                                    "SELECT lifecycle FROM ma.session WHERE"
                                                            + " scope_key=? FOR UPDATE",
                                                    String.class,
                                                    scope.messageGroup());
                                    var previous = find(scope, id);
                                    if (previous != null) {
                                        if (!name.equals(previous.get("name"))
                                                || !media.equals(previous.get("media_type"))
                                                || !hash.equals(previous.get("sha256")))
                                            throw failure(
                                                    "file_content_conflict",
                                                    "File key reused with different content or"
                                                            + " metadata");
                                        return previous;
                                    }
                                    ManagedCheckpointHolds.requireUnheld(jdbc, scope);
                                    if (!"active".equals(lifecycle))
                                        throw failure("session_inactive", "Session is not active");
                                    var quota =
                                            jdbc.queryForMap(
                                                    "SELECT COUNT(*) AS"
                                                        + " entries,COALESCE(SUM(size),0) AS bytes"
                                                        + " FROM ma.file WHERE scope_key=?",
                                                    scope.messageGroup());
                                    if (((Number) quota.get("entries")).longValue() >= 256
                                            || ((Number) quota.get("bytes")).longValue()
                                                            + bytes.length
                                                    > SESSION_BYTES)
                                        throw failure(
                                                "file_quota_exceeded",
                                                "Session file capacity reached");
                                    jdbc.update(
                                            "INSERT INTO"
                                                + " ma.file(scope_key,file_id,name,media_type,size,sha256,content,created_at)"
                                                + " VALUES(?,?,?,?,?,?,?,?)",
                                            scope.messageGroup(),
                                            id,
                                            name,
                                            media,
                                            bytes.length,
                                            hash,
                                            bytes,
                                            createdAt);
                                    var result = metadata(scope, id);
                                    journal.resourceEvent(
                                            scope, "uploaded:" + id, "file.created", result);
                                    return result;
                                }));
    }

    Map<String, Object> exportMetadata(
            ManagedScope scope, String key, String name, String media, byte[] content, long time) {
        String id =
                "file_"
                        + ManagedScope.fingerprint(
                                scope.messageGroup(), "managed-tool-worker", key);
        var existing = find(scope, id);
        if (existing != null) return existing;
        var data = new LinkedHashMap<String, Object>();
        data.put("file_id", id);
        data.put("name", name);
        data.put("media_type", MediaType.parseMediaType(media).toString());
        data.put("size", (long) content.length);
        data.put("sha256", sha256(content));
        data.put("created_at", time);
        data.put("download_url", "/v1/sessions/" + scope.sessionId() + "/files/" + id + "/content");
        return data;
    }

    public Map<String, Object> metadata(ManagedScope scope, String id) {
        var value = find(scope, id);
        if (value == null) throw failure("file_not_found", "File not found in this Session");
        return value;
    }

    private Map<String, Object> find(ManagedScope scope, String id) {
        if (id == null || !id.matches("file_[a-f0-9]{64}"))
            throw failure("file_not_found", "Invalid file ID");
        var rows =
                jdbc.query(
                        "SELECT " + COLUMNS + " FROM ma.file WHERE scope_key=? AND file_id=?",
                        (row, index) -> metadata(scope, row),
                        scope.messageGroup(),
                        id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public byte[] content(ManagedScope scope, String id) {
        var metadata = metadata(scope, id);
        byte[] bytes =
                jdbc.queryForObject(
                        "SELECT content FROM ma.file WHERE scope_key=? AND file_id=?",
                        byte[].class,
                        scope.messageGroup(),
                        id);
        if (bytes == null)
            throw failure(
                    "file_content_removed", "File content was removed during Session deletion");
        if (bytes.length != ((Number) metadata.get("size")).longValue()
                || !sha256(bytes).equals(metadata.get("sha256")))
            throw new ManagedTransportException(
                    "Stored file content differs from its immutable hash");
        return bytes;
    }

    public Map<String, Object> list(ManagedScope scope, int offset, int limit) {
        if (offset < 0 || offset > 256 || limit < 1 || limit > 100)
            throw failure("file_invalid", "Invalid file page");
        var values =
                jdbc.query(
                        "SELECT "
                                + COLUMNS
                                + " FROM ma.file WHERE scope_key=? ORDER BY file_id LIMIT ? OFFSET"
                                + " ?",
                        (row, index) -> metadata(scope, row),
                        scope.messageGroup(),
                        limit + 1,
                        offset);
        var page = values.subList(0, Math.min(limit, values.size()));
        return Map.of(
                "data",
                page,
                "next_offset",
                offset + page.size(),
                "has_more",
                values.size() > limit);
    }

    /** Metadata validation occurs before acceptance; bytes resolve only inside the HarnessRuntime. */
    public void validateInput(ManagedScope scope, Map<String, Object> input) {
        references(scope, input, false);
    }

    public Map<String, Object> resolveInput(ManagedScope scope, Map<String, Object> input) {
        return references(scope, input, true);
    }

    /** Native recovery keeps bytes; public resources carry a scoped immutable file reference. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> publicPayload(ManagedScope scope, Map<String, Object> payload) {
        return (Map<String, Object>) publicValue(scope, payload);
    }

    private Object publicValue(ManagedScope scope, Object value) {
        if (value instanceof List<?> list)
            return list.stream().map(item -> publicValue(scope, item)).toList();
        if (!(value instanceof Map<?, ?> map)) return value;
        if (Set.of("data", "image", "audio", "video").contains(String.valueOf(map.get("type")))
                && map.get("source") instanceof Map<?, ?> source
                && "base64".equals(source.get("type"))) {
            var reference = reference(scope, map, source);
            if (reference != null) return reference;
            var omitted = new LinkedHashMap<String, Object>();
            for (String field : List.of("type", "id", "name"))
                if (map.get(field) != null) omitted.put(field, map.get(field));
            if (source.get("media_type") instanceof String media) omitted.put("media_type", media);
            omitted.put("content_omitted", true);
            omitted.put("reason", "binary_content_private");
            return omitted;
        }
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> result.put(String.valueOf(key), publicValue(scope, item)));
        return result;
    }

    private Map<String, Object> reference(ManagedScope scope, Map<?, ?> block, Map<?, ?> source) {
        if (!(source.get("data") instanceof String encoded)
                || encoded.length() > (MAX_BYTES + 2L) / 3 * 4
                || !(source.get("media_type") instanceof String media)) return null;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        String id =
                block.get("id") instanceof String supplied && supplied.startsWith("file_")
                        ? supplied
                        : null;
        var parameters =
                new ArrayList<Object>(
                        List.of(scope.messageGroup(), sha256(bytes), media, bytes.length));
        String sql =
                "SELECT "
                        + COLUMNS
                        + " FROM ma.file WHERE scope_key=? AND sha256=? AND media_type=? AND"
                        + " size=?";
        if (id != null) {
            sql += " AND file_id=?";
            parameters.add(id);
        }
        sql += " ORDER BY file_id LIMIT 1";
        var rows = jdbc.query(sql, (row, index) -> metadata(scope, row), parameters.toArray());
        if (rows.isEmpty()) return null;
        var result = new LinkedHashMap<>(rows.get(0));
        result.remove("created_at");
        result.put("type", "file");
        return result;
    }

    private Map<String, Object> references(
            ManagedScope scope, Map<String, Object> input, boolean resolve) {
        if (!(input.get("input") instanceof List<?> messages)) return input;
        var result = new ArrayList<Map<String, Object>>();
        int references = 0;
        long total = 0;
        for (Object raw : messages) {
            var message = new LinkedHashMap<>(map(raw));
            var blocks = new ArrayList<Map<String, Object>>();
            for (Object rawBlock : (List<?>) message.get("content")) {
                var block = map(rawBlock);
                if (!"file".equals(block.get("type"))) {
                    blocks.add(block);
                    continue;
                }
                if (!Set.of("type", "file_id").equals(block.keySet()) || ++references > 128)
                    throw failure(
                            "file_invalid",
                            "File references need only type/file_id, at most 128 per input");
                String id = String.valueOf(block.get("file_id"));
                var file = metadata(scope, id);
                total += ((Number) file.get("size")).longValue();
                if (total > INPUT_BYTES)
                    throw failure("file_too_large", "Input file content exceeds 32 MiB");
                if (!resolve) {
                    blocks.add(block);
                    continue;
                }
                String media = (String) file.get("media_type");
                String kind =
                        media.startsWith("image/")
                                ? "image"
                                : media.startsWith("audio/")
                                        ? "audio"
                                        : media.startsWith("video/") ? "video" : "data";
                var resolved = new LinkedHashMap<String, Object>();
                resolved.put("type", kind);
                resolved.put(
                        "source",
                        Map.of(
                                "type",
                                "base64",
                                "media_type",
                                media,
                                "data",
                                Base64.getEncoder().encodeToString(content(scope, id))));
                if ("data".equals(kind)) {
                    resolved.put("id", id);
                    resolved.put("name", file.get("name"));
                }
                blocks.add(resolved);
            }
            message.put("content", blocks);
            result.add(message);
        }
        return resolve ? Map.of("input", result) : input;
    }

    public void discardDeletedContent(ManagedScope scope) {
        jdbc.update(
                "UPDATE ma.file SET content=NULL WHERE scope_key=? AND EXISTS (SELECT 1 FROM"
                        + " ma.session WHERE scope_key=? AND lifecycle='deleted')",
                scope.messageGroup(),
                scope.messageGroup());
    }

    private static Map<String, Object> metadata(ManagedScope scope, ResultSet row)
            throws SQLException {
        var result = new LinkedHashMap<String, Object>();
        result.put("file_id", row.getString("file_id"));
        result.put("name", row.getString("name"));
        result.put("media_type", row.getString("media_type"));
        result.put("size", row.getLong("size"));
        result.put("sha256", row.getString("sha256"));
        result.put("created_at", row.getLong("created_at"));
        result.put(
                "download_url",
                "/v1/sessions/"
                        + scope.sessionId()
                        + "/files/"
                        + row.getString("file_id")
                        + "/content");
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ManagedStateException failure(String code, String message) {
        return new ManagedStateException(code, message);
    }
}
