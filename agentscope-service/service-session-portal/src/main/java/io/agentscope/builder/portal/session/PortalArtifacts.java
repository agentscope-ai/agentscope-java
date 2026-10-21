/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.portal.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.portal.persistence.PortalArtifactPublicationEntity;
import io.agentscope.builder.portal.persistence.PortalArtifactPublicationRepository;
import io.agentscope.builder.portal.persistence.PortalEventEntity;
import io.agentscope.builder.portal.persistence.PortalEventRepository;
import io.agentscope.builder.portal.persistence.PortalResourceRepository;
import io.agentscope.builder.portal.persistence.PortalSessionEntity;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.runtime.protocol.RuntimePayloadDigest;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Explicit public deliverables belong to Session Portal; publishing never changes the runtime journal. */
@Service
public final class PortalArtifacts {
    private final PortalSessionRepository sessions;
    private final PortalArtifactPublicationRepository publications;
    private final PortalEventRepository events;
    private final PortalResourceRepository resources;
    private final PortalSessionAccess access;
    private final PortalSessionFiles files;
    private final PortalResourceProjection projection;
    private final PortalEventNotifications notifications;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public PortalArtifacts(
            PortalSessionRepository sessions,
            PortalArtifactPublicationRepository publications,
            PortalEventRepository events,
            PortalResourceRepository resources,
            PortalSessionAccess access,
            PortalSessionFiles files,
            PortalResourceProjection projection,
            PortalEventNotifications notifications,
            TransactionTemplate tx,
            ObjectMapper mapper) {
        this.sessions = sessions;
        this.publications = publications;
        this.events = events;
        this.resources = resources;
        this.access = access;
        this.files = files;
        this.projection = projection;
        this.notifications = notifications;
        this.tx = tx;
        this.mapper = mapper;
    }

    public Map<String, Object> publish(
            String sessionId, HttpHeaders headers, Map<String, Object> input) {
        var actor = access.authorize(sessionId, headers, "interact");
        String key = headers.getFirst("Idempotency-Key");
        if (key == null || key.isBlank() || key.length() > 256)
            throw invalid("A valid Idempotency-Key is required");
        var selected = sessions.findById(sessionId).orElseThrow();
        String principal =
                String.valueOf(actor.get("key_hash")).isBlank()
                        ? "user:" + actor.get("user")
                        : "application:" + selected.applicationId;
        String id =
                RuntimePayloadDigest.of(
                        mapper,
                        Map.of(
                                "session",
                                sessionId,
                                "principal",
                                principal,
                                "kind",
                                "artifact_publication",
                                "key",
                                key));
        String digest = RuntimePayloadDigest.of(mapper, input);
        var existing = publications.findById(id);
        if (existing.isPresent()) return replay(existing.get(), digest);
        var artifact = prepare(selected, input);
        artifact.put("artifact_id", "artifact_" + id);
        artifact.put("status", "published");
        return tx.execute(
                ignored -> {
                    var session = sessions.lockById(sessionId).orElseThrow();
                    access.authorize(sessionId, headers, "interact");
                    var prior = publications.findById(id);
                    if (prior.isPresent()) return replay(prior.get(), digest);
                    if (!"ready".equals(session.state) || !"active".equals(session.lifecycle))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Session is not ready and active");
                    var receipt = new PortalArtifactPublicationEntity();
                    receipt.id = id;
                    receipt.sessionId = sessionId;
                    receipt.inputDigest = digest;
                    receipt.data = json(artifact);
                    receipt.createdAt = System.currentTimeMillis();
                    publications.saveAndFlush(receipt);
                    append(session, null, "artifact.published", id, artifact);
                    return artifact;
                });
    }

    private Map<String, Object> prepare(PortalSessionEntity session, Map<String, Object> input) {
        if (!Set.of("name", "uri", "media_type", "sha256", "file_id").containsAll(input.keySet()))
            throw invalid("Unknown artifact fields");
        if (input.containsKey("file_id")) {
            if (input.size() != 1 || !(input.get("file_id") instanceof String id) || id.isBlank())
                throw invalid("Supply file_id alone or an external URI");
            var file = files.metadata(session, id).block(Duration.ofSeconds(6));
            if (file == null)
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "File metadata unavailable");
            var result = new LinkedHashMap<String, Object>();
            for (String name : Set.of("file_id", "name", "media_type", "sha256"))
                result.put(name, file.get(name));
            result.put("uri", "/api/v1/agent-sessions/" + session.id + "/files/" + id + "/content");
            return result;
        }
        if (!(input.get("name") instanceof String name)
                || name.isBlank()
                || name.length() > 512
                || !(input.get("uri") instanceof String uri)
                || uri.length() > 4096) throw invalid("Valid artifact name and URI are required");
        try {
            var parsed = URI.create(uri);
            if (!"https".equalsIgnoreCase(parsed.getScheme())
                    || parsed.getHost() == null
                    || parsed.getUserInfo() != null)
                throw invalid("Artifact URI requires HTTPS without embedded credentials");
            String media =
                    MediaType.parseMediaType(
                                    String.valueOf(
                                            input.getOrDefault(
                                                    "media_type", "application/octet-stream")))
                            .toString();
            Object sha = input.getOrDefault("sha256", "");
            if (!(sha instanceof String hash)
                    || !hash.isEmpty() && !hash.matches("[0-9a-fA-F]{64}"))
                throw invalid("Invalid SHA-256");
            return new LinkedHashMap<>(
                    Map.of(
                            "name",
                            name,
                            "uri",
                            parsed.toString(),
                            "media_type",
                            media,
                            "sha256",
                            hash));
        } catch (IllegalArgumentException invalid) {
            throw invalid("Invalid artifact URI or media type");
        }
    }

    public void delete(String sessionId, String id, HttpHeaders headers) {
        access.authorize(sessionId, headers, "interact");
        if (id == null || id.isBlank() || id.length() > 512) throw invalid("Invalid artifact ID");
        tx.executeWithoutResult(
                ignored -> {
                    var session = sessions.lockById(sessionId).orElseThrow();
                    access.authorize(sessionId, headers, "interact");
                    String receipt =
                            RuntimePayloadDigest.of(
                                    mapper,
                                    Map.of(
                                            "session",
                                            sessionId,
                                            "artifact",
                                            id,
                                            "kind",
                                            "artifact_delete"));
                    if (events.existsById(receipt)) return;
                    if (!"ready".equals(session.state) || !"active".equals(session.lifecycle))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Session is not ready and active");
                    var resource =
                            resources
                                    .findById(projection.resourceId(sessionId, "artifacts", id))
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND));
                    append(
                            session,
                            resource.turnId,
                            "artifact.deleted",
                            receipt,
                            Map.of("artifact_id", id, "status", "deleted"));
                });
    }

    private void append(
            PortalSessionEntity session,
            String turn,
            String type,
            String id,
            Map<String, Object> data) {
        var event = new PortalEventEntity();
        event.id =
                "artifact.deleted".equals(type)
                        ? id
                        : RuntimePayloadDigest.of(mapper, Map.of("artifact_publication", id));
        event.sessionId = session.id;
        event.turnId = turn;
        event.seq = ++session.nextEventSequence;
        event.createdAt = System.currentTimeMillis();
        event.type = type;
        var payload = new LinkedHashMap<String, Object>(data);
        if (turn != null) payload.put("turn_id", turn);
        event.data = json(payload);
        events.saveAndFlush(event);
        projection.apply(session, event);
        sessions.saveAndFlush(session);
        notifications.committed(session.id);
    }

    private Map<String, Object> replay(PortalArtifactPublicationEntity prior, String digest) {
        if (!prior.inputDigest.equals(digest))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Artifact idempotency key conflicts");
        return projection.read(prior.data);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode public artifact", error);
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
