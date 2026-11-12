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
package io.agentcore.managed.server.agentservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentcore.managed.server.agentservice.persistence.AgentServiceArtifactPublicationRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceEventEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceEventRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.session.AgentServiceArtifacts;
import io.agentcore.managed.server.agentservice.session.AgentServiceResourceProjection;
import io.agentcore.managed.server.agentservice.session.AgentServiceResourceReads;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@TestPropertySource(locations = "classpath:application-test.properties")
@SpringBootTest(
        classes = AgentServiceApp.class,
        properties = {
            "managed-agents.local-dev=true",
            "managed-agents.control-plane-url=http://127.0.0.1:1",
            "managed-agents.agent-harness-runtime-url=http://127.0.0.1:1",
            "managed-agents.orchestration-runtime-url=http://127.0.0.1:1",
            "managed-agents.internal-token=artifact-test-internal-token-at-least-32-characters",
            "managed-agents.agent-service.provisioning-poll-ms=3600000",
            "managed-agents.agent-service.turn-delivery-poll-ms=3600000",
            "managed-agents.agent-service.interaction-delivery-poll-ms=3600000",
            "managed-agents.agent-service.lifecycle-poll-ms=3600000",
            "spring.datasource.url=jdbc:h2:mem:portal-artifacts;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
class AgentServiceArtifactsTest extends AgentServiceInfrastructureTestSupport {
    @Autowired AgentServiceArtifacts artifacts;
    @Autowired AgentServiceResourceReads reads;
    @Autowired AgentServiceSessionRepository sessions;
    @Autowired AgentServiceArtifactPublicationRepository receipts;
    @Autowired AgentServiceEventRepository events;
    @Autowired AgentServiceResourceProjection projection;
    @Autowired TransactionTemplate tx;

    @Test
    void deletingARuntimeArtifactKeepsItsTurnInThePublicStream() {
        String session = ready();
        tx.executeWithoutResult(
                ignored -> {
                    var row = sessions.lockById(session).orElseThrow();
                    var event = new AgentServiceEventEntity();
                    event.id = UUID.randomUUID().toString();
                    event.sessionId = session;
                    event.turnId = "native-turn";
                    event.seq = ++row.nextEventSequence;
                    event.type = "artifact.published";
                    event.createdAt = System.currentTimeMillis();
                    event.data =
                            "{\"artifact_id\":\"native-artifact\",\"turn_id\":\"native-turn\",\"status\":\"published\"}";
                    events.saveAndFlush(event);
                    projection.apply(row, event);
                    sessions.saveAndFlush(row);
                });
        artifacts.delete(session, "native-artifact", new HttpHeaders());
        var page =
                events.findBySessionIdAndTurnIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
                        session, "native-turn", 0, 2, PageRequest.of(0, 10));
        assertEquals(2, page.size());
        assertEquals("artifact.deleted", page.get(1).type);
    }

    private String ready() {
        var row = new AgentServiceSessionEntity();
        row.id = UUID.randomUUID().toString();
        row.ownerId = "local-developer";
        row.tenant = "tenant";
        row.namespace = "space";
        row.runtimeKind = "managed";
        row.commandId = UUID.randomUUID().toString();
        row.revision = "fixture";
        row.digest = "a".repeat(64);
        row.state = "ready";
        row.lifecycle = "active";
        row.policy = "{}";
        row.publicView = "{}";
        sessions.saveAndFlush(row);
        return row.id;
    }

    @Test
    void concurrentPublicationCommitsOnceAndDeletionPreservesOldSnapshot() throws Exception {
        String session = ready();
        var headers = new HttpHeaders();
        headers.set("Idempotency-Key", "deliverable");
        var input =
                Map.<String, Object>of(
                        "name", "result.txt", "uri", "https://example.invalid/result.txt");
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one =
                    executor.submit(
                            () -> {
                                start.await();
                                return artifacts.publish(session, headers, input);
                            });
            var two =
                    executor.submit(
                            () -> {
                                start.await();
                                return artifacts.publish(session, headers, input);
                            });
            start.countDown();
            var published = one.get(10, TimeUnit.SECONDS);
            assertEquals(published, two.get(10, TimeUnit.SECONDS));
            String id = (String) published.get("artifact_id");
            assertEquals(1, events.highWatermark(session));
            assertEquals(
                    HttpStatus.CONFLICT,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            artifacts.publish(
                                                    session,
                                                    headers,
                                                    Map.of(
                                                            "name",
                                                            "changed",
                                                            "uri",
                                                            "https://example.invalid/other")))
                            .getStatusCode());
            var snapshot = reads.snapshot(session, 10, headers);
            String snapshotId = (String) snapshot.get("snapshot_id");
            assertEquals(
                    id,
                    ((Map<?, ?>)
                                    ((Map<?, ?>)
                                                    reads.get(
                                                                    session,
                                                                    null,
                                                                    "artifacts",
                                                                    id,
                                                                    snapshotId,
                                                                    headers)
                                                            .get("data"))
                                            .get("data"))
                            .get("artifact_id"));
            artifacts.delete(session, id, headers);
            artifacts.delete(session, id, headers);
            assertEquals(2, events.highWatermark(session));
            assertEquals(
                    HttpStatus.NOT_FOUND,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () -> reads.get(session, null, "artifacts", id, null, headers))
                            .getStatusCode());
            assertEquals(
                    id,
                    ((Map<?, ?>)
                                    ((Map<?, ?>)
                                                    reads.get(
                                                                    session,
                                                                    null,
                                                                    "artifacts",
                                                                    id,
                                                                    snapshotId,
                                                                    headers)
                                                            .get("data"))
                                            .get("data"))
                            .get("artifact_id"));
            assertEquals(published, artifacts.publish(session, headers, input));
            assertEquals(2, events.highWatermark(session));
            assertTrue(receipts.findAll().stream().anyMatch(row -> row.sessionId.equals(session)));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidExternalReferencesCannotCommitAnEvent() {
        String session = ready();
        var headers = new HttpHeaders();
        headers.set("Idempotency-Key", "invalid");
        for (String uri :
                List.of(
                        "http://example.invalid/x",
                        "https://user:secret@example.invalid/x",
                        "file:///tmp/private"))
            assertEquals(
                    HttpStatus.BAD_REQUEST,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            artifacts.publish(
                                                    session,
                                                    headers,
                                                    Map.of("name", "result", "uri", uri)))
                            .getStatusCode());
        assertEquals(0, events.highWatermark(session));
    }
}
