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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceEventRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceExecutionGrantEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceExecutionGrantRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceInteractionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceInteractionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceQuotaEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceQuotaRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceTurnEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceTurnRepository;
import io.agentcore.managed.server.agentservice.session.AgentServiceRuntimeFacts;
import io.agentcore.managed.server.agentservice.session.AgentServiceSessionAccess;
import io.agentcore.managed.server.runtime.protocol.InteractionCommand;
import io.agentcore.managed.server.runtime.protocol.RuntimeFact;
import io.agentcore.managed.server.runtime.protocol.RuntimePayloadDigest;
import io.agentcore.managed.server.runtime.protocol.RuntimeRequestContext;
import io.agentcore.managed.server.runtime.protocol.TurnCommand;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@TestPropertySource(locations = "classpath:application-test.properties")
@SpringBootTest(
        classes = AgentServiceApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"managed-agents.local-dev=true"})
@ActiveProfiles("test")
@Transactional
class AgentServiceChildFactsTest extends AgentServiceInfrastructureTestSupport {
    @Autowired AgentServiceRuntimeFacts facts;
    @Autowired AgentServiceSessionRepository sessions;
    @Autowired AgentServiceTurnRepository turns;
    @Autowired AgentServiceQuotaRepository quotas;
    @Autowired AgentServiceExecutionGrantRepository grants;
    @Autowired AgentServiceEventRepository events;
    @Autowired AgentServiceInteractionRepository interactions;
    @Autowired AgentServiceSessionAccess access;
    @Autowired VaultCrypto crypto;
    @Autowired ObjectMapper mapper;

    @Test
    void childSequenceUsageAndReadAuthorityAreIndependentOfRootTurnSettlement() throws Exception {
        long now = System.currentTimeMillis();
        var context =
                new RuntimeRequestContext(
                        1,
                        "tenant",
                        "default",
                        "application",
                        "child-test-root",
                        "child-test-turn",
                        "child-test-turn",
                        "trace",
                        Map.of("user", "user", "key_hash", "", "internal", false),
                        "1",
                        now + 60000);
        var root = new AgentServiceSessionEntity();
        root.id = context.sessionId();
        root.tenant = context.tenant();
        root.namespace = context.namespace();
        root.ownerId = "user";
        root.applicationId = "application";
        root.runtimeKind = "managed";
        root.commandId = "child-test-bootstrap";
        root.revision = "1";
        root.digest = "digest";
        root.state = "ready";
        root.lifecycle = "active";
        root.policy = "{}";
        root.publicView = "{}";
        sessions.saveAndFlush(root);
        var quota = new AgentServiceQuotaEntity();
        quota.id = "child-test-quota";
        quota.tenant = context.tenant();
        quota.namespace = context.namespace();
        quota.activeCount = 1;
        quota.maxConcurrent = 5;
        quotas.saveAndFlush(quota);
        var input = Map.<String, Object>of("message", "parent");
        var turn = new AgentServiceTurnEntity();
        turn.id = context.turnId();
        turn.sessionId = root.id;
        turn.quotaId = quota.id;
        turn.inputDigest = RuntimePayloadDigest.of(mapper, input);
        turn.admissionSequence = 1;
        turn.status = "running";
        turn.reservationState = "active";
        turn.permissionUntil = now + 60000;
        turn.executionGeneration = 1;
        turn.executionFence = "fence";
        turn.activationCommandId = turn.id;
        turn.deliveryStatus = "delivered";
        turn.command =
                crypto.encrypt(
                        mapper.writeValueAsString(
                                new TurnCommand(context, "key", turn.inputDigest, 1, input)));
        turns.saveAndFlush(turn);
        var grant = new AgentServiceExecutionGrantEntity();
        grant.id = RuntimePayloadDigest.of(mapper, Map.of("turn", turn.id, "generation", 1L));
        grant.turnId = turn.id;
        grant.generation = 1;
        grant.activationCommandId = turn.id;
        grant.fence = "fence";
        grant.validUntil = now + 30000;
        grants.saveAndFlush(grant);
        facts.accept(
                fact(
                        context,
                        "spawn",
                        1,
                        "subagent.started",
                        Map.of(
                                "childSessionId",
                                "child-test-child",
                                "childAgentId",
                                "researcher")));
        var terminal =
                fact(
                        context,
                        "child-complete",
                        2,
                        "subagent.event",
                        envelope(
                                "turn.completed",
                                Map.of("turn_id", "native-child-turn", "status", "completed")));
        facts.accept(terminal);
        assertThat(turns.findById(turn.id).orElseThrow().status).isEqualTo("running");
        assertThat(quotas.findById(quota.id).orElseThrow().activeCount).isEqualTo(1);
        assertThat(events.highWatermark("child-test-child")).isEqualTo(1);
        assertThat(events.highWatermark(root.id)).isEqualTo(2);
        var usage =
                fact(
                        context,
                        "child-usage",
                        3,
                        "subagent.event",
                        envelope(
                                "usage.recorded",
                                Map.of(
                                        "model_call_id",
                                        "child-model",
                                        "usage",
                                        Map.of("inputTokens", 7, "outputTokens", 3))));
        var accepted = facts.accept(usage);
        assertThat(facts.accept(usage)).isEqualTo(accepted);
        assertThat(quotas.findById(quota.id).orElseThrow().tokensUsed).isEqualTo(10);
        assertThat(sessions.findById(root.id).orElseThrow().usageSummary)
                .contains("\"scope\":\"session_subtree\"", "\"model_calls\":1");
        assertThat(turns.findById(turn.id).orElseThrow().usageSummary)
                .contains("\"scope\":\"turn_subtree\"", "\"model_calls\":1");
        assertThat(events.highWatermark("child-test-child")).isEqualTo(2);
        assertThat(sessions.findById("child-test-child").orElseThrow().usageSummary)
                .contains("\"model_calls\":1");
        access.authorize("child-test-child", new HttpHeaders(), "read");
        assertThatThrownBy(() -> access.authorize("child-test-child", new HttpHeaders(), "invoke"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");

        var answer = new AgentServiceInteractionEntity();
        answer.id = "child-test-answer";
        answer.sessionId = root.id;
        answer.turnId = turn.id;
        answer.kind = "tool_result";
        answer.payloadDigest = "digest";
        answer.deliveryStatus = "delivered";
        answer.nativeStatus = "runtime_accepted";
        answer.command =
                crypto.encrypt(
                        mapper.writeValueAsString(
                                new InteractionCommand(
                                        context,
                                        "tool_result",
                                        Map.of(
                                                "answers",
                                                List.of(
                                                        Map.of(
                                                                "child_session_id",
                                                                "child-test-child",
                                                                "request_id",
                                                                "child-request",
                                                                "kind",
                                                                "external_execution"))))));
        interactions.saveAndFlush(answer);
        turn = turns.findById(turn.id).orElseThrow();
        turn.status = "queued";
        turn.queuedReservation = true;
        turn.activationCommandId = answer.id;
        turn.executionGeneration = 2;
        turn.executionFence = "child-fence";
        turns.saveAndFlush(turn);
        quota = quotas.findById(quota.id).orElseThrow();
        quota.queuedCount = 1;
        quotas.saveAndFlush(quota);
        var childGrant = new AgentServiceExecutionGrantEntity();
        childGrant.id = RuntimePayloadDigest.of(mapper, Map.of("turn", turn.id, "generation", 2L));
        childGrant.turnId = turn.id;
        childGrant.generation = 2;
        childGrant.activationCommandId = answer.id;
        childGrant.fence = "child-fence";
        childGrant.validUntil = now + 30000;
        grants.saveAndFlush(childGrant);
        facts.accept(
                new RuntimeFact(
                        context,
                        "managed",
                        "child-activation",
                        4,
                        "2",
                        "child-fence",
                        "turn.running",
                        Map.of("activation_command_id", answer.id, "status", "running")));
        var resolved =
                Map.<String, Object>of(
                        "child_session_id",
                        "child-test-child",
                        "parent_session_id",
                        root.id,
                        "activation_command_id",
                        answer.id,
                        "event_type",
                        "required_action.resolved",
                        "event_payload",
                        Map.of("request_id", "child-request", "kind", "external_execution"));
        facts.accept(
                new RuntimeFact(
                        context,
                        "managed",
                        "child-resolution",
                        5,
                        "2",
                        "child-fence",
                        "subagent.event",
                        resolved));
        facts.accept(
                new RuntimeFact(
                        context,
                        "managed",
                        "parent-spawn-resolved",
                        6,
                        "2",
                        "child-fence",
                        "required_action.resolved",
                        Map.of(
                                "activation_command_id",
                                answer.id,
                                "request_id",
                                "parent-spawn",
                                "kind",
                                "external_execution")));
        facts.accept(
                new RuntimeFact(
                        context,
                        "managed",
                        "child-wait",
                        7,
                        "2",
                        "child-fence",
                        "turn.requires_action",
                        Map.of("activation_command_id", answer.id, "status", "requires_action")));
        assertThat(interactions.findById(answer.id).orElseThrow().nativeStatus)
                .isEqualTo("applied");
        assertThat(turns.findById(turn.id).orElseThrow().status).isEqualTo("requires_action");
        assertThat(quotas.findById(quota.id).orElseThrow().activeCount).isEqualTo(1);
        assertThat(quotas.findById(quota.id).orElseThrow().queuedCount).isZero();
        root = sessions.findById(root.id).orElseThrow();
        root.lifecycle = "deleted";
        sessions.saveAndFlush(root);
        assertThatThrownBy(() -> access.authorize("child-test-child", new HttpHeaders(), "read"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    private static Map<String, Object> envelope(String type, Map<String, Object> data) {
        return Map.of(
                "child_session_id",
                "child-test-child",
                "parent_session_id",
                "child-test-root",
                "event_type",
                type,
                "event_payload",
                data);
    }

    private static RuntimeFact fact(
            RuntimeRequestContext context,
            String event,
            long seq,
            String type,
            Map<String, Object> data) {
        return new RuntimeFact(context, "managed", event, seq, "1", "fence", type, data);
    }
}
