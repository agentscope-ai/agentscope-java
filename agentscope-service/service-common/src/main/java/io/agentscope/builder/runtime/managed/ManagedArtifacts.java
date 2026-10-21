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
package io.agentscope.builder.runtime.managed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit deliverables share the public cursor and snapshot projection with Hands events. */
public final class ManagedArtifacts {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate captureTx;
    private final ObjectMapper mapper;
    private final ManagedJournalStore journal;

    ManagedArtifacts(DataSource source, ObjectMapper mapper, ManagedJournalStore journal) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        captureTx = new TransactionTemplate(new DataSourceTransactionManager(source));
        captureTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        captureTx.setTimeout(10);
        this.mapper =
                mapper.copy()
                        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                        .enable(DeserializationFeature.USE_LONG_FOR_INTS);
        this.journal = journal;
    }

    /** Called within the tool event transaction; a rejected export must still settle its tool. */
    Map<String, Object> capture(
            ManagedScope scope, ManagedJournalStore.ToolTask task, String result) {
        try {
            return captureTx.execute(
                    ignored -> {
                        var exported = parse(result);
                        var arguments = parse(task.arguments());
                        if (!(exported.get("content") instanceof String encoded)
                                || encoded.length() > (ManagedFiles.MAX_BYTES + 2L) / 3 * 4
                                || !(exported.get("sha256") instanceof String hash))
                            throw new IllegalArgumentException("Invalid Sandbox export");
                        byte[] content = Base64.getDecoder().decode(encoded);
                        if (!ManagedFiles.sha256(content).equals(hash)
                                || !(exported.get("size") instanceof Number size)
                                || size.longValue() != content.length)
                            throw new IllegalArgumentException("Sandbox export integrity mismatch");
                        String key = "export:" + task.executionId();
                        var file =
                                journal.files()
                                        .upload(
                                                scope,
                                                "managed-hands",
                                                key,
                                                (String) arguments.get("name"),
                                                (String) arguments.get("media_type"),
                                                content);
                        return publish(
                                scope,
                                "managed-hands",
                                key,
                                Map.of("file_id", file.get("file_id"), "turn_id", task.turnId()));
                    });
        } catch (ManagedStateException rejected) {
            return Map.of("error", rejected.code(), "message", rejected.getMessage());
        } catch (IllegalArgumentException rejected) {
            return Map.of("error", "artifact_export_invalid", "message", rejected.getMessage());
        }
    }

    public Map<String, Object> publish(
            ManagedScope scope, String actor, String key, Map<String, Object> input) {
        ManagedScope.requireText(actor, "actor");
        ManagedScope.requireText(key, "idempotency key");
        if (key.length() > 256)
            throw new IllegalArgumentException("Idempotency-Key exceeds 256 characters");
        String id = "artifact_" + ManagedScope.fingerprint(scope.messageGroup(), actor, key);
        String digest = ManagedScope.fingerprint(json(input));
        return tx.execute(
                ignored -> {
                    String lifecycle = lock(scope);
                    var prior =
                            jdbc.queryForList(
                                    "SELECT input_digest,data FROM ma.artifact WHERE scope_key=?"
                                            + " AND artifact_id=?",
                                    scope.messageGroup(),
                                    id);
                    if (!prior.isEmpty()) {
                        if (!digest.equals(prior.get(0).get("input_digest")))
                            throw new ManagedStateException(
                                    "artifact_content_conflict",
                                    "Artifact idempotency content differs");
                        return parse((String) prior.get(0).get("data"));
                    }
                    ManagedCheckpointOperations.requireUnheld(jdbc, scope);
                    active(lifecycle);
                    if (jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ma.artifact WHERE scope_key=?",
                                    Long.class,
                                    scope.messageGroup())
                            >= 256)
                        throw new ManagedStateException(
                                "artifact_quota_exceeded", "Session artifact capacity reached");
                    var data = prepare(scope, input);
                    data.put("artifact_id", id);
                    data.put("status", "published");
                    data.put("created_at", System.currentTimeMillis());
                    if (data.containsKey("file_id"))
                        data.put(
                                "download_url",
                                "/v1/sessions/"
                                        + scope.sessionId()
                                        + "/artifacts/"
                                        + id
                                        + "/content");
                    jdbc.update(
                            "INSERT INTO"
                                    + " ma.artifact(scope_key,artifact_id,input_digest,status,data)"
                                    + " VALUES(?,?,?,'published',?)",
                            scope.messageGroup(),
                            id,
                            digest,
                            json(data));
                    journal.resourceEvent(scope, "published:" + id, "artifact.published", data);
                    return data;
                });
    }

    public Map<String, Object> metadata(ManagedScope scope, String id, String snapshot) {
        var event = journal.resources().resource(scope, snapshot, "artifacts", id);
        return mapper.convertValue(event.get("data"), new TypeReference<>() {});
    }

    public void delete(ManagedScope scope, String id) {
        tx.executeWithoutResult(
                ignored -> {
                    String lifecycle = lock(scope);
                    var prior =
                            jdbc.queryForList(
                                    "SELECT status,data FROM ma.artifact WHERE scope_key=? AND"
                                            + " artifact_id=?",
                                    scope.messageGroup(),
                                    id);
                    if (prior.isEmpty())
                        throw new ManagedStateException(
                                "artifact_not_found", "Unknown published artifact");
                    if ("deleted".equals(prior.get(0).get("status"))) return;
                    ManagedCheckpointOperations.requireUnheld(jdbc, scope);
                    active(lifecycle);
                    var data = parse((String) prior.get(0).get("data"));
                    data.put("status", "deleted");
                    jdbc.update(
                            "UPDATE ma.artifact SET status='deleted',data=? WHERE scope_key=? AND"
                                    + " artifact_id=?",
                            json(data),
                            scope.messageGroup(),
                            id);
                    journal.resourceEvent(
                            scope,
                            "deleted:" + id,
                            "artifact.deleted",
                            Map.of("artifact_id", id, "status", "deleted"));
                });
    }

    private Map<String, Object> prepare(ManagedScope scope, Map<String, Object> input) {
        if (!Set.of("file_id", "turn_id", "name", "uri", "media_type", "sha256")
                .containsAll(input.keySet()))
            throw new IllegalArgumentException("Unknown artifact fields");
        var result = new LinkedHashMap<String, Object>();
        if (input.containsKey("file_id")) {
            if (!(input.get("file_id") instanceof String id)
                    || input.keySet().stream()
                            .anyMatch(field -> !Set.of("file_id", "turn_id").contains(field)))
                throw new IllegalArgumentException("Supply file_id alone, with optional turn_id");
            var file = journal.files().metadata(scope, id);
            for (String field : Set.of("file_id", "name", "media_type", "sha256", "size"))
                result.put(field, file.get(field));
            result.put("uri", file.get("download_url"));
        } else {
            if (!(input.get("name") instanceof String name)
                    || name.isBlank()
                    || name.length() > 512
                    || !(input.get("uri") instanceof String uri)
                    || uri.length() > 4096)
                throw new IllegalArgumentException(
                        "Valid artifact name and HTTPS URI are required");
            var parsed = URI.create(uri);
            if (!"https".equalsIgnoreCase(parsed.getScheme())
                    || parsed.getHost() == null
                    || parsed.getUserInfo() != null)
                throw new IllegalArgumentException("Artifact URI needs HTTPS without credentials");
            String media =
                    MediaType.parseMediaType(
                                    String.valueOf(
                                            input.getOrDefault(
                                                    "media_type", "application/octet-stream")))
                            .toString();
            String hash = String.valueOf(input.getOrDefault("sha256", ""));
            if (!hash.isEmpty() && !hash.matches("[a-fA-F0-9]{64}"))
                throw new IllegalArgumentException("Invalid SHA-256");
            result.putAll(
                    Map.of(
                            "name",
                            name,
                            "uri",
                            parsed.toString(),
                            "media_type",
                            media,
                            "sha256",
                            hash));
        }
        if (input.containsKey("turn_id")) {
            if (!(input.get("turn_id") instanceof String turn)
                    || jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ma.turn WHERE scope_key=? AND turn_id=?",
                                    Long.class,
                                    scope.messageGroup(),
                                    turn)
                            != 1)
                throw new ManagedStateException(
                        "turn_not_found", "Artifact Turn does not belong to this Session");
            result.put("turn_id", turn);
        }
        return result;
    }

    private String lock(ManagedScope scope) {
        return jdbc.queryForObject(
                "SELECT lifecycle FROM ma.session WHERE scope_key=? FOR UPDATE",
                String.class,
                scope.messageGroup());
    }

    private static void active(String lifecycle) {
        if (!"active".equals(lifecycle))
            throw new ManagedStateException("session_inactive", "Session is not active");
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Cannot encode artifact", invalid);
        }
    }

    private Map<String, Object> parse(String value) {
        try {
            return mapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid stored artifact", invalid);
        }
    }
}
