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
package io.agentscope.builder.runtime.managed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic parent ToolTask -> child Session/Turn admission. No live Agent or callback is stored. */
public final class ManagedSubagents {
    public static final String OPERATION = "subagent.call";
    private static final String SELECT =
            "SELECT c.*,p.tenant,p.namespace,p.session_id AS parent_session_id,r.session_id AS"
                + " root_session_id,s.session_id AS child_session_id FROM ma.subagent_call c JOIN"
                + " ma.session p ON p.scope_key=c.parent_scope_key JOIN ma.session r ON"
                + " r.scope_key=c.root_scope_key LEFT JOIN ma.session s ON"
                + " s.scope_key=c.child_scope_key";

    public record Specification(
            String agentId,
            String prompt,
            String resultHeader,
            String frozenDefinition,
            String childSessionId) {
        public Specification(
                String agentId, String prompt, String resultHeader, String frozenDefinition) {
            this(agentId, prompt, resultHeader, frozenDefinition, null);
        }

        public Specification {
            ManagedScope.requireText(agentId, "child Agent id");
            requirePayload(prompt, "child prompt");
            requirePayload(frozenDefinition, "frozen child definition");
            if (agentId.length() > 256)
                throw new IllegalArgumentException("Child Agent id is too long");
        }

        private static void requirePayload(String value, String name) {
            if (value == null
                    || value.isBlank()
                    || value.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024)
                throw new IllegalArgumentException(name + " must be nonblank and at most 1 MiB");
        }
    }

    public record Call(
            ManagedScope parent,
            String parentTurnId,
            String taskId,
            ManagedScope root,
            String rootTurnId,
            ManagedScope child,
            String childTurnId,
            Specification specification,
            String status) {}

    public record Reply(String status, String messageJson, String reason) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ManagedAdmissionStore admission;
    private final ManagedJournalStore journal;
    private final int capacity;

    public ManagedSubagents(
            DataSource source,
            ObjectMapper mapper,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal) {
        this(source, mapper, admission, journal, 256);
    }

    public ManagedSubagents(
            DataSource source,
            ObjectMapper mapper,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal,
            int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Child capacity must be positive");
        this.jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.admission = admission;
        this.journal = journal;
        this.capacity = capacity;
    }

    public Optional<Call> find(ManagedScope parent, String taskId) {
        return jdbc
                .query(
                        SELECT + " WHERE c.parent_scope_key=? AND c.task_id=?",
                        this::read,
                        parent.messageGroup(),
                        taskId)
                .stream()
                .findFirst();
    }

    public Optional<Call> parent(ManagedScope child) {
        return jdbc
                .query(
                        SELECT + " WHERE c.child_scope_key=? ORDER BY c.created_at,c.task_id",
                        this::read,
                        child.messageGroup())
                .stream()
                .findFirst();
    }

    public Optional<Call> parent(ManagedScope child, String turn) {
        return jdbc
                .query(
                        SELECT + " WHERE c.child_scope_key=? AND c.child_turn_id=?",
                        this::read,
                        child.messageGroup(),
                        turn)
                .stream()
                .findFirst();
    }

    /** Replaying a committed call returns its original identity, including a cancelled-before-start fact. */
    public Call start(ManagedScope parent, String taskId) {
        return tx.execute(
                ignored -> {
                    var original =
                            journal.tool(parent, taskId)
                                    .orElseThrow(
                                            () ->
                                                    failure(
                                                            "subagent_task_missing",
                                                            "Child call has no durable parent"
                                                                    + " ToolTask"));
                    var ancestor = parent(parent, original.turnId()).orElse(null);
                    var root = ancestor == null ? parent : ancestor.root();
                    jdbc.update(
                            "INSERT INTO ma.subagent_tree(root_scope_key) VALUES(?) ON CONFLICT DO"
                                    + " NOTHING",
                            root.messageGroup());
                    jdbc.queryForObject(
                            "SELECT root_scope_key FROM ma.subagent_tree WHERE root_scope_key=? FOR"
                                    + " UPDATE",
                            String.class,
                            root.messageGroup());
                    // Budget commits lock the child Session before its root Session.
                    lock(parent);
                    if (!root.equals(parent)) lock(root);
                    var task =
                            journal.tool(parent, taskId)
                                    .orElseThrow(
                                            () ->
                                                    failure(
                                                            "subagent_task_missing",
                                                            "Child call has no durable parent"
                                                                    + " ToolTask"));
                    if (!OPERATION.equals(task.name()))
                        throw failure(
                                "subagent_operation_mismatch", "ToolTask is not a child call");
                    var specification = specification(task.arguments());
                    var existing = find(parent, taskId);
                    if (existing.isPresent()) {
                        if (!existing.get().parentTurnId().equals(task.turnId())
                                || !existing.get().specification().equals(specification))
                            throw failure(
                                    "subagent_content_conflict",
                                    "Original child call content changed");
                        return existing.get();
                    }
                    var parentTurn = admission.turn(parent, task.turnId());
                    String rootTurn = ancestor == null ? task.turnId() : ancestor.rootTurnId();
                    if (journal.cancelRequested(parent, task.turnId())
                            || journal.cancelRequested(root, rootTurn)
                            || !List.of(
                                            "preparing",
                                            "running",
                                            "waiting",
                                            "waiting_tool",
                                            "waiting_input")
                                    .contains(parentTurn.status())
                            || task.result() != null) {
                        jdbc.update(
                                "INSERT INTO"
                                    + " ma.subagent_call(parent_scope_key,task_id,root_scope_key,parent_turn_id,root_turn_id,agent_id,specification,status,created_at)"
                                    + " VALUES(?,?,?,?,?,?,?,'cancelled',?)",
                                parent.messageGroup(),
                                taskId,
                                root.messageGroup(),
                                task.turnId(),
                                rootTurn,
                                specification.agentId(),
                                json(specification),
                                System.currentTimeMillis());
                        return find(parent, taskId).orElseThrow();
                    }
                    long active =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ma.subagent_call c LEFT JOIN ma.turn t ON"
                                        + " t.scope_key=c.child_scope_key AND"
                                        + " t.turn_id=c.child_turn_id WHERE c.root_scope_key=? AND"
                                        + " c.status<>'cancelled' AND (t.status IS NULL OR t.status"
                                        + " NOT IN ('succeeded','failed','cancelled'))",
                                    Long.class,
                                    root.messageGroup());
                    if (active >= capacity)
                        throw failure(
                                "subagent_capacity_exceeded",
                                "Root child-call capacity is exhausted");
                    var child =
                            new ManagedScope(
                                    parent.tenant(),
                                    parent.namespace(),
                                    specification.childSessionId() == null
                                            ? UUID.nameUUIDFromBytes(
                                                            (parent.messageGroup() + ":" + taskId)
                                                                    .getBytes(
                                                                            StandardCharsets.UTF_8))
                                                    .toString()
                                            : UUID.fromString(specification.childSessionId())
                                                    .toString());
                    var configuration =
                            (ObjectNode) readObject(admission.session(root).configuration());
                    var parentConfiguration = readObject(admission.session(parent).configuration());
                    int depth = parentConfiguration.path("subagent").path("depth").asInt(0) + 1;
                    if (depth > ManagedTurnLanes.MAX_DEPTH)
                        throw failure("subagent_depth_exceeded", "Child-call depth exceeds 16");
                    configuration.put(
                            "owner_id", configuration.path("owner_id").asText(root.sessionId()));
                    configuration.put("root_session_id", root.sessionId());
                    configuration.put("parent_session_id", parent.sessionId());
                    configuration.put("parent_turn_id", task.turnId());
                    configuration.set(
                            "subagent",
                            mapper.valueToTree(
                                    Map.of(
                                            "depth",
                                            depth,
                                            "parent_scope_key",
                                            parent.messageGroup(),
                                            "parent_task_id",
                                            taskId,
                                            "root_turn_id",
                                            rootTurn,
                                            "specification",
                                            specification)));
                    admission.lockAdmissionQuota(root);
                    var known =
                            admission.sessionsById(child.sessionId()).stream()
                                    .filter(value -> child.equals(value.scope()))
                                    .findFirst();
                    if (known.isEmpty()) admission.createSession(child, json(configuration));
                    else {
                        var first =
                                parent(child)
                                        .orElseThrow(
                                                () ->
                                                        failure(
                                                                "subagent_identity_conflict",
                                                                "Existing child Session has no"
                                                                        + " original parent"));
                        if (!first.parent().equals(parent)
                                || !first.root().equals(root)
                                || !first.specification().agentId().equals(specification.agentId())
                                || !first.specification()
                                        .frozenDefinition()
                                        .equals(specification.frozenDefinition()))
                            throw failure(
                                    "subagent_identity_conflict",
                                    "Child Session belongs to a different definition or parent");
                    }
                    jdbc.update(
                            "INSERT INTO"
                                + " ma.subagent_call(parent_scope_key,task_id,root_scope_key,parent_turn_id,root_turn_id,child_scope_key,agent_id,specification,status,created_at)"
                                + " VALUES(?,?,?,?,?,?,?,?,'admitting',?)",
                            parent.messageGroup(),
                            taskId,
                            root.messageGroup(),
                            task.turnId(),
                            rootTurn,
                            child.messageGroup(),
                            specification.agentId(),
                            json(specification),
                            System.currentTimeMillis());
                    var turn =
                            admission.acceptChildTurn(
                                    child,
                                    parentTurn.actor(),
                                    "subagent:" + taskId,
                                    json(Map.of("message", specification.prompt())));
                    jdbc.update(
                            "UPDATE ma.subagent_call SET child_turn_id=?,status='submitted' WHERE"
                                    + " parent_scope_key=? AND task_id=?",
                            turn.id(),
                            parent.messageGroup(),
                            taskId);
                    return find(parent, taskId).orElseThrow();
                });
    }

    private Specification specification(String arguments) {
        try {
            var node = readObject(arguments).get("subagent");
            if (node == null || !node.isObject())
                throw failure(
                        "subagent_specification_missing", "Child call has no frozen specification");
            var specification = mapper.treeToValue(node, Specification.class);
            if (!readObject(specification.frozenDefinition()).isObject())
                throw failure("subagent_definition_invalid", "Child definition must be an object");
            return specification;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid child specification", error);
        }
    }

    private ObjectNode readObject(String json) {
        try {
            var node = mapper.readTree(json);
            if (!(node instanceof ObjectNode object))
                throw failure(
                        "subagent_definition_invalid", "Child configuration must be an object");
            return object;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid child configuration", error);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode child call", error);
        }
    }

    private void lock(ManagedScope scope) {
        jdbc.queryForObject(
                "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                String.class,
                scope.messageGroup());
    }

    private Call read(ResultSet row, int index) throws SQLException {
        var tenant = row.getString("tenant");
        var namespace = row.getString("namespace");
        var child = row.getString("child_session_id");
        try {
            return new Call(
                    new ManagedScope(tenant, namespace, row.getString("parent_session_id")),
                    row.getString("parent_turn_id"),
                    row.getString("task_id"),
                    new ManagedScope(tenant, namespace, row.getString("root_session_id")),
                    row.getString("root_turn_id"),
                    child == null ? null : new ManagedScope(tenant, namespace, child),
                    row.getString("child_turn_id"),
                    mapper.readValue(row.getString("specification"), Specification.class),
                    row.getString("status"));
        } catch (JsonProcessingException invalid) {
            throw new SQLException("Invalid persisted child specification", invalid);
        }
    }

    private static ManagedStateException failure(String code, String message) {
        return new ManagedStateException(code, message);
    }
}
