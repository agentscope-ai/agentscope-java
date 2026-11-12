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
package io.agentcore.managed.server.agentservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceTurnRepository;
import io.agentcore.managed.server.agentservice.session.AgentServiceExecutionAuthorization;
import io.agentcore.managed.server.agentservice.session.AgentServiceInputAdmission;
import io.agentcore.managed.server.agentservice.session.AgentServiceRuntimeFacts;
import io.agentcore.managed.server.agentservice.session.AgentServiceSessionProvisioning;
import io.agentcore.managed.server.agentservice.session.AgentServiceTurnAdmission;
import io.agentcore.managed.server.agentservice.session.AgentServiceTurnControl;
import io.agentcore.managed.server.runtime.protocol.RuntimeAgentSignal;
import io.agentcore.managed.server.runtime.protocol.RuntimeExecutionRequest;
import io.agentcore.managed.server.runtime.protocol.RuntimeFact;
import io.agentcore.managed.server.runtime.protocol.RuntimeSessionAdmission;
import io.agentcore.managed.server.runtime.protocol.RuntimeSessionBootstrap;
import io.agentcore.managed.server.runtime.protocol.TurnCommand;
import io.agentcore.managed.server.web.auth.JwtService;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
            "managed-agents.local-dev=false",
            "managed-agents.internal-token=signal-test-internal-token-at-least-32-characters",
            "managed-agents.agent-service.provisioning-poll-ms=3600000",
            "managed-agents.agent-service.turn-delivery-poll-ms=3600000",
            "managed-agents.agent-service.interaction-delivery-poll-ms=3600000",
            "spring.datasource.url=jdbc:h2:mem:portal-agent-signal;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
class AgentServiceAgentSignalAdmissionTest extends AgentServiceInfrastructureTestSupport {
    @Autowired AgentServiceTurnAdmission admission;
    @Autowired AgentServiceSessionProvisioning provisioning;
    @Autowired AgentServiceSessionRepository sessions;
    @Autowired AgentServiceTurnRepository turns;
    @Autowired ObjectMapper mapper;
    @Autowired VaultCrypto crypto;
    @Autowired TransactionTemplate tx;
    @Autowired JwtService jwt;
    @Autowired AgentServiceExecutionAuthorization execution;
    @Autowired AgentServiceRuntimeFacts facts;
    @Autowired AgentServiceInputAdmission inputs;
    @Autowired AgentServiceTurnControl controls;

    @Test
    void fencedAgentSignalsKeepOriginalPermissionWithoutBecomingHumanApproval() throws Exception {
        String sessionId = readySession("agent-signal-" + UUID.randomUUID());
        tx.executeWithoutResult(
                ignored -> {
                    var session = sessions.lockById(sessionId).orElseThrow();
                    session.runtimeKind = "workflow";
                    sessions.saveAndFlush(session);
                });
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generate("user-1", "fixture", List.of("user")));
        String turnId =
                String.valueOf(admission.accept(sessionId, headers, Map.of("value", 1)).get("id"));
        var turn = turns.findById(turnId).orElseThrow();
        var original = mapper.readValue(crypto.decrypt(turn.command), TurnCommand.class);
        execution.authorize(
                new RuntimeExecutionRequest(
                        original.context(),
                        original.inputDigest(),
                        original.admissionSequence(),
                        1,
                        "agent-signal-grant",
                        false));
        String signalId = UUID.randomUUID().toString();
        String confirmationId = UUID.randomUUID().toString();
        facts.accept(
                new RuntimeFact(
                        original.context(),
                        "orchestration",
                        "signal-pending",
                        1,
                        "1",
                        "agent-signal-grant",
                        "required_action.created",
                        Map.of("request_id", signalId, "kind", "signal", "signal_name", "ready")));
        facts.accept(
                new RuntimeFact(
                        original.context(),
                        "orchestration",
                        "confirmation-pending",
                        2,
                        "1",
                        "agent-signal-grant",
                        "required_action.created",
                        Map.of("request_id", confirmationId, "kind", "confirmation")));
        tx.executeWithoutResult(
                ignored -> {
                    var current = turns.lockById(turnId).orElseThrow();
                    current.status = "running";
                    turns.saveAndFlush(current);
                });
        String task = UUID.randomUUID().toString();
        String attempt = UUID.randomUUID().toString();
        var request =
                new RuntimeAgentSignal(
                        1,
                        sessionId,
                        turnId,
                        1,
                        "agent-signal-grant",
                        task,
                        attempt,
                        7,
                        "agent-1",
                        signalId,
                        "reply",
                        Map.of("value", 42));
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        controls.actions(
                                                sessionId,
                                                turnId,
                                                headers,
                                                List.of(
                                                        Map.of(
                                                                "request_id",
                                                                signalId,
                                                                "value",
                                                                42))))
                        .getStatusCode());
        assertEquals(
                HttpStatus.FORBIDDEN,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        controls.agentSignal(
                                                new RuntimeAgentSignal(
                                                        1,
                                                        sessionId,
                                                        turnId,
                                                        1,
                                                        "agent-signal-grant",
                                                        task,
                                                        attempt,
                                                        7,
                                                        "agent-1",
                                                        confirmationId,
                                                        "approval-bypass",
                                                        null)))
                        .getStatusCode());
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        controls.agentSignal(
                                                new RuntimeAgentSignal(
                                                        1,
                                                        sessionId,
                                                        turnId,
                                                        1,
                                                        "stale-grant",
                                                        task,
                                                        attempt,
                                                        7,
                                                        "agent-1",
                                                        signalId,
                                                        "stale",
                                                        42)))
                        .getStatusCode());
        var accepted = controls.agentSignal(request);
        assertEquals("signal", accepted.kind);
        assertEquals("queued", accepted.nativeStatus);
        // Delivery may wait for another replica; an old renewal cannot stop the pending activation.
        assertEquals(
                HttpStatus.TOO_EARLY,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        execution.authorize(
                                                new RuntimeExecutionRequest(
                                                        original.context(),
                                                        original.inputDigest(),
                                                        original.admissionSequence(),
                                                        1,
                                                        "agent-signal-grant",
                                                        true)))
                        .getStatusCode());
        assertEquals("queued", turns.findById(turnId).orElseThrow().status);
        var actor = inputs.command(accepted).context().actor();
        assertEquals("user-1", actor.get("user"));
        assertEquals(task, actor.get("agent_task"));
        assertEquals(attempt, actor.get("agent_attempt"));
        assertEquals("agent-1", actor.get("agent_ref"));
        assertEquals(accepted.id, controls.agentSignal(request).id);
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        controls.agentSignal(
                                                new RuntimeAgentSignal(
                                                        1,
                                                        sessionId,
                                                        turnId,
                                                        1,
                                                        "agent-signal-grant",
                                                        task,
                                                        attempt,
                                                        7,
                                                        "agent-1",
                                                        signalId,
                                                        "reply",
                                                        Map.of("value", 43))))
                        .getStatusCode());
    }

    private String readySession(String applicationId) throws Exception {
        ObjectNode wire =
                (ObjectNode)
                        mapper.readTree(
                                Files.readString(
                                        Path.of(
                                                "..",
                                                "common",
                                                "src",
                                                "test",
                                                "resources",
                                                "runtime-protocol",
                                                "v1",
                                                "bootstrap.json")));
        String id = UUID.randomUUID().toString();
        ((ObjectNode) wire.get("context")).put("applicationId", applicationId);
        ((ObjectNode) wire.get("context")).put("sessionId", id);
        ((ObjectNode) wire.get("context")).put("commandId", UUID.randomUUID().toString());
        ((ObjectNode) wire.get("configuration").get("session")).put("id", id);
        provisioning.stage(
                new RuntimeSessionAdmission(
                        mapper.treeToValue(wire, RuntimeSessionBootstrap.class),
                        Map.of(
                                "tenant",
                                "tenant-1",
                                "namespace",
                                "default",
                                "enabled",
                                true,
                                "valid_until",
                                2000000000000L,
                                "humans",
                                Map.of("user-1", List.of("invoke", "read", "cancel")),
                                "application",
                                Map.of(
                                        "id",
                                        applicationId,
                                        "maxConcurrent",
                                        1,
                                        "tokensUsed",
                                        0,
                                        "tokenBudget",
                                        1000,
                                        "version",
                                        1)),
                        Map.of("id", id)));
        tx.executeWithoutResult(
                ignored -> {
                    var row = sessions.lockById(id).orElseThrow();
                    row.state = "ready";
                    row.bootstrapPayload = null;
                    sessions.saveAndFlush(row);
                });
        return id;
    }
}
