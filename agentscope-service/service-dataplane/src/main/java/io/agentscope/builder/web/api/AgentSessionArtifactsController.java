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
package io.agentscope.builder.web.api;

import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.SessionFileService;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.core.session.JournalSessionLog;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Explicit publication of external artifact references; private journal blobs are never public artifacts. */
@RestController
@RequestMapping("/api/v1/agent-sessions/{session}/artifacts")
public class AgentSessionArtifactsController {
    private final DataSessionService sessions;
    private final SessionEventLog events;
    private final SessionFileService files;

    public AgentSessionArtifactsController(
            DataSessionService sessions, SessionEventLog events, SessionFileService files) {
        this.sessions = sessions;
        this.events = events;
        this.files = files;
    }

    public record Publish(
            String name, String uri, String media_type, String sha256, String file_id) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> publish(
            @PathVariable String session,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody Publish input,
            Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            sessions.get((String) auth.getPrincipal(), session);
                            if (input.file_id() != null) {
                                if (input.uri() != null || key.isBlank() || key.length() > 256)
                                    throw new ResponseStatusException(
                                            HttpStatus.BAD_REQUEST,
                                            "Supply file_id or uri and a valid key");
                                var file = files.get(session, input.file_id());
                                String id =
                                        JournalSessionLog.hash(
                                                (session + "\n" + key)
                                                        .getBytes(StandardCharsets.UTF_8));
                                var artifact =
                                        Map.<String, Object>of(
                                                "artifact_id",
                                                id,
                                                "file_id",
                                                input.file_id(),
                                                "name",
                                                file.get("name"),
                                                "uri",
                                                file.get("download_url"),
                                                "media_type",
                                                file.get("media_type"),
                                                "sha256",
                                                file.get("sha256"),
                                                "status",
                                                "published");
                                events.appendIdempotent(
                                        session,
                                        "artifact.published",
                                        artifact,
                                        "art_" + id.substring(0, 56));
                                return artifact;
                            }
                            if (key.isBlank()
                                    || key.length() > 256
                                    || input.name() == null
                                    || input.name().isBlank()
                                    || input.name().length() > 512
                                    || input.uri() == null
                                    || input.uri().length() > 4096)
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Valid artifact name, URI and key required");
                            URI uri;
                            try {
                                uri = URI.create(input.uri());
                            } catch (IllegalArgumentException error) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "Invalid artifact URI");
                            }
                            if (!"https".equalsIgnoreCase(uri.getScheme())
                                    || uri.getHost() == null
                                    || uri.getUserInfo() != null)
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Artifact publication requires an HTTPS reference without"
                                                + " credentials");
                            if (input.sha256() != null
                                    && !input.sha256().matches("[0-9a-fA-F]{64}"))
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "Invalid SHA-256");
                            String id =
                                    JournalSessionLog.hash(
                                            (session + "\n" + key)
                                                    .getBytes(StandardCharsets.UTF_8));
                            var artifact =
                                    Map.<String, Object>of(
                                            "artifact_id",
                                            id,
                                            "name",
                                            input.name(),
                                            "uri",
                                            uri.toString(),
                                            "media_type",
                                            input.media_type() == null
                                                    ? "application/octet-stream"
                                                    : input.media_type(),
                                            "sha256",
                                            input.sha256() == null ? "" : input.sha256(),
                                            "status",
                                            "published");
                            events.appendIdempotent(
                                    session,
                                    "artifact.published",
                                    artifact,
                                    "art_" + id.substring(0, 56));
                            return artifact;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(
            @PathVariable String session, @PathVariable String id, Authentication auth) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            sessions.get((String) auth.getPrincipal(), session);
                            boolean exists =
                                    events.list(session).stream()
                                            .anyMatch(
                                                    event ->
                                                            event.type()
                                                                            .equals(
                                                                                    "artifact.published")
                                                                    && id.equals(
                                                                            event.payload()
                                                                                    .get(
                                                                                            "artifact_id")));
                            if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                            events.appendIdempotent(
                                    session,
                                    "artifact.deleted",
                                    Map.of("artifact_id", id, "status", "deleted"),
                                    "del_"
                                            + JournalSessionLog.hash(
                                                            (session + "\n" + id)
                                                                    .getBytes(
                                                                            StandardCharsets.UTF_8))
                                                    .substring(0, 56));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
