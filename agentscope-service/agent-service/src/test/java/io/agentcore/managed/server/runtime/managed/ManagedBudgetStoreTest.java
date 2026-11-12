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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.web.managed.SessionUsagePricer;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Accounting transactions and limits; transport/guard ownership has separate contract tests. */
class ManagedBudgetStoreTest {
    @TempDir private Path managedCheckpointRoot;

    private final ManagedScope scope = new ManagedScope("t", "n", "s");
    private ManagedAdmissionStore admission;
    private ManagedBudgetStore budgets;
    private ManagedJournalStore journal;
    private String turn;
    private DriverManagerDataSource source;

    @BeforeEach
    void setup() {
        source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:budget-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var mapper = new ObjectMapper();
        admission = ProjectionTestSupport.admission(source, mapper);
        admission.createSession(scope, "{}");
        budgets =
                new ManagedBudgetStore(
                        source,
                        mapper,
                        (model, usage) ->
                                new SessionUsagePricer.Quote(
                                        new BigDecimal(
                                                String.valueOf(
                                                        usage.getOrDefault("inputTokens", 0))),
                                        "USD"));
        journal =
                ProjectionTestSupport.journal(
                        source,
                        mapper,
                        budgets,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        turn = active(scope, "first");
    }

    private String active(ManagedScope session, String key) {
        String id = admission.acceptTurn(session, "u", key, "{}").id();
        admission.reservePublication(session, id);
        admission.confirmPublication(session, id, "broker");
        admission.activate(session, id, 1, "brain");
        return id;
    }

    private ManagedTurnExecution execution(ManagedScope session, String id, long epoch) {
        var execution = mock(ManagedTurnExecution.class);
        when(execution.scope()).thenReturn(session);
        when(execution.turnId()).thenReturn(id);
        when(execution.epoch()).thenReturn(epoch);
        when(execution.admission()).thenReturn(admission);
        return execution;
    }

    @Test
    void recoveryLimitSurvivesStoreReconstructionAndClaimReplay() {
        budgets.configure(scope, Map.of("max_recovery_attempts", 1), 0L);
        admission.abandonExecution(scope, turn, 1, "brain");
        long epoch = admission.activate(scope, turn, 1, "replacement");
        assertEquals(epoch, admission.activate(scope, turn, 1, "replacement"));
        assertEquals(1L, number("recovery_attempts"));
        admission.abandonExecution(scope, turn, epoch, "replacement");
        var rebuilt = ProjectionTestSupport.admission(source, new ObjectMapper());
        assertEquals(
                "budget_exceeded",
                assertThrows(
                                ManagedStateException.class,
                                () -> rebuilt.activate(scope, turn, 1, "third"))
                        .code());
        assertEquals(1L, number("recovery_attempts"));
    }

    @Test
    void cancellationCleanupRemainsClaimableAfterRecoveryAndDeadlineExhaustion() {
        budgets.configure(scope, Map.of("max_recovery_attempts", 0, "deadline_epoch_ms", 1L), 0L);
        admission.abandonExecution(scope, turn, 1, "brain");
        assertEquals(
                "budget_exceeded",
                assertThrows(
                                ManagedStateException.class,
                                () -> admission.activate(scope, turn, 1, "denied"))
                        .code());
        admission.acceptInput(
                scope, turn, "actor", "cancel", ManagedInputInbox.Type.CANCEL, null, "{}");
        long epoch = admission.activate(scope, turn, 1, "cleanup");
        assertTrue(epoch > 1);
        assertEquals(0L, number("recovery_attempts"));
        assertEquals(
                "budget_exceeded",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        budgets.reserve(
                                                scope,
                                                turn,
                                                epoch,
                                                "late-model",
                                                "model",
                                                admission))
                        .code());
        assertEquals(0L, number("model_calls"));
    }

    @Test
    void expiredCaseCannotAdmitModelToolOrRecovery() {
        budgets.configure(scope, Map.of("deadline_epoch_ms", 1L), 0L);
        assertEquals(
                "budget_exceeded",
                assertThrows(
                                ManagedStateException.class,
                                () -> budgets.reserve(scope, turn, 1, "late", "model", admission))
                        .code());
        assertEquals(
                "budget_exceeded",
                assertThrows(ManagedStateException.class, () -> budgets.admitTools(scope, 1))
                        .code());
        admission.abandonExecution(scope, turn, 1, "brain");
        assertEquals(
                "budget_exceeded",
                assertThrows(
                                ManagedStateException.class,
                                () -> admission.activate(scope, turn, 1, "late"))
                        .code());
        assertEquals(0L, number("model_calls"));
    }

    @Test
    void toolBudgetCountsBatchChildrenAndPersistsAcrossInstances() {
        budgets.configure(scope, Map.of("max_tool_calls", 2), 0L);
        budgets.admitTools(scope, 2);
        // Framework preparation still checks the deadline but does not spend a model tool action.
        budgets.admitTools(scope, 0);
        var rebuilt = new ManagedBudgetStore(new JdbcTemplate(source), new ObjectMapper());
        assertEquals(
                "budget_exceeded",
                assertThrows(ManagedStateException.class, () -> rebuilt.admitTools(scope, 1))
                        .code());
        assertEquals(2L, number("tool_calls"));
    }

    @Test
    void rejectedToolTransactionRollsBackCountsButRetainsDenialEvidence() {
        budgets.configure(scope, Map.of("max_tool_calls", 1), 0L);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertThrows(
                ManagedStateException.class,
                () ->
                        budgets.observeDenial(
                                scope,
                                turn,
                                () ->
                                        transaction.execute(
                                                ignored -> {
                                                    budgets.admitTools(scope, 1);
                                                    budgets.admitTools(scope, 1);
                                                    return null;
                                                })));
        assertEquals(0L, number("tool_calls"));
        var restarted = new ManagedBudgetStore(source, new ObjectMapper(), null);
        var usage = (Map<?, ?>) restarted.get(scope).get("usage");
        var denial = (Map<?, ?>) usage.get("last_denial");
        assertEquals("max_tool_calls", denial.get("limit"));
        assertEquals(turn, denial.get("turn_id"));
        budgets.configure(scope, Map.of("max_tool_calls", 1), 1L);
        assertEquals(denial, ((Map<?, ?>) budgets.get(scope).get("usage")).get("last_denial"));
        budgets.configure(scope, Map.of("max_tool_calls", 2), 1L);
        assertEquals(null, ((Map<?, ?>) budgets.get(scope).get("usage")).get("last_denial"));
    }

    @Test
    void modelReservationIsIdempotentAndBudgetPreventsAnotherCall() {
        budgets.configure(scope, Map.of("max_model_calls", 1), 0L);
        var execution = execution(scope, turn, 1);
        budgets.reserve(
                execution.scope(),
                execution.turnId(),
                execution.epoch(),
                "call",
                "model",
                execution.admission());
        budgets.reserve(
                execution.scope(),
                execution.turnId(),
                execution.epoch(),
                "call",
                "model",
                execution.admission());
        assertEquals(1L, number("model_calls"));
        assertEquals(
                "budget_exceeded",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        budgets.reserve(
                                                execution.scope(),
                                                execution.turnId(),
                                                execution.epoch(),
                                                "next",
                                                "model",
                                                execution.admission()))
                        .code());
        assertEquals(
                "model_identity_conflict",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        budgets.reserve(
                                                execution.scope(),
                                                execution.turnId(),
                                                execution.epoch(),
                                                "call",
                                                "different-model",
                                                execution.admission()))
                        .code());
    }

    @Test
    void nativeEndAndAccountingCommitAtomicallyAndStaleReplayCannotBillTwice() {
        budgets.reserve(
                execution(scope, turn, 1).scope(),
                execution(scope, turn, 1).turnId(),
                execution(scope, turn, 1).epoch(),
                "call",
                "model",
                execution(scope, turn, 1).admission());
        var request =
                event(
                        scope,
                        turn,
                        1,
                        "request/prepared",
                        Map.of(
                                "modelCallId",
                                "call",
                                "model",
                                "model",
                                "purpose",
                                "REASONING",
                                "messages",
                                List.of()));
        var end =
                event(
                        scope,
                        turn,
                        2,
                        "model/end",
                        Map.of(
                                "modelCallId",
                                "call",
                                "status",
                                "completed",
                                "usage",
                                Map.of("inputTokens", 12, "outputTokens", 8)));
        var bad = event(scope, turn, 3, "state/checkpoint", Map.of("stateJson", 42));
        assertThrows(
                ManagedStateException.class,
                () ->
                        AgentScopePreparation.commit(
                                journal,
                                new SessionCommitTransactions(journal),
                                AgentScopeCheckpoints.prepare(
                                        journal.objects(),
                                        batch(scope, turn, "bad", List.of(request, end, bad)))));
        assertEquals(1L, number("in_flight_calls"));
        assertEquals(0L, journal.head(scope).sequence());
        var good =
                event(scope, turn, 3, "state/checkpoint", Map.of("stateJson", "{\"context\":[]}"));
        var commit = batch(scope, turn, "good", List.of(request, end, good));
        var receipt =
                AgentScopePreparation.commit(
                        journal,
                        new SessionCommitTransactions(journal),
                        AgentScopeCheckpoints.prepare(journal.objects(), commit));
        assertEquals(
                receipt,
                AgentScopePreparation.commit(
                        journal,
                        new SessionCommitTransactions(journal),
                        AgentScopeCheckpoints.prepare(journal.objects(), commit)));
        assertEquals(3, journal.head(scope).sequence());
        assertEquals(20L, number("total_tokens"));
        assertEquals(0L, number("in_flight_calls"));
        var usage = (Map<?, ?>) budgets.get(scope).get("usage");
        assertEquals(new BigDecimal("12"), new BigDecimal(usage.get("estimated_cost").toString()));
    }

    @Test
    void childModelsUseTheRootBudget() {
        budgets.configure(scope, Map.of("max_model_calls", 1), 0L);
        var child = new ManagedScope("t", "n", "child");
        admission.createSession(child, "{\"root_session_id\":\"s\"}");
        String childTurn = active(child, "child");
        budgets.reserve(
                execution(child, childTurn, 1).scope(),
                execution(child, childTurn, 1).turnId(),
                execution(child, childTurn, 1).epoch(),
                "child-call",
                "model",
                execution(child, childTurn, 1).admission());
        assertEquals(1L, number("model_calls"));
        assertThrows(
                ManagedStateException.class,
                () ->
                        budgets.reserve(
                                execution(scope, turn, 1).scope(),
                                execution(scope, turn, 1).turnId(),
                                execution(scope, turn, 1).epoch(),
                                "parent-call",
                                "model",
                                execution(scope, turn, 1).admission()));
        assertEquals(budgets.get(scope), budgets.get(child));
    }

    @Test
    void lostEpochWithUnreportedUsageCannotBypassTokenBudget() {
        budgets.configure(scope, Map.of("max_total_tokens", 100), 0L);
        budgets.reserve(
                execution(scope, turn, 1).scope(),
                execution(scope, turn, 1).turnId(),
                execution(scope, turn, 1).epoch(),
                "old",
                "model",
                execution(scope, turn, 1).admission());
        admission.activate(scope, turn, 1, "new-brain");
        assertEquals(
                "usage_unavailable",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        budgets.reserve(
                                                execution(scope, turn, 2).scope(),
                                                execution(scope, turn, 2).turnId(),
                                                execution(scope, turn, 2).epoch(),
                                                "new",
                                                "model",
                                                execution(scope, turn, 2).admission()))
                        .getMessage());
        assertEquals(0L, number("in_flight_calls"));
        assertEquals(1L, number("unreported_calls"));
    }

    @Test
    void committedModelUsageAlsoEnforcesTheApplicationTokenBudget() {
        admission.applicationPolicy(scope, Map.of("version", 1, "tokenBudget", 10));
        budgets.reserve(
                execution(scope, turn, 1).scope(),
                execution(scope, turn, 1).turnId(),
                execution(scope, turn, 1).epoch(),
                "first",
                "model",
                execution(scope, turn, 1).admission());
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch(
                                scope,
                                turn,
                                "usage",
                                List.of(
                                        event(
                                                scope,
                                                turn,
                                                1,
                                                "request/prepared",
                                                Map.of(
                                                        "modelCallId",
                                                        "first",
                                                        "model",
                                                        "model",
                                                        "purpose",
                                                        "REASONING",
                                                        "messages",
                                                        List.of())),
                                        event(
                                                scope,
                                                turn,
                                                2,
                                                "model/end",
                                                Map.of(
                                                        "modelCallId",
                                                        "first",
                                                        "status",
                                                        "completed",
                                                        "usage",
                                                        Map.of(
                                                                "inputTokens",
                                                                10,
                                                                "outputTokens",
                                                                0)))))));
        // The native usage is committed immediately; the application total is eventual.
        budgets.reserve(
                execution(scope, turn, 1).scope(),
                execution(scope, turn, 1).turnId(),
                execution(scope, turn, 1).epoch(),
                "before-aggregation",
                "model",
                execution(scope, turn, 1).admission());
        try (var aggregation = new ManagedApplicationUsage(source)) {
            assertEquals(1, aggregation.flush(512));
            assertEquals(0, aggregation.flush(512));
        }
        assertEquals(
                "application_token_budget",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        budgets.reserve(
                                                execution(scope, turn, 1).scope(),
                                                execution(scope, turn, 1).turnId(),
                                                execution(scope, turn, 1).epoch(),
                                                "second",
                                                "model",
                                                execution(scope, turn, 1).admission()))
                        .getMessage());
    }

    private long number(String key) {
        var usage = (Map<?, ?>) budgets.get(scope).get("usage");
        return usage.get(key) instanceof Number n ? n.longValue() : 0;
    }

    private static SessionEvent event(
            ManagedScope scope, String turn, long seq, String type, Object payload) {
        if ("model/end".equals(type) && payload instanceof Map<?, ?> data) {
            var enriched = new LinkedHashMap<Object, Object>(data);
            enriched.put(
                    ManagedModelCapture.OUTCOME,
                    Map.of(
                            "request",
                            Map.of("model", "model", "purpose", "REASONING"),
                            "responses",
                            List.of(),
                            "replayHash",
                            ManagedScope.fingerprint("fixture")));
            payload = enriched;
        }
        return new SessionEvent(
                1,
                UUID.randomUUID().toString(),
                seq,
                System.currentTimeMillis(),
                type,
                "run",
                turn,
                true,
                JsonUtils.getJsonCodec().toJson(payload));
    }

    private static ManagedRuntimeBatch batch(
            ManagedScope scope, String turn, String id, List<SessionEvent> events) {
        return new ManagedRuntimeBatch(
                scope, turn, 1, "brain", "writer", id, 0, events, List.of(), List.of(), null);
    }
}
