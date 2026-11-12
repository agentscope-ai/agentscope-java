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
import io.agentcore.managed.server.runtime.protocol.InteractionCommand;
import io.agentcore.managed.server.runtime.protocol.RuntimeFact;
import io.agentcore.managed.server.runtime.protocol.RuntimePayloadDigest;
import io.agentcore.managed.server.runtime.protocol.RuntimeRequestContext;
import io.agentcore.managed.server.runtime.protocol.TurnCommand;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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
class AgentServiceActivationStopTest extends AgentServiceInfrastructureTestSupport {
    @Autowired AgentServiceRuntimeFacts facts;
    @Autowired AgentServiceSessionRepository sessions;
    @Autowired AgentServiceTurnRepository turns;
    @Autowired AgentServiceQuotaRepository quotas;
    @Autowired AgentServiceExecutionGrantRepository grants;
    @Autowired AgentServiceInteractionRepository interactions;
    @Autowired AgentServiceEventRepository events;
    @Autowired VaultCrypto crypto;
    @Autowired ObjectMapper mapper;

    @ParameterizedTest
    @CsvSource({
        "approval,interrupted,false",
        "signal,interrupted,false",
        "approval,cancelled,false",
        "signal,cancelled,false",
        "approval,completed,false",
        "signal,interrupted,true",
        "approval,failed,false",
        "signal,failed,true"
    })
    void stopsAcceptedControlWithoutGrantAndDrainsOriginalFacts(
            String kind, String outcome, boolean lostGrantAck) throws Exception {
        var context = fixture(kind, lostGrantAck);
        facts.accept(
                new RuntimeFact(
                        context,
                        "orchestration",
                        "stopped-node",
                        1,
                        "1",
                        "original-fence",
                        "step.updated",
                        Map.of(
                                "activation_command_id",
                                context.turnId(),
                                "step_id",
                                "gate",
                                "status",
                                "cancelled")));
        assertThat(turns.findById(context.turnId()).orElseThrow().status).isEqualTo("queued");
        assertThat(quotas.findById("stop-quota").orElseThrow().activeCount).isEqualTo(1);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("activation_command_id", "answer");
        payload.put("control_stop", true);
        payload.put("native_stopped", true);
        payload.put("native_started", true);
        payload.put("reason", "execution_authorization_rejected");
        if (outcome.equals("cancelled") || outcome.equals("failed")) {
            var cancellation =
                    interaction(
                            context,
                            "cancel",
                            "cancel",
                            Map.of(
                                    "target_command_id",
                                    context.turnId(),
                                    "target_activation_command_id",
                                    "answer",
                                    "expected_generation",
                                    lostGrantAck ? 2 : 1));
            interactions.saveAndFlush(cancellation);
            payload.put("cancel_command_id", cancellation.id);
        }
        var terminal =
                new RuntimeFact(
                        context,
                        "orchestration",
                        "physical-stop",
                        2,
                        null,
                        null,
                        "turn." + outcome,
                        payload);
        facts.accept(terminal);
        facts.accept(terminal);
        assertThat(turns.findById(context.turnId()).orElseThrow().status).isEqualTo(outcome);
        assertThat(quotas.findById("stop-quota").orElseThrow().activeCount).isZero();
        assertThat(quotas.findById("stop-quota").orElseThrow().queuedCount).isZero();
        assertThat(interactions.findById("answer").orElseThrow().nativeStatus).isEqualTo("failed");
        if (outcome.equals("cancelled") || outcome.equals("failed"))
            assertThat(interactions.findById("cancel").orElseThrow().nativeStatus)
                    .isEqualTo("applied");
        assertThat(events.highWatermark(context.sessionId())).isEqualTo(2);
        // Internal stop proof is persisted in the receipt, not exposed in SSE.
        var publicEvents =
                events.findBySessionIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
                        context.sessionId(), 0, 2, PageRequest.of(0, 10));
        assertThat(publicEvents)
                .allSatisfy(
                        row ->
                                assertThat(row.data)
                                        .doesNotContain(
                                                "control_stop",
                                                "native_stopped",
                                                "activation_command_id",
                                                "cancel_command_id"));
    }

    @Test
    void delayedPriorActivationCompletionCannotSettleANewerInterruptedTurn() throws Exception {
        var context = fixture("signal", true);
        var session = sessions.findById(context.sessionId()).orElseThrow();
        session.runtimeKind = "managed";
        sessions.saveAndFlush(session);
        var turn = turns.findById(context.turnId()).orElseThrow();
        turn.status = "interrupted";
        turns.saveAndFlush(turn);
        facts.accept(
                new RuntimeFact(
                        context,
                        "managed",
                        "old-native-completion",
                        1,
                        "1",
                        "original-fence",
                        "turn.completed",
                        Map.of(
                                "native_event_id",
                                "native-end",
                                "native_sequence",
                                74,
                                "run_id",
                                "old-native-run")));
        assertThat(turns.findById(turn.id).orElseThrow().status).isEqualTo("interrupted");
    }

    @ParameterizedTest
    @CsvSource({"failed", "interrupted", "cancelled"})
    void committedCurrentCompletionRepairsConservativeStopWithoutReleasingQuotaTwice(String stopped)
            throws Exception {
        var context = stoppedManagedTurn(stopped);
        var completion =
                new RuntimeFact(
                        context,
                        "managed",
                        "delayed-native-completion",
                        1,
                        "1",
                        "original-fence",
                        "turn.completed",
                        Map.of(
                                "native_event_id",
                                "native-end",
                                "native_sequence",
                                74,
                                "run_id",
                                "native-run"));
        facts.accept(completion);
        facts.accept(completion);
        assertThat(turns.findById(context.turnId()).orElseThrow().status)
                .isEqualTo(stopped.equals("cancelled") ? "cancelled" : "completed");
        assertThat(quotas.findById("stop-quota").orElseThrow().activeCount).isZero();
        assertThat(quotas.findById("stop-quota").orElseThrow().queuedCount).isZero();
        assertThat(events.highWatermark(context.sessionId())).isEqualTo(1);
    }

    @Test
    void completionWithoutCommittedNativeProofCannotRewindAnInterruptedTurn() throws Exception {
        var context = stoppedManagedTurn("interrupted");
        assertThatThrownBy(
                        () ->
                                facts.accept(
                                        new RuntimeFact(
                                                context,
                                                "managed",
                                                "unproven-completion",
                                                1,
                                                "1",
                                                "original-fence",
                                                "turn.completed",
                                                Map.of())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    private RuntimeRequestContext stoppedManagedTurn(String status) throws Exception {
        var context = fixture("signal", false);
        var session = sessions.findById(context.sessionId()).orElseThrow();
        session.runtimeKind = "managed";
        sessions.saveAndFlush(session);
        var turn = turns.findById(context.turnId()).orElseThrow();
        turn.status = status;
        turn.activationCommandId = turn.id;
        turn.reservationState = "released";
        turn.queuedReservation = false;
        turns.saveAndFlush(turn);
        var quota = quotas.findById("stop-quota").orElseThrow();
        quota.activeCount = 0;
        quota.queuedCount = 0;
        quotas.saveAndFlush(quota);
        return context;
    }

    @Test
    void physicalCompletionCanSettleASuspendedTurnWithoutExecutingAnAnswer() throws Exception {
        var context = fixture("signal", false);
        var turn = turns.findById(context.turnId()).orElseThrow();
        turn.status = "requires_action";
        turns.saveAndFlush(turn);
        facts.accept(
                new RuntimeFact(
                        context,
                        "orchestration",
                        "stopped-completion",
                        1,
                        null,
                        null,
                        "turn.completed",
                        Map.of(
                                "activation_command_id", "answer",
                                "control_stop", true,
                                "native_stopped", true,
                                "native_started", true)));
        assertThat(turns.findById(context.turnId()).orElseThrow().status).isEqualTo("completed");
        assertThat(quotas.findById("stop-quota").orElseThrow().activeCount).isZero();
        assertThat(interactions.findById("answer").orElseThrow().nativeStatus).isEqualTo("failed");
    }

    @ParameterizedTest
    @CsvSource({"cancel_requested", "completed"})
    void lateCurrentWriterCannotRewindThePublicTurn(String status) throws Exception {
        var context = fixture("signal", false);
        var turn = turns.findById(context.turnId()).orElseThrow();
        turn.status = status;
        turn.activationCommandId = turn.id;
        turns.saveAndFlush(turn);
        facts.accept(
                new RuntimeFact(
                        context,
                        "orchestration",
                        "late-running",
                        1,
                        "1",
                        "original-fence",
                        "turn.running",
                        Map.of("status", "completed")));
        assertThat(turns.findById(turn.id).orElseThrow().status).isEqualTo(status);
        var event =
                events.findBySessionIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
                                context.sessionId(), 0, Long.MAX_VALUE, PageRequest.of(0, 1))
                        .get(0);
        assertThat(event.type).isEqualTo("execution.running");
        assertThat(mapper.readTree(event.data).get("status").asText()).isEqualTo("running");
    }

    @Test
    void aTerminalControlFactRequiresPhysicalStopAndTheCurrentActivation() throws Exception {
        var context = fixture("signal", false);
        assertThatThrownBy(
                        () ->
                                facts.accept(
                                        new RuntimeFact(
                                                context,
                                                "orchestration",
                                                "no-stop",
                                                1,
                                                null,
                                                null,
                                                "turn.interrupted",
                                                Map.of(
                                                        "activation_command_id",
                                                        "answer",
                                                        "control_stop",
                                                        true,
                                                        "native_started",
                                                        true))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    private RuntimeRequestContext fixture(String kind, boolean lostGrantAck) throws Exception {
        long now = System.currentTimeMillis();
        var context =
                new RuntimeRequestContext(
                        1,
                        "tenant",
                        "default",
                        "application",
                        "stop-root",
                        "stop-turn",
                        "stop-turn",
                        "trace",
                        Map.of("user", "owner", "key_hash", "", "internal", false),
                        "1",
                        now + 60000);
        var root = new AgentServiceSessionEntity();
        root.id = context.sessionId();
        root.tenant = context.tenant();
        root.namespace = context.namespace();
        root.ownerId = "owner";
        root.applicationId = context.applicationId();
        root.runtimeKind = "workflow";
        root.commandId = "bootstrap-stop";
        root.revision = "frozen";
        root.digest = "digest";
        root.state = "ready";
        root.lifecycle = "active";
        root.policy = "{}";
        root.publicView = "{}";
        sessions.saveAndFlush(root);
        var quota = new AgentServiceQuotaEntity();
        quota.id = "stop-quota";
        quota.tenant = context.tenant();
        quota.namespace = context.namespace();
        quota.activeCount = 1;
        quota.queuedCount = 1;
        quota.maxConcurrent = 5;
        quotas.saveAndFlush(quota);
        var input = Map.<String, Object>of("message", "gate");
        var turn = new AgentServiceTurnEntity();
        turn.id = context.turnId();
        turn.sessionId = root.id;
        turn.quotaId = quota.id;
        turn.inputDigest = RuntimePayloadDigest.of(mapper, input);
        turn.admissionSequence = 1;
        turn.status = "queued";
        turn.reservationState = "active";
        turn.queuedReservation = true;
        turn.permissionUntil = now + 60000;
        turn.executionGeneration = lostGrantAck ? 2 : 1;
        turn.executionFence = lostGrantAck ? "answer-fence" : "original-fence";
        turn.activationCommandId = "answer";
        turn.deliveryStatus = "delivered";
        turn.command =
                crypto.encrypt(
                        mapper.writeValueAsString(
                                new TurnCommand(context, "key", turn.inputDigest, 1, input)));
        turns.saveAndFlush(turn);
        var grant = new AgentServiceExecutionGrantEntity();
        grant.id = RuntimePayloadDigest.of(mapper, Map.of("turn", turn.id, "generation", 1));
        grant.turnId = turn.id;
        grant.generation = 1;
        grant.activationCommandId = turn.id;
        grant.fence = "original-fence";
        grant.validUntil = now - 1;
        grants.saveAndFlush(grant);
        if (lostGrantAck) {
            var next = new AgentServiceExecutionGrantEntity();
            next.id = RuntimePayloadDigest.of(mapper, Map.of("turn", turn.id, "generation", 2));
            next.turnId = turn.id;
            next.generation = 2;
            next.activationCommandId = "answer";
            next.fence = "answer-fence";
            next.validUntil = now + 30000;
            grants.saveAndFlush(next);
        }
        interactions.saveAndFlush(
                interaction(
                        context,
                        "answer",
                        kind,
                        Map.of("target_command_id", turn.id, "expected_generation", 1)));
        return context;
    }

    private AgentServiceInteractionEntity interaction(
            RuntimeRequestContext original, String id, String kind, Map<String, Object> payload)
            throws Exception {
        var context =
                new RuntimeRequestContext(
                        1,
                        original.tenant(),
                        original.namespace(),
                        original.applicationId(),
                        original.sessionId(),
                        original.turnId(),
                        id,
                        original.traceId(),
                        original.actor(),
                        "1",
                        original.permissionExpiresAt());
        var row = new AgentServiceInteractionEntity();
        row.id = id;
        row.sessionId = context.sessionId();
        row.turnId = context.turnId();
        row.kind = kind;
        row.command =
                crypto.encrypt(
                        mapper.writeValueAsString(new InteractionCommand(context, kind, payload)));
        row.payloadDigest = RuntimePayloadDigest.of(mapper, payload);
        row.nativeStatus = "runtime_accepted";
        row.deliveryStatus = "delivered";
        row.acceptUntil = context.permissionExpiresAt();
        return row;
    }
}
