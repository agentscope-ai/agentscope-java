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
package io.agentscope.builder.runtime.managed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Short durable admission transactions; no execution TTL or dispatch polling is stored here. */
public final class ManagedAdmissionStore {
    // A terminal is a durable business result. The SDK holds normal Session FIFO until the
    // original callback succeeds; this store checks durable predecessor identity on admission.
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Limits limits;

    public record Limits(
            int queuedPerGroup, int queuedPerSession, int pendingInputs, int requestsPerSecond) {
        public Limits {
            if (queuedPerGroup < 1
                    || queuedPerSession < 1
                    || pendingInputs < 1
                    || requestsPerSecond < 1)
                throw new IllegalArgumentException("Admission limits must be positive");
        }
    }

    public record Session(
            ManagedScope scope,
            String configuration,
            long executionEpoch,
            String currentTurnId,
            String publishingTurnId,
            long publishedSequence,
            long settledSequence,
            String lifecycle,
            long lifecycleRevision,
            String lifecycleCommand) {}

    public record Turn(
            String id,
            String actor,
            String input,
            Long publicationSequence,
            String deliveryStatus,
            String status,
            long executionEpoch,
            String executionToken,
            boolean inputsOpen,
            long inputSequence,
            long admissionSequence,
            long createdAt) {}

    public record Input(
            String id,
            String turnId,
            long sequence,
            ManagedInputInbox.Type type,
            String actionId,
            String payload,
            String actor,
            String status,
            String deliveryStatus,
            String rejectionReason) {}

    public ManagedAdmissionStore(DataSource source, ObjectMapper mapper) {
        this(source, mapper, new Limits(1000, 128, 128, 100));
    }

    public ManagedAdmissionStore(DataSource source, ObjectMapper mapper, Limits limits) {
        this.limits = limits;
        this.jdbc = new JdbcTemplate(source);
        this.jdbc.setQueryTimeout(10);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.tx.setTimeout(10);
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /** Idempotent schema setup for isolated fixtures; production uses the versioned ServiceSchema job. */
    public static void initialize(DataSource source) {
        new TransactionTemplate(new DataSourceTransactionManager(source))
                .executeWithoutResult(
                        ignored -> {
                            var connection = DataSourceUtils.getConnection(source);
                            try {
                                new ResourceDatabasePopulator(
                                                new ClassPathResource("managed-runtime-v2.sql"),
                                                new ClassPathResource("managed-subagents.sql"),
                                                new ClassPathResource(
                                                        "managed-private-objects.sql"),
                                                new ClassPathResource("managed-hands.sql"),
                                                new ClassPathResource(
                                                        "managed-session-protocol.sql"),
                                                new ClassPathResource("managed-turn-ack.sql"),
                                                new ClassPathResource("managed-mcp-batch.sql"),
                                                new ClassPathResource(
                                                        "managed-tool-publication-lease.sql"),
                                                new ClassPathResource(
                                                        "managed-tool-submission.sql"))
                                        .populate(connection);
                            } finally {
                                DataSourceUtils.releaseConnection(connection, source);
                            }
                        });
    }

    public Session createSession(ManagedScope scope, String configuration) {
        String canonical = canonical(configuration);
        String group = admissionGroup(scope, canonical);
        jdbc.update(
                "INSERT INTO ma.admission_quota(quota_key) VALUES(?) ON CONFLICT DO NOTHING",
                group);
        try {
            return tx.execute(
                    ignored -> {
                        var existing =
                                jdbc.query(
                                        "SELECT * FROM ma.session WHERE scope_key=? FOR UPDATE",
                                        (row, index) -> session(row),
                                        scope.messageGroup());
                        if (!existing.isEmpty()) {
                            if (!existing.get(0).configuration().equals(canonical))
                                throw conflict(
                                        "session_content_conflict",
                                        "Session configuration differs");
                            return existing.get(0);
                        }
                        jdbc.update(
                                "INSERT INTO"
                                    + " ma.session(scope_key,tenant,namespace,session_id,configuration,quota_key)"
                                    + " VALUES(?,?,?,?,?,?)",
                                scope.messageGroup(),
                                scope.tenant(),
                                scope.namespace(),
                                scope.sessionId(),
                                canonical,
                                group);
                        return requireSession(scope, false);
                    });
        } catch (DataIntegrityViolationException concurrentCreation) {
            var existing =
                    jdbc.query(
                            "SELECT * FROM ma.session WHERE scope_key=?",
                            (row, index) -> session(row),
                            scope.messageGroup());
            if (existing.isEmpty()) throw concurrentCreation;
            if (!existing.get(0).configuration().equals(canonical))
                throw conflict("session_content_conflict", "Session configuration differs");
            return existing.get(0);
        }
    }

    public List<Session> sessionsById(String id) {
        ManagedScope.requireText(id, "Session id");
        return jdbc.query(
                "SELECT * FROM ma.session WHERE session_id=? LIMIT 2",
                (row, index) -> session(row),
                id);
    }

    public ManagedSessionProtocol protocol() {
        return new ManagedSessionProtocol(jdbc.getDataSource(), mapper, this);
    }

    public Session session(ManagedScope scope) {
        return requireSession(scope, false);
    }

    public Turn acceptTurn(ManagedScope scope, String actor, String key, String input) {
        return acceptTurn(scope, actor, key, input, false);
    }

    // Only a transactionally registered child may share its admitted root's concurrency slot.
    Turn acceptChildTurn(ManagedScope scope, String actor, String key, String input) {
        return acceptTurn(scope, actor, key, input, true);
    }

    private Turn acceptTurn(
            ManagedScope scope, String actor, String key, String input, boolean delegated) {
        ManagedScope.requireText(actor, "actor");
        ManagedScope.requireText(key, "idempotency key");
        String payload = canonical(input);
        String id = "turn_" + ManagedScope.fingerprint(scope.messageGroup(), actor, key);
        return tx.execute(
                ignored -> {
                    var session = requireSession(scope, true);
                    var existing = findTurn(scope, id);
                    if (!existing.isEmpty()) {
                        if (!existing.get(0).input().equals(payload))
                            throw conflict(
                                    "turn_content_conflict", "Turn idempotency content differs");
                        return existing.get(0);
                    }
                    if (!"active".equals(session.lifecycle()))
                        throw conflict("session_inactive", "Session is not active");
                    ManagedCheckpointOperations.requireUnheld(jdbc, scope);
                    if (!delegated
                            && jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM ma.subagent_call WHERE"
                                                    + " child_scope_key=?",
                                            Long.class,
                                            scope.messageGroup())
                                    > 0)
                        throw conflict(
                                "subagent_turn_requires_parent",
                                "Child Turns must be admitted by their durable parent call");
                    if (delegated
                            && jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM ma.subagent_call WHERE"
                                                    + " child_scope_key=? AND status='admitting'",
                                            Long.class,
                                            scope.messageGroup())
                                    != 1)
                        throw conflict(
                                "child_admission_missing",
                                "Child Turn needs its durable parent call");
                    String group = delegated ? sessionGroup(scope) : reserveRate(scope);
                    var quota =
                            jdbc.queryForMap(
                                    "SELECT * FROM ma.admission_quota WHERE quota_key=? FOR UPDATE",
                                    group);
                    long outstanding =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ma.turn t JOIN ma.session s ON"
                                            + " t.scope_key=s.scope_key WHERE s.quota_key=? AND NOT"
                                            + " EXISTS (SELECT 1 FROM ma.subagent_call c WHERE"
                                            + " c.child_scope_key=s.scope_key) AND t.status NOT IN"
                                            + " ('succeeded','failed','cancelled')",
                                    Long.class,
                                    group);
                    long maxConcurrent = ((Number) quota.get("max_concurrent")).longValue();
                    long budget = ((Number) quota.get("token_budget")).longValue();
                    long consumed =
                            ((Number) quota.get("baseline_tokens_used")).longValue()
                                    + ((Number) quota.get("managed_tokens_used")).longValue();
                    if (!delegated && maxConcurrent > 0 && outstanding >= maxConcurrent
                            || budget > 0 && consumed >= budget)
                        throw conflict(
                                "admission_limited",
                                "Application concurrency or token budget exceeded");
                    long queued =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ma.turn t JOIN ma.session s ON"
                                            + " t.scope_key=s.scope_key WHERE s.quota_key=? AND"
                                            + " t.status='accepted'",
                                    Long.class,
                                    group);
                    long sessionQueued =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ma.turn WHERE scope_key=? AND"
                                            + " status='accepted'",
                                    Long.class,
                                    scope.messageGroup());
                    if (queued >= limits.queuedPerGroup()
                            || sessionQueued >= limits.queuedPerSession())
                        throw conflict("admission_limited", "Managed Turn queue capacity exceeded");
                    long sequence = Math.addExact(turnHead(scope), 1);
                    jdbc.update(
                            "UPDATE ma.session SET turn_sequence=? WHERE scope_key=?",
                            sequence,
                            scope.messageGroup());
                    jdbc.update(
                            "INSERT INTO"
                                + " ma.turn(scope_key,turn_id,input_payload,actor,admission_sequence,created_at)"
                                + " VALUES(?,?,?,?,?,?)",
                            scope.messageGroup(),
                            id,
                            payload,
                            actor,
                            sequence,
                            System.currentTimeMillis());
                    return requireTurn(scope, id);
                });
    }

    private String sessionGroup(ManagedScope scope) {
        return jdbc.queryForObject(
                "SELECT quota_key FROM ma.session WHERE scope_key=?",
                String.class,
                scope.messageGroup());
    }

    // Lock before inserting a delegated Session: its quota FK otherwise takes KEY SHARE and
    // concurrent child admissions can deadlock while upgrading that lock to FOR UPDATE.
    void lockAdmissionQuota(ManagedScope scope) {
        jdbc.queryForObject(
                "SELECT quota_key FROM ma.admission_quota WHERE quota_key=? FOR UPDATE",
                String.class,
                sessionGroup(scope));
    }

    public void applicationPolicy(ManagedScope scope, Map<String, Object> policy) {
        tx.executeWithoutResult(
                ignored -> {
                    requireSession(scope, true);
                    String group =
                            jdbc.queryForObject(
                                    "SELECT quota_key FROM ma.session WHERE scope_key=?",
                                    String.class,
                                    scope.messageGroup());
                    var row =
                            jdbc.queryForMap(
                                    "SELECT * FROM ma.admission_quota WHERE quota_key=? FOR UPDATE",
                                    group);
                    long version = number(policy, "version");
                    if (version < ((Number) row.get("policy_version")).longValue()) return;
                    jdbc.update(
                            "UPDATE ma.admission_quota SET"
                                + " policy_version=?,max_concurrent=?,token_budget=?,baseline_tokens_used=?"
                                + " WHERE quota_key=?",
                            version,
                            number(policy, "maxConcurrent"),
                            number(policy, "tokenBudget"),
                            Math.max(
                                    ((Number) row.get("baseline_tokens_used")).longValue(),
                                    number(policy, "tokensUsed")),
                            group);
                });
    }

    private static long number(Map<String, Object> value, String key) {
        return value.get(key) instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }

    public Turn turn(ManagedScope scope, String turnId) {
        return requireTurn(scope, turnId);
    }

    /** Admission order is only a query cursor; execution order remains Broker publication order. */
    public long turnHead(ManagedScope scope) {
        requireSession(scope, false);
        return jdbc.queryForObject(
                "SELECT turn_sequence FROM ma.session WHERE scope_key=?",
                Long.class,
                scope.messageGroup());
    }

    public List<Turn> turns(
            ManagedScope scope, long after, boolean descending, String status, int limit) {
        return turns(scope, after, descending, status, limit, null);
    }

    public List<Turn> turns(
            ManagedScope scope,
            long after,
            boolean descending,
            String status,
            int limit,
            String eventId) {
        if (after < 0 || limit < 1 || limit > 257)
            throw new IllegalArgumentException("Invalid Turn read bounds");
        requireSession(scope, false);
        if (status != null
                && !List.of(
                                "accepted",
                                "preparing",
                                "running",
                                "waiting",
                                "waiting_tool",
                                "waiting_input",
                                "succeeded",
                                "failed",
                                "cancelled")
                        .contains(status))
            throw new IllegalArgumentException("Invalid Turn status");
        String comparison = descending ? "<" : ">";
        String order = descending ? "DESC" : "ASC";
        String sql =
                "SELECT * FROM ma.turn WHERE scope_key=? AND admission_sequence" + comparison + "?";
        if ("waiting".equals(status)) sql += " AND status IN ('waiting','waiting_input')";
        else if (status != null) sql += " AND status=?";
        var arguments = new ArrayList<Object>();
        arguments.add(scope.messageGroup());
        arguments.add(after);
        if (status != null && !"waiting".equals(status)) arguments.add(status);
        if (eventId != null) {
            sql +=
                    " AND EXISTS(SELECT 1 FROM ma.protocol_link l WHERE"
                            + " l.scope_key=ma.turn.scope_key AND l.turn_id=ma.turn.turn_id AND"
                            + " l.input_id IS NULL AND l.event_id=?)";
            arguments.add(eventId);
        }
        sql += " ORDER BY admission_sequence " + order + " LIMIT ?";
        arguments.add(limit);
        return jdbc.query(sql, (row, index) -> turn(row), arguments.toArray());
    }

    /** Called under the short Redis publication lock, immediately before sending to the Broker. */
    public Turn reservePublication(ManagedScope scope, String turnId) {
        return tx.execute(
                ignored -> {
                    var session = requireSession(scope, true);
                    var turn = requireTurn(scope, turnId);
                    if (turn.publicationSequence() != null) return turn;
                    if (session.publishingTurnId() != null)
                        throw conflict(
                                "publication_pending", "Previous Broker acceptance is unconfirmed");
                    if (jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ma.protocol_link own JOIN"
                                        + " ma.protocol_link prior ON own.scope_key=prior.scope_key"
                                        + " AND own.request_key=prior.request_key AND"
                                        + " prior.batch_index<own.batch_index JOIN ma.turn t ON"
                                        + " t.scope_key=prior.scope_key AND t.turn_id=prior.turn_id"
                                        + " WHERE own.scope_key=? AND own.turn_id=? AND"
                                        + " own.intent='new_turn' AND prior.intent='new_turn' AND"
                                        + " t.publication_sequence IS NULL",
                                    Long.class,
                                    scope.messageGroup(),
                                    turnId)
                            > 0)
                        throw conflict(
                                "publication_pending", "Earlier batch Turn is pending publication");
                    long next = Math.addExact(session.publishedSequence(), 1);
                    jdbc.update(
                            "UPDATE ma.session SET publishing_turn_id=?,published_sequence=? WHERE"
                                    + " scope_key=?",
                            turnId,
                            next,
                            scope.messageGroup());
                    jdbc.update(
                            "UPDATE ma.turn SET publication_sequence=? WHERE scope_key=? AND"
                                    + " turn_id=?",
                            next,
                            scope.messageGroup(),
                            turnId);
                    return requireTurn(scope, turnId);
                });
    }

    /** Either a send receipt or a consumer observation can finish the same publication. */
    public Turn confirmPublication(ManagedScope scope, String turnId, String brokerMessageId) {
        ManagedScope.requireText(brokerMessageId, "Broker message id");
        return tx.execute(
                ignored -> {
                    var session = requireSession(scope, true);
                    var turn = requireTurn(scope, turnId);
                    if (turn.publicationSequence() == null)
                        throw conflict(
                                "publication_not_reserved", "Turn has no publication identity");
                    jdbc.update(
                            "UPDATE ma.turn SET"
                                + " delivery_status='queued',broker_message_id=COALESCE(broker_message_id,?)"
                                + " WHERE scope_key=? AND turn_id=?",
                            brokerMessageId,
                            scope.messageGroup(),
                            turnId);
                    if (turnId.equals(session.publishingTurnId()))
                        jdbc.update(
                                "UPDATE ma.session SET publishing_turn_id=NULL WHERE scope_key=?",
                                scope.messageGroup());
                    return requireTurn(scope, turnId);
                });
    }

    /** The caller must hold the Redis execution token before establishing a new durable epoch. */
    public long activate(
            ManagedScope scope, String turnId, long publicationSequence, String token) {
        return activate(scope, turnId, publicationSequence, token, null);
    }

    public long activateResume(ManagedSuspensions.Resume resume, String token) {
        return activate(
                resume.scope(), resume.turnId(), resume.publicationSequence(), token, resume);
    }

    private long activate(
            ManagedScope scope,
            String turnId,
            long publicationSequence,
            String token,
            ManagedSuspensions.Resume resume) {
        ManagedScope.requireText(token, "execution token");
        return tx.execute(
                ignored -> {
                    var session = requireSession(scope, true);
                    var turn = requireTurn(scope, turnId);
                    if (turn.publicationSequence() == null
                            || turn.publicationSequence() != publicationSequence)
                        throw conflict(
                                "publication_mismatch", "Delivery publication identity differs");
                    if (List.of("succeeded", "failed", "cancelled").contains(turn.status()))
                        return turn.executionEpoch();
                    if (resume == null
                            && List.of("waiting_tool", "waiting_input").contains(turn.status()))
                        throw conflict(
                                "turn_suspended", "Suspended Turn requires its resume command");
                    if (publicationSequence != session.settledSequence() + 1
                            || session.currentTurnId() != null
                                    && !turnId.equals(session.currentTurnId()))
                        throw conflict(
                                "missing_predecessor", "A prior logical Turn has not settled");
                    if (turnId.equals(session.currentTurnId())
                            && token.equals(turn.executionToken())) return turn.executionEpoch();
                    if (resume != null) new ManagedSuspensions(jdbc, mapper).activate(resume);
                    long epoch = Math.addExact(session.executionEpoch(), 1);
                    jdbc.update(
                            "UPDATE ma.session SET current_turn_id=?,execution_epoch=? WHERE"
                                    + " scope_key=?",
                            turnId,
                            epoch,
                            scope.messageGroup());
                    jdbc.update(
                            "UPDATE ma.turn SET"
                                + " status='preparing',execution_epoch=?,execution_token=? WHERE"
                                + " scope_key=? AND turn_id=?",
                            epoch,
                            token,
                            scope.messageGroup(),
                            turnId);
                    return epoch;
                });
    }

    /** Durable execution identity gate; it makes no claim about SDK ACK delivery. */
    public void requireEpoch(ManagedScope scope, String turnId, long epoch) {
        requireEpoch(requireSession(scope, false), turnId, epoch);
    }

    private static void requireEpoch(Session session, String turnId, long epoch) {
        if (epoch <= 0
                || session.executionEpoch() != epoch
                || !turnId.equals(session.currentTurnId()))
            throw conflict("stale_execution", "Execution epoch is no longer current");
    }

    public Input acceptInput(
            ManagedScope scope,
            String turnId,
            String actor,
            String key,
            ManagedInputInbox.Type type,
            String actionId,
            String payload) {
        ManagedScope.requireText(actor, "actor");
        ManagedScope.requireText(key, "idempotency key");
        if (type == ManagedInputInbox.Type.REPLY)
            ManagedScope.requireText(actionId, "reply action");
        String canonical = canonical(payload);
        String id = "input_" + ManagedScope.fingerprint(scope.messageGroup(), turnId, actor, key);
        return tx.execute(
                ignored -> {
                    var session = requireSession(scope, true);
                    var turn = requireTurn(scope, turnId);
                    var old =
                            jdbc.query(
                                    "SELECT * FROM ma.input WHERE scope_key=? AND input_id=?",
                                    (row, index) -> input(row),
                                    scope.messageGroup(),
                                    id);
                    if (!old.isEmpty()) {
                        var previous = old.get(0);
                        if (previous.type() != type
                                || !previous.payload().equals(canonical)
                                || !Objects.equals(previous.actionId(), actionId))
                            throw conflict(
                                    "input_content_conflict", "Input idempotency content differs");
                        return previous;
                    }
                    if (!"active".equals(session.lifecycle()))
                        throw conflict("session_inactive", "Session is not active");
                    if (!turn.inputsOpen())
                        throw conflict("input_closed", "Turn input admission is closed");
                    if (type != ManagedInputInbox.Type.CANCEL) {
                        reserveRate(scope);
                        long pending =
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM ma.input WHERE scope_key=? AND"
                                                + " turn_id=? AND status='accepted'",
                                        Long.class,
                                        scope.messageGroup(),
                                        turnId);
                        if (pending >= limits.pendingInputs())
                            throw conflict("admission_limited", "Managed Input capacity exceeded");
                    } else if (jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ma.input WHERE scope_key=? AND turn_id=?"
                                            + " AND type='CANCEL' AND status<>'rejected'",
                                    Long.class,
                                    scope.messageGroup(),
                                    turnId)
                            > 0)
                        throw conflict(
                                "cancel_already_requested",
                                "Cancellation is already durably accepted");
                    if (type == ManagedInputInbox.Type.REPLY) {
                        long open =
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM ma.action WHERE scope_key=? AND"
                                                + " turn_id=? AND action_id=? AND status='open'",
                                        Long.class,
                                        scope.messageGroup(),
                                        turnId,
                                        actionId);
                        if (open != 1)
                            throw conflict(
                                    "action_not_pending",
                                    "Reply does not reference an open action");
                        long pending =
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM ma.input WHERE scope_key=? AND"
                                                + " turn_id=? AND action_id=? AND type='REPLY' AND"
                                                + " status='accepted'",
                                        Long.class,
                                        scope.messageGroup(),
                                        turnId,
                                        actionId);
                        if (pending != 0)
                            throw conflict(
                                    "action_reply_pending",
                                    "An accepted reply already awaits application");
                    }
                    long sequence = Math.addExact(turn.inputSequence(), 1);
                    jdbc.update(
                            "UPDATE ma.turn SET input_sequence=? WHERE scope_key=? AND turn_id=?",
                            sequence,
                            scope.messageGroup(),
                            turnId);
                    jdbc.update(
                            "INSERT INTO"
                                + " ma.input(scope_key,input_id,turn_id,input_sequence,type,actor,action_id,payload)"
                                + " VALUES(?,?,?,?,?,?,?,?)",
                            scope.messageGroup(),
                            id,
                            turnId,
                            sequence,
                            type.name(),
                            actor,
                            actionId,
                            canonical);
                    if (type == ManagedInputInbox.Type.REPLY
                            || type == ManagedInputInbox.Type.CANCEL)
                        ManagedSuspensions.inputReady(jdbc, scope, turnId);
                    return new Input(
                            id,
                            turnId,
                            sequence,
                            type,
                            actionId,
                            canonical,
                            actor,
                            "accepted",
                            "pending",
                            null);
                });
    }

    public Input input(ManagedScope scope, String id) {
        var rows =
                jdbc.query(
                        "SELECT * FROM ma.input WHERE scope_key=? AND input_id=?",
                        (row, index) -> input(row),
                        scope.messageGroup(),
                        id);
        if (rows.isEmpty())
            throw conflict("input_not_found", "Input does not exist in this Session");
        return rows.get(0);
    }

    public List<Input> inputsAfter(ManagedScope scope, String turnId, long sequence, int limit) {
        return inputsAfter(scope, turnId, sequence, limit, null, null);
    }

    public List<Input> inputsAfter(
            ManagedScope scope,
            String turnId,
            long sequence,
            int limit,
            String status,
            String eventId) {
        if (sequence < 0 || limit < 1 || limit > 257)
            throw new IllegalArgumentException("Invalid Input read bounds");
        requireTurn(scope, turnId);
        String sql = "SELECT * FROM ma.input WHERE scope_key=? AND turn_id=? AND input_sequence>?";
        var arguments = new ArrayList<Object>();
        arguments.add(scope.messageGroup());
        arguments.add(turnId);
        arguments.add(sequence);
        if (status != null) {
            sql += " AND status=?";
            arguments.add(status);
        }
        if (eventId != null) {
            sql +=
                    " AND EXISTS(SELECT 1 FROM ma.protocol_link l WHERE"
                            + " l.scope_key=ma.input.scope_key AND l.input_id=ma.input.input_id AND"
                            + " l.event_id=?)";
            arguments.add(eventId);
        }
        sql += " ORDER BY input_sequence LIMIT ?";
        arguments.add(limit);
        return jdbc.query(sql, (row, index) -> input(row), arguments.toArray());
    }

    public List<Input> pendingInputs(ManagedScope scope, String turnId, int limit) {
        if (limit < 1 || limit > 256) throw new IllegalArgumentException("Invalid Input page size");
        return jdbc.query(
                "SELECT * FROM ma.input WHERE scope_key=? AND turn_id=? AND status='accepted' ORDER"
                        + " BY input_sequence LIMIT ?",
                (row, index) -> input(row),
                scope.messageGroup(),
                turnId,
                limit);
    }

    public record Publication(ManagedScope scope, String turnId) {}

    /** Durable outbox scan. Deliberately does not impose API acceptance order on MQ publication. */
    public List<Publication> pendingPublications(int limit) {
        if (limit < 1 || limit > 256)
            throw new IllegalArgumentException("Invalid publication limit");
        return jdbc.query(
                "SELECT s.tenant,s.namespace,s.session_id,t.turn_id FROM ma.turn t JOIN ma.session"
                    + " s ON s.scope_key=t.scope_key WHERE t.delivery_status='pending' AND"
                    + " (s.publishing_turn_id IS NULL OR s.publishing_turn_id=t.turn_id) ORDER BY"
                    + " CASE WHEN s.publishing_turn_id=t.turn_id THEN 0 ELSE 1 END LIMIT ?",
                (row, index) ->
                        new Publication(
                                new ManagedScope(
                                        row.getString("tenant"),
                                        row.getString("namespace"),
                                        row.getString("session_id")),
                                row.getString("turn_id")),
                limit);
    }

    public void confirmInputDelivery(ManagedScope scope, String inputId) {
        jdbc.update(
                "UPDATE ma.input SET delivery_status='delivered' WHERE scope_key=? AND input_id=?",
                scope.messageGroup(),
                inputId);
    }

    /** Same Session lock as admission: the returned cutoff cannot miss an accepted concurrent input. */
    public long closeInputs(ManagedScope scope, String turnId, long epoch) {
        return tx.execute(
                ignored -> {
                    var session = requireSession(scope, true);
                    requireEpoch(session, turnId, epoch);
                    var turn = requireTurn(scope, turnId);
                    jdbc.update(
                            "UPDATE ma.turn SET inputs_open=FALSE WHERE scope_key=? AND turn_id=?",
                            scope.messageGroup(),
                            turnId);
                    return turn.inputSequence();
                });
    }

    private Session requireSession(ManagedScope scope, boolean lock) {
        var rows =
                jdbc.query(
                        "SELECT * FROM ma.session WHERE scope_key=?" + (lock ? " FOR UPDATE" : ""),
                        (row, index) -> session(row),
                        scope.messageGroup());
        if (rows.isEmpty())
            throw conflict("session_not_found", "Session does not exist in this scope");
        if (!scope.equals(rows.get(0).scope()))
            throw conflict("session_scope_mismatch", "Session scope differs");
        return rows.get(0);
    }

    private Turn requireTurn(ManagedScope scope, String id) {
        var rows = findTurn(scope, id);
        if (rows.isEmpty()) throw conflict("turn_not_found", "Turn does not exist in this Session");
        return rows.get(0);
    }

    private List<Turn> findTurn(ManagedScope scope, String id) {
        return jdbc.query(
                "SELECT * FROM ma.turn WHERE scope_key=? AND turn_id=?",
                (row, index) -> turn(row),
                scope.messageGroup(),
                id);
    }

    private String admissionGroup(ManagedScope scope, String configuration) {
        try {
            var value = mapper.readTree(configuration);
            String application = value.path("application_id").asText("");
            String owner = value.path("owner_id").asText(scope.sessionId());
            return ManagedScope.fingerprint(
                    scope.tenant(),
                    scope.namespace(),
                    application.isBlank() ? "user:" + owner : "application:" + application);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid Session configuration", invalid);
        }
    }

    private String reserveRate(ManagedScope scope) {
        String group =
                jdbc.queryForObject(
                        "SELECT quota_key FROM ma.session WHERE scope_key=?",
                        String.class,
                        scope.messageGroup());
        var quota =
                jdbc.queryForMap(
                        "SELECT * FROM ma.admission_quota WHERE quota_key=? FOR UPDATE", group);
        long second = System.currentTimeMillis() / 1000;
        long count =
                ((Number) quota.get("window_second")).longValue() == second
                        ? ((Number) quota.get("requests")).longValue()
                        : 0;
        if (count >= limits.requestsPerSecond())
            throw conflict("admission_limited", "Managed request rate exceeded");
        jdbc.update(
                "UPDATE ma.admission_quota SET window_second=?,requests=? WHERE quota_key=?",
                second,
                count + 1,
                group);
        return group;
    }

    private static Session session(ResultSet row) throws SQLException {
        return new Session(
                new ManagedScope(
                        row.getString("tenant"),
                        row.getString("namespace"),
                        row.getString("session_id")),
                row.getString("configuration"),
                row.getLong("execution_epoch"),
                row.getString("current_turn_id"),
                row.getString("publishing_turn_id"),
                row.getLong("published_sequence"),
                row.getLong("settled_sequence"),
                row.getString("lifecycle"),
                row.getLong("lifecycle_revision"),
                row.getString("lifecycle_command_id"));
    }

    private static Turn turn(ResultSet row) throws SQLException {
        long sequence = row.getLong("publication_sequence");
        Long publication = row.wasNull() ? null : sequence;
        return new Turn(
                row.getString("turn_id"),
                row.getString("actor"),
                row.getString("input_payload"),
                publication,
                row.getString("delivery_status"),
                row.getString("status"),
                row.getLong("execution_epoch"),
                row.getString("execution_token"),
                row.getBoolean("inputs_open"),
                row.getLong("input_sequence"),
                row.getLong("admission_sequence"),
                row.getLong("created_at"));
    }

    private static Input input(ResultSet row) throws SQLException {
        return new Input(
                row.getString("input_id"),
                row.getString("turn_id"),
                row.getLong("input_sequence"),
                ManagedInputInbox.Type.valueOf(row.getString("type")),
                row.getString("action_id"),
                row.getString("payload"),
                row.getString("actor"),
                row.getString("status"),
                row.getString("delivery_status"),
                row.getString("rejection_reason"));
    }

    private String canonical(String value) {
        try {
            Object parsed = mapper.readValue(value, Object.class);
            if (!(parsed instanceof Map<?, ?>))
                throw new IllegalArgumentException("Managed payload must be an object");
            return mapper.writeValueAsString(parsed);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid Managed JSON payload", invalid);
        }
    }

    private static ManagedStateException conflict(String code, String message) {
        return new ManagedStateException(code, message);
    }
}
