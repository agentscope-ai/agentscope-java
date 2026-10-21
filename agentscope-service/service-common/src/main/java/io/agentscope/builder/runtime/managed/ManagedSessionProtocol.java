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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.builder.runtime.managed.ManagedInputInbox.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Public protocol ledger. Every write joins the execution ledger's datasource transaction. */
public final class ManagedSessionProtocol {
    public static final String REVISION = "agentscope-service-2026-10-19-draft3";
    public static final String EXTENSION = "agentscope_service";

    public record Command(
            String type,
            List<Map<String, Object>> content,
            String intent,
            String turnId,
            boolean explicit) {}

    public record Acceptance(Map<String, Object> receipt, boolean replayed) {}

    public record Change(long sequence, Map<String, Object> event) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ManagedAdmissionStore admission;
    private final TransactionTemplate tx;

    ManagedSessionProtocol(
            DataSource source, ObjectMapper mapper, ManagedAdmissionStore admission) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.admission = admission;
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
    }

    public Acceptance accept(
            ManagedScope scope,
            String actor,
            String key,
            List<Command> commands,
            Runnable validate) {
        String request = ManagedScope.fingerprint("events", actor, key);
        String digest = json(mapper, Map.of("revision", REVISION, "events", commands));
        return tx.execute(
                ignored -> {
                    // Serialize before checking an existing key or any mutable target state.
                    jdbc.queryForObject(
                            "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                            String.class,
                            scope.messageGroup());
                    ensure(jdbc, scope);
                    var old =
                            jdbc.query(
                                    "SELECT digest,receipt FROM ma.protocol_request WHERE"
                                            + " scope_key=? AND request_key=?",
                                    (row, index) ->
                                            Map.of(
                                                    "digest",
                                                    row.getString(1),
                                                    "receipt",
                                                    row.getString(2)),
                                    scope.messageGroup(),
                                    request);
                    if (!old.isEmpty()) {
                        if (!digest.equals(old.get(0).get("digest")))
                            throw error("idempotency_conflict", "Request content differs");
                        return new Acceptance(read(mapper, old.get(0).get("receipt")), true);
                    }
                    validate.run();
                    var session = admission.session(scope);
                    if (!"active".equals(session.lifecycle()))
                        throw error("session_inactive", "Session is not active");
                    var targets = new ArrayList<String>();
                    for (var command : commands) {
                        String target = command.turnId();
                        if ("interrupt".equals(command.intent())) target = session.currentTurnId();
                        targets.add(target);
                        if (target != null) {
                            var targetTurn = admission.turn(scope, target);
                            if ("cancel".equals(command.intent())
                                    && Set.of("succeeded", "failed", "cancelled")
                                            .contains(targetTurn.status()))
                                throw error("turn_terminal", "The target Turn is already terminal");
                        }
                    }
                    for (int i = 0; i < commands.size(); i++) {
                        if (!Set.of("cancel", "interrupt").contains(commands.get(i).intent())
                                || targets.get(i) == null) continue;
                        for (int j = 0; j < commands.size(); j++)
                            if (Set.of("steer", "inject").contains(commands.get(j).intent())
                                    && targets.get(i).equals(targets.get(j)))
                                throw error(
                                        "ambiguous_batch",
                                        "Cancellation and new input target the same Turn");
                    }
                    var data = new ArrayList<Map<String, Object>>();
                    boolean interrupted = false;
                    for (int index = 0; index < commands.size(); index++) {
                        var command = commands.get(index);
                        String intent = command.intent();
                        String turn = targets.get(index);
                        String input = null;
                        String eventId =
                                "evt_"
                                        + ManagedScope.fingerprint(
                                                scope.messageGroup(),
                                                request,
                                                String.valueOf(index));
                        long now = System.currentTimeMillis();
                        String subkey = request + ":" + index;
                        var extension = new LinkedHashMap<String, Object>();
                        extension.put("schema_version", 1);
                        extension.put("session_id", scope.sessionId());
                        extension.put("admitted_at", time(now));
                        extension.put("event_id", eventId);
                        extension.put("status", "accepted");
                        if ("new_turn".equals(intent) || "message".equals(intent)) {
                            if ("message".equals(intent)
                                    && !interrupted
                                    && (session.currentTurnId() != null
                                            || !admission
                                                    .turns(scope, 0, false, "accepted", 1)
                                                    .isEmpty()))
                                throw error(
                                        "unsupported_capability",
                                        "Ordinary messages require an idle Session");
                            var accepted =
                                    admission.acceptTurn(
                                            scope,
                                            actor,
                                            subkey,
                                            json(mapper, nativeInput(command)));
                            turn = accepted.id();
                            extension.put(
                                    "receipt",
                                    Map.of("type", "turn", "id", turn, "status", "accepted"));
                        } else if (turn != null) {
                            Type kind =
                                    switch (intent) {
                                        case "steer" -> Type.STEER;
                                        case "inject" -> Type.INJECT;
                                        default -> Type.CANCEL;
                                    };
                            var accepted =
                                    admission.acceptInput(
                                            scope,
                                            turn,
                                            actor,
                                            subkey,
                                            kind,
                                            null,
                                            json(
                                                    mapper,
                                                    kind == Type.CANCEL
                                                            ? Map.of()
                                                            : nativeInput(command)));
                            input = accepted.id();
                            extension.put("input_id", input);
                            extension.put(
                                    "receipt",
                                    Map.of("type", "input", "id", input, "status", "accepted"));
                        }
                        if (turn != null) extension.put("turn_id", turn);
                        if (input == null) extension.put("input_event_ids", List.of(eventId));
                        else extension.put("input_type", intent);
                        extension.put(
                                "intent",
                                command.explicit()
                                        ? intent
                                        : "user.interrupt".equals(command.type())
                                                ? "interrupt"
                                                : "new_turn");
                        Long processed = "interrupt".equals(intent) && turn == null ? now : null;
                        var event = new LinkedHashMap<String, Object>();
                        event.put("id", eventId);
                        event.put("type", command.type());
                        if ("user.message".equals(command.type()))
                            event.put("content", command.content());
                        event.put("processed_at", processed == null ? null : time(processed));
                        event.put(EXTENSION, extension);
                        String messageId =
                                "user.message".equals(command.type())
                                        ? "command_" + (input == null ? turn : input) + "_0"
                                        : null;
                        jdbc.update(
                                "INSERT INTO"
                                    + " ma.protocol_link(scope_key,event_id,request_key,batch_index,turn_id,input_id,intent,message_id,admitted_at,processed_at)"
                                    + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                                scope.messageGroup(),
                                eventId,
                                request,
                                index,
                                turn,
                                input,
                                "message".equals(intent) ? "new_turn" : intent,
                                messageId,
                                now,
                                processed);
                        put(jdbc, mapper, scope, eventId, event, messageId);
                        if (turn != null)
                            emit(
                                    jdbc,
                                    mapper,
                                    scope,
                                    eventId + ":accepted",
                                    input == null ? "turn.accepted" : "input.accepted",
                                    extension);
                        data.add(event);
                        if ("interrupt".equals(intent)) interrupted = true;
                    }
                    var receipt = Map.<String, Object>of("data", data);
                    jdbc.update(
                            "INSERT INTO ma.protocol_request(scope_key,request_key,digest,receipt)"
                                    + " VALUES(?,?,?,?)",
                            scope.messageGroup(),
                            request,
                            digest,
                            json(mapper, receipt));
                    return new Acceptance(receipt, false);
                });
    }

    private static Map<String, Object> nativeInput(Command command) {
        return Map.of("input", List.of(Map.of("role", "user", "content", command.content())));
    }

    public Map<String, Object> receipt(
            Map<String, Object> receipt, List<Command> commands, boolean extended) {
        var values = list(receipt.get("data"));
        var result = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < values.size(); index++)
            result.add(
                    represent(map(values.get(index)), extended || commands.get(index).explicit()));
        return Map.of("data", result);
    }

    public String creationSession(String key) {
        var values =
                jdbc.query(
                        "SELECT s.session_id FROM ma.protocol_creation c JOIN ma.session s ON"
                                + " c.scope_key=s.scope_key WHERE c.request_key=?",
                        (row, index) -> row.getString(1),
                        key);
        return values.isEmpty() ? null : values.get(0);
    }

    public Map<String, Object> creation(String key, String digest) {
        var values =
                jdbc.query(
                        "SELECT digest,receipt FROM ma.protocol_creation WHERE request_key=?",
                        (row, index) ->
                                Map.of("digest", row.getString(1), "receipt", row.getString(2)),
                        key);
        if (values.isEmpty()) return null;
        if (!digest.equals(values.get(0).get("digest")))
            throw error("idempotency_conflict", "Session creation content differs");
        return read(mapper, values.get(0).get("receipt"));
    }

    public Map<String, Object> freezeCreation(
            ManagedScope scope, String key, String digest, Map<String, Object> receipt) {
        jdbc.update(
                "INSERT INTO ma.protocol_creation(request_key,scope_key,digest,receipt)"
                        + " VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
                key,
                scope.messageGroup(),
                digest,
                json(mapper, receipt));
        return creation(key, digest);
    }

    public long head(ManagedScope scope) {
        var values =
                jdbc.query(
                        "SELECT sequence FROM ma.protocol_session WHERE scope_key=?",
                        (row, index) -> row.getLong(1),
                        scope.messageGroup());
        return values.isEmpty() ? 0 : values.get(0);
    }

    public String status(ManagedScope scope) {
        var values =
                jdbc.query(
                        "SELECT status FROM ma.protocol_session WHERE scope_key=?",
                        (row, index) -> row.getString(1),
                        scope.messageGroup());
        return values.isEmpty() ? "idle" : values.get(0);
    }

    public List<Change> changes(ManagedScope scope, long after, int limit) {
        return jdbc.query(
                "SELECT sequence,payload FROM ma.protocol_event WHERE scope_key=? AND sequence>?"
                        + " ORDER BY sequence LIMIT ?",
                (row, index) -> new Change(row.getLong(1), read(mapper, row.getString(2))),
                scope.messageGroup(),
                after,
                limit);
    }

    public Map<String, Object> history(
            ManagedScope scope, String actor, Map<String, List<String>> query, boolean extended) {
        Set<String> allowed =
                Set.of(
                        "beta",
                        "page",
                        "limit",
                        "order",
                        "types[]",
                        "created_at[gt]",
                        "created_at[gte]",
                        "created_at[lt]",
                        "created_at[lte]");
        if (!allowed.containsAll(query.keySet()))
            throw error("invalid_cursor", "Unknown history query");
        if (query.containsKey("beta") && !List.of("true").equals(query.get("beta")))
            throw error("invalid_cursor", "Invalid beta transport flag");
        int limit = limit(first(query, "limit", "100"));
        String order = first(query, "order", "asc");
        if (!Set.of("asc", "desc").contains(order)) throw error("invalid_cursor", "Invalid order");
        var filters = new LinkedHashMap<>(query);
        filters.remove("beta");
        filters.remove("page");
        filters.remove("limit");
        filters.put("order", List.of(order));
        // Output selection never changes the standard history's read boundary or filter semantics.
        String binding =
                ManagedScope.fingerprint(
                        REVISION,
                        scope.messageGroup(),
                        actor,
                        json(mapper, filters),
                        String.valueOf(extended));
        long through = head(scope);
        long offset = 0;
        String page = first(query, "page", null);
        if (page != null) {
            var cursor = cursor(page, binding);
            if (!(cursor.get("through") instanceof Integer
                    || cursor.get("through") instanceof Long))
                throw error("invalid_cursor", "Invalid history boundary");
            through = ((Number) cursor.get("through")).longValue();
            offset = ((Number) cursor.get("offset")).longValue();
            if (through < 0 || through > head(scope) || offset < 0)
                throw error("invalid_cursor", "Invalid history boundary");
        }
        var args = new ArrayList<Object>();
        args.add(scope.messageGroup());
        args.add(through);
        args.add(through);
        String sql =
                "SELECT e.payload FROM ma.protocol_event e WHERE e.scope_key=? AND e.sequence<=?"
                    + " AND NOT EXISTS(SELECT 1 FROM ma.protocol_event n WHERE"
                    + " n.scope_key=e.scope_key AND n.event_id=e.event_id AND n.sequence>e.sequence"
                    + " AND n.sequence<=?)";
        if (!extended) sql += " AND e.event_type NOT LIKE 'agentscope_service.%'";
        List<String> types = query.getOrDefault("types[]", List.of());
        if (!types.isEmpty()) {
            sql +=
                    " AND e.event_type IN ("
                            + String.join(",", Collections.nCopies(types.size(), "?"))
                            + ")";
            args.addAll(types);
        }
        for (var operator : Map.of("gt", ">", "gte", ">=", "lt", "<", "lte", "<=").entrySet()) {
            String value = first(query, "created_at[" + operator.getKey() + "]", null);
            if (value != null) {
                try {
                    Instant instant = Instant.parse(value);
                    long millis = instant.toEpochMilli();
                    if (instant.getNano() % 1000000 != 0
                            && Set.of("gte", "lt").contains(operator.getKey()))
                        millis = Math.addExact(millis, 1);
                    args.add(millis);
                } catch (RuntimeException invalid) {
                    throw error("invalid_cursor", "Invalid time filter");
                }
                sql += " AND e.processed_at" + operator.getValue() + "?";
            }
        }
        sql +=
                " ORDER BY e.processed_at "
                        + order
                        + " NULLS FIRST,e.event_id "
                        + order
                        + " LIMIT ? OFFSET ?";
        args.add(limit + 1);
        args.add(offset);
        var values =
                jdbc.query(
                        sql,
                        (row, index) -> represent(read(mapper, row.getString(1)), extended),
                        args.toArray());
        boolean more = values.size() > limit;
        var result = new LinkedHashMap<String, Object>();
        result.put("data", values.subList(0, Math.min(limit, values.size())));
        result.put(
                "next_page",
                more
                        ? encode(
                                Map.of(
                                        "binding",
                                        binding,
                                        "through",
                                        through,
                                        "offset",
                                        offset + limit))
                        : null);
        return result;
    }

    public Map<String, Object> output(ManagedScope scope, String turn, String after, int limit) {
        if (limit < 1 || limit > 256) throw error("invalid_cursor", "limit must be 1..256");
        var state = admission.turn(scope, turn);
        var outputs =
                jdbc.query(
                        "SELECT event_ids FROM ma.protocol_output WHERE scope_key=? AND turn_id=?"
                                + " AND sealed=TRUE",
                        (row, index) -> row.getString(1),
                        scope.messageGroup(),
                        turn);
        List<Object> ids = outputs.isEmpty() ? List.of() : list(parse(mapper, outputs.get(0)));
        String binding =
                ManagedScope.fingerprint("output", scope.messageGroup(), turn, json(mapper, ids));
        long requestedOffset =
                after == null ? 0 : ((Number) cursor(after, binding).get("offset")).longValue();
        if (requestedOffset < 0 || requestedOffset > ids.size())
            throw error("invalid_cursor", "Invalid output cursor");
        int offset = (int) requestedOffset;
        var data = new ArrayList<Map<String, Object>>();
        int end = Math.min(ids.size(), offset + limit);
        for (Object id : ids.subList(offset, end)) {
            var event = latest(jdbc, mapper, scope, String.valueOf(id));
            if (event == null)
                throw error("service_unavailable", "Final output projection is not readable");
            data.add(event);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("type", "turn_output");
        result.put("session_id", scope.sessionId());
        result.put("turn_id", turn);
        result.put("turn_status", ManagedJournalStore.publicTurnStatus(state.status()));
        result.put("output_available", !ids.isEmpty());
        result.put("output_event_ids", ids.subList(offset, end));
        result.put("data", data);
        result.put(
                "next_cursor",
                end < ids.size() ? encode(Map.of("binding", binding, "offset", end)) : null);
        result.put("has_more", end < ids.size());
        return result;
    }

    public Map<String, Object> link(ManagedScope scope, String turn, String input) {
        String sql =
                "SELECT event_id,admitted_at,processed_at FROM ma.protocol_link WHERE scope_key=?"
                        + " AND turn_id=? AND "
                        + (input == null ? "input_id IS NULL" : "input_id=?");
        Object[] args =
                input == null
                        ? new Object[] {scope.messageGroup(), turn}
                        : new Object[] {scope.messageGroup(), turn, input};
        var result =
                jdbc.query(
                        sql,
                        (row, index) -> {
                            var value = new LinkedHashMap<String, Object>();
                            value.put("event_id", row.getString(1));
                            value.put("created_at", time(row.getLong(2)));
                            long processed = row.getLong(3);
                            value.put("applied_at", row.wasNull() ? null : time(processed));
                            return value;
                        },
                        args);
        return result.isEmpty() ? Map.of() : result.get(0);
    }

    public static void validateAgent(Map<String, Object> configuration) {
        var snapshot = map(configuration.get("agentSnapshot"));
        Object multiagent = snapshot.get("multiagent");
        if (multiagent != null && !Boolean.FALSE.equals(multiagent) && !Map.of().equals(multiagent))
            throw error(
                    "unsupported_capability",
                    "Multiagent configurations are outside this protocol release");
        Object identity =
                snapshot.getOrDefault("execution_identity", snapshot.get("executionIdentity"));
        if (identity != null && !"service_account".equals(map(identity).get("type")))
            throw error(
                    "unsupported_capability",
                    "This execution identity has no verified protocol mapping");
        if (snapshot.get("skills") instanceof List<?> skills && !skills.isEmpty())
            throw error(
                    "unsupported_capability",
                    "Skills have no verified protocol mapping in this release");
        agentToolsets(snapshot);
        rejectClientActions(snapshot);
    }

    /** Resolve the same enabled and permission defaults as AgentSpecCodec, without private credentials. */
    public static List<Map<String, Object>> agentToolsets(Map<String, Object> snapshot) {
        var result = new ArrayList<Map<String, Object>>();
        var configured = snapshot.get("tools") instanceof List<?> tools ? tools : List.of();
        var declared = new HashSet<String>();
        boolean nativeTools = false;
        for (Object raw : configured) {
            var toolset = map(raw);
            String type = text(toolset.get("type"));
            boolean mcp = "mcp_toolset".equals(type);
            if (!mcp && !"agent_toolset".equals(type))
                throw error("unsupported_capability", "Unknown Agent toolset mapping");
            nativeTools |= !mcp;
            var defaults =
                    toolConfig(
                            map(toolset.get("defaultConfig")),
                            true,
                            mcp ? "always_ask" : "always_allow");
            var configs = new ArrayList<Map<String, Object>>();
            if (toolset.get("configs") instanceof List<?> entries)
                for (Object entry : entries) {
                    var config = map(entry);
                    var projected =
                            new LinkedHashMap<>(
                                    toolConfig(
                                            config,
                                            (boolean) defaults.get("enabled"),
                                            text(
                                                    map(defaults.get("permission_policy"))
                                                            .get("type"))));
                    String name = text(config.get("name"));
                    if (name == null || name.isBlank())
                        throw error("unsupported_capability", "Tool name is missing");
                    if (!mcp
                            && !Set.of(
                                            "bash",
                                            "read",
                                            "write",
                                            "edit",
                                            "glob",
                                            "grep",
                                            "web_fetch",
                                            "web_search")
                                    .contains(name))
                        throw error(
                                "unsupported_capability",
                                "Tool name has no verified standard mapping");
                    projected.put("name", name);
                    if (!mcp) projected.put("type", name);
                    configs.add(projected);
                }
            var projected = new LinkedHashMap<String, Object>();
            projected.put("type", mcp ? "mcp_toolset" : "agent_toolset_20260401");
            projected.put("default_config", defaults);
            projected.put("configs", configs);
            if (mcp) {
                String name = text(toolset.get("mcpServerName"));
                if (name == null || !declared.add(name))
                    throw error("unsupported_capability", "Invalid MCP toolset mapping");
                projected.put("mcp_server_name", name);
            }
            result.add(projected);
        }
        if (!nativeTools)
            result.add(
                    Map.of(
                            "type",
                            "agent_toolset_20260401",
                            "default_config",
                            toolConfig(Map.of(), true, "always_allow"),
                            "configs",
                            List.of()));
        var servers = new HashSet<String>();
        if (snapshot.get("mcpServers") instanceof List<?> configuredServers)
            for (Object raw : configuredServers) {
                var server = map(raw);
                String name = text(server.get("name"));
                if ("stdio".equals(server.get("transport"))
                        || "stdio".equals(server.get("type"))
                        || !(server.get("url") instanceof String url)
                        || url.isBlank()
                        || name == null
                        || !servers.add(name)
                        || !declared.contains(name))
                    throw error(
                            "unsupported_capability",
                            "MCP requires a URL server and an explicit non-interactive tool"
                                    + " policy");
            }
        if (!servers.equals(declared))
            throw error("unsupported_capability", "MCP toolset has no matching server");
        return List.copyOf(result);
    }

    private static Map<String, Object> toolConfig(
            Map<String, Object> config, boolean inheritedEnabled, String inheritedPolicy) {
        boolean enabled = config.get("enabled") instanceof Boolean value ? value : inheritedEnabled;
        String policy = text(map(config.get("permissionPolicy")).get("type"));
        if (policy == null) policy = inheritedPolicy;
        if (!Set.of("always_allow", "always_ask", "auto").contains(policy)
                || enabled && !"always_allow".equals(policy))
            throw error("unsupported_capability", "Client tool confirmation is unavailable");
        return Map.of("enabled", enabled, "permission_policy", Map.of("type", policy));
    }

    private static void rejectClientActions(Object value) {
        if (value instanceof Map<?, ?> object) {
            if (Boolean.TRUE.equals(object.get("requiresExternalResult"))
                    || Boolean.TRUE.equals(object.get("requireConfirmation"))
                    || "client".equals(object.get("execution")))
                throw error(
                        "unsupported_capability",
                        "Client confirmation or external tool results are unavailable");
            for (Object child : object.values()) rejectClientActions(child);
        } else if (value instanceof List<?> list)
            for (Object child : list) rejectClientActions(child);
    }

    /** Called by the journal while its native facts, checkpoint and resources are being committed. */
    public static void project(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ManagedScope scope,
            String source,
            String type,
            Map<String, Object> data,
            long now) {
        ensure(jdbc, scope);
        String turn = text(data.get("turn_id"));
        if ("item.completed".equals(type) || "item.finalized".equals(type)) {
            var item = map(data.get("item"));
            String role = text(item.get("role"));
            String message = text(item.get("message_id"));
            String nativeKey = "message:" + turn + ":" + message;
            if ("user".equalsIgnoreCase(role)) {
                var links =
                        jdbc.query(
                                "SELECT event_id,input_id FROM ma.protocol_link WHERE scope_key=?"
                                        + " AND message_id=?",
                                (row, index) -> row.getString(1),
                                scope.messageGroup(),
                                message);
                if (!links.isEmpty()) {
                    // Steer/inject become processed only with their applied receipt, not a native
                    // message alone.
                    var linked =
                            jdbc.queryForMap(
                                    "SELECT input_id FROM ma.protocol_link WHERE scope_key=? AND"
                                            + " event_id=?",
                                    scope.messageGroup(),
                                    links.get(0));
                    if (linked.get("input_id") == null)
                        processed(jdbc, mapper, scope, links.get(0), now);
                }
                return;
            }
            if (!"assistant".equalsIgnoreCase(role)) return;
            var content = new ArrayList<Map<String, Object>>();
            for (Object raw : list(item.get("content"))) {
                var block = map(raw);
                if ("text".equals(block.get("type"))) content.add(block);
            }
            if (content.isEmpty()) return;
            var prior =
                    jdbc.query(
                            "SELECT event_id FROM ma.protocol_event WHERE scope_key=? AND"
                                    + " native_key=? ORDER BY sequence LIMIT 1",
                            (row, index) -> row.getString(1),
                            scope.messageGroup(),
                            nativeKey);
            String id =
                    prior.isEmpty()
                            ? "evt_" + ManagedScope.fingerprint(scope.messageGroup(), nativeKey)
                            : prior.get(0);
            if (prior.isEmpty()) {
                var event = base(id, "agent.message", now);
                event.put("content", content);
                event.put(EXTENSION, extension(scope, turn, "item_id", data.get("item_id")));
                put(jdbc, mapper, scope, id, event, nativeKey);
            }
            if ("item.finalized".equals(type)) {
                var previous =
                        jdbc.query(
                                "SELECT event_ids,sealed FROM ma.protocol_output WHERE scope_key=?"
                                        + " AND turn_id=?",
                                (row, index) ->
                                        Map.of(
                                                "ids",
                                                row.getString(1),
                                                "sealed",
                                                row.getBoolean(2)),
                                scope.messageGroup(),
                                turn);
                if (previous.isEmpty())
                    jdbc.update(
                            "INSERT INTO ma.protocol_output(scope_key,turn_id,event_ids)"
                                    + " VALUES(?,?,?)",
                            scope.messageGroup(),
                            turn,
                            json(mapper, List.of(id)));
                else {
                    var ids =
                            new ArrayList<>(
                                    list(parse(mapper, (String) previous.get(0).get("ids"))));
                    if (!ids.contains(id)) {
                        if (Boolean.TRUE.equals(previous.get(0).get("sealed")))
                            throw error("output_conflict", "Final output is already committed");
                        ids.add(id);
                        jdbc.update(
                                "UPDATE ma.protocol_output SET event_ids=? WHERE scope_key=? AND"
                                        + " turn_id=?",
                                json(mapper, ids),
                                scope.messageGroup(),
                                turn);
                    }
                }
                emit(
                        jdbc,
                        mapper,
                        scope,
                        source + ":finalized",
                        "item.finalized",
                        extension(scope, turn, "output_event_ids", List.of(id)));
            }
        } else if (Set.of("tool.requested", "tool.dispatched", "tool.completed").contains(type)) {
            String call = text(data.get("tool_call_id"));
            if (call == null || turn == null || call.startsWith("framework_")) return;
            String nativeKey = "tool_use:" + turn + ":" + call;
            String useId = "evt_" + ManagedScope.fingerprint(scope.messageGroup(), nativeKey);
            var use = latest(jdbc, mapper, scope, useId);
            if (!"tool.completed".equals(type)) {
                if (use != null) return;
                String name = text(data.get("name"));
                if (name == null) return;
                Map<String, Object> descriptor = Map.of();
                var catalogs =
                        jdbc.queryForList(
                                "SELECT c.payload FROM ma.mcp_catalog c JOIN"
                                    + " ma.protocol_mcp_catalog t ON c.scope_key=t.scope_key AND"
                                    + " c.catalog_id=t.catalog_id WHERE c.scope_key=? AND"
                                    + " t.turn_id=?",
                                String.class,
                                scope.messageGroup(),
                                turn);
                for (String raw : catalogs)
                    for (Object tool : list(read(mapper, raw).get("tools")))
                        if (name.equals(map(tool).get("name"))) descriptor = map(tool);
                boolean mcp = !descriptor.isEmpty();
                var event = base(useId, mcp ? "agent.mcp_tool_use" : "agent.tool_use", now);
                event.put("name", mcp ? descriptor.get("remoteName") : name);
                event.put(
                        "input",
                        data.get("input") instanceof Map<?, ?> ? data.get("input") : Map.of());
                if (mcp) event.put("mcp_server_name", descriptor.get("server"));
                event.put(EXTENSION, extension(scope, turn, "tool_call_id", call));
                put(jdbc, mapper, scope, useId, event, nativeKey);
            } else if (use != null) {
                String resultId =
                        "evt_"
                                + ManagedScope.fingerprint(
                                        scope.messageGroup(), "tool_result", turn, call);
                if (latest(jdbc, mapper, scope, resultId) != null) return;
                boolean mcp = "agent.mcp_tool_use".equals(use.get("type"));
                var event =
                        base(resultId, mcp ? "agent.mcp_tool_result" : "agent.tool_result", now);
                event.put(mcp ? "mcp_tool_use_id" : "tool_use_id", useId);
                var result = map(data.get("result"));
                var content = new ArrayList<Map<String, Object>>();
                for (Object raw : list(result.getOrDefault("output", result.get("content"))))
                    if ("text".equals(map(raw).get("type"))) content.add(map(raw));
                event.put("content", content);
                event.put(
                        "is_error",
                        Set.of("failed", "error", "cancelled", "denied", "interrupted")
                                .contains(String.valueOf(data.get("status"))));
                event.put(EXTENSION, extension(scope, turn, "tool_call_id", call));
                put(jdbc, mapper, scope, resultId, event, "tool_result:" + turn + ":" + call);
            }
        } else if ("model.started".equals(type)) {
            String call = text(data.get("model_call_id"));
            if (call == null) return;
            String id =
                    "evt_"
                            + ManagedScope.fingerprint(
                                    scope.messageGroup(), "model_start", turn, call);
            if (latest(jdbc, mapper, scope, id) == null)
                put(jdbc, mapper, scope, id, base(id, "span.model_request_start", now), null);
        } else if (Set.of("usage.recorded", "model.completed").contains(type)) {
            String call = text(data.get("model_call_id"));
            if (call == null || !(data.get("usage") instanceof Map<?, ?>)) return;
            String start =
                    "evt_"
                            + ManagedScope.fingerprint(
                                    scope.messageGroup(), "model_start", turn, call);
            String id =
                    "evt_"
                            + ManagedScope.fingerprint(
                                    scope.messageGroup(), "model_end", turn, call);
            if (latest(jdbc, mapper, scope, start) == null
                    || latest(jdbc, mapper, scope, id) != null) return;
            var event = base(id, "span.model_request_end", now);
            event.put("model_request_start_id", start);
            var usage = map(data.get("usage"));
            var counts = new LinkedHashMap<String, Object>();
            if (usage.get("inputTokens") instanceof Number value) counts.put("input_tokens", value);
            if (usage.get("outputTokens") instanceof Number value)
                counts.put("output_tokens", value);
            if (usage.get("cachedTokens") instanceof Number value)
                counts.put("cache_read_input_tokens", value);
            if (usage.get("cacheCreationTokens") instanceof Number value)
                counts.put("cache_creation_input_tokens", value);
            if (counts.size() != 4) return;
            event.put("model_usage", counts);
            put(jdbc, mapper, scope, id, event, null);
        } else if (type.startsWith("input.")) {
            var inputs = new ArrayList<Object>();
            if (data.get("input_id") != null) inputs.add(data.get("input_id"));
            inputs.addAll(list(data.get("input_ids")));
            for (Object input : inputs) {
                var links =
                        jdbc.query(
                                "SELECT event_id,turn_id,intent FROM ma.protocol_link WHERE"
                                        + " scope_key=? AND input_id=?",
                                (row, index) ->
                                        Map.of(
                                                "event",
                                                row.getString(1),
                                                "turn",
                                                row.getString(2),
                                                "intent",
                                                row.getString(3)),
                                scope.messageGroup(),
                                input);
                if (links.isEmpty()) continue;
                var link = links.get(0);
                if ("input.applied".equals(type))
                    processed(jdbc, mapper, scope, link.get("event"), now);
                var ext = extension(scope, link.get("turn"), "input_id", input);
                ext.put("event_id", link.get("event"));
                ext.put("input_type", link.get("intent"));
                ext.put("status", type.substring(6));
                if (data.get("reason") != null) ext.put("rejection_reason", data.get("reason"));
                emit(jdbc, mapper, scope, source + ":" + input, type, ext);
            }
        } else if (type.startsWith("turn.")) {
            var ext =
                    extension(
                            scope, turn, "status", data.getOrDefault("status", type.substring(5)));
            if (data.get("error") != null) ext.put("error", data.get("error"));
            if (Set.of("turn.completed", "turn.failed", "turn.cancelled").contains(type)) {
                var ids =
                        jdbc.query(
                                "SELECT event_ids FROM ma.protocol_output WHERE scope_key=? AND"
                                        + " turn_id=? AND sealed=TRUE",
                                (row, index) -> list(parse(mapper, row.getString(1))),
                                scope.messageGroup(),
                                turn);
                ext.put("output_event_ids", ids.isEmpty() ? List.of() : ids.get(0));
                ext.put(
                        "output_url",
                        "/v1/sessions/" + scope.sessionId() + "/turns/" + turn + "/output");
            }
            emit(jdbc, mapper, scope, source + ":turn", type, ext);
            if (Set.of("turn.running", "turn.waiting_tool", "turn.preparing").contains(type))
                status(jdbc, mapper, scope, source, "running", null, now);
            if (Set.of("turn.completed", "turn.failed", "turn.cancelled").contains(type)) {
                if ("turn.failed".equals(type)) {
                    var event =
                            base(
                                    "evt_" + ManagedScope.fingerprint(source, "error"),
                                    "session.error",
                                    now);
                    event.put(
                            "error",
                            Map.of("type", "api_error", "message", "Turn execution failed"));
                    put(jdbc, mapper, scope, String.valueOf(event.get("id")), event, null);
                }
                status(jdbc, mapper, scope, source, "idle", Map.of("type", "end_turn"), now);
            }
        }
    }

    public static void sealOutput(JdbcTemplate jdbc, ManagedScope scope, String turn) {
        jdbc.update(
                "UPDATE ma.protocol_output SET sealed=TRUE WHERE scope_key=? AND turn_id=?",
                scope.messageGroup(),
                turn);
    }

    private static void processed(
            JdbcTemplate jdbc, ObjectMapper mapper, ManagedScope scope, String id, long now) {
        var event = latest(jdbc, mapper, scope, id);
        if (event == null || event.get("processed_at") != null) return;
        event.put("processed_at", time(now));
        put(jdbc, mapper, scope, id, event, null);
        jdbc.update(
                "UPDATE ma.protocol_link SET processed_at=? WHERE scope_key=? AND event_id=?",
                now,
                scope.messageGroup(),
                id);
    }

    private static void status(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ManagedScope scope,
            String source,
            String status,
            Map<String, Object> reason,
            long now) {
        String previous =
                jdbc.queryForObject(
                        "SELECT status FROM ma.protocol_session WHERE scope_key=?",
                        String.class,
                        scope.messageGroup());
        if (status.equals(previous)) return;
        var event =
                base(
                        "evt_" + ManagedScope.fingerprint(source, "session", status),
                        "session.status_" + status,
                        now);
        if (reason != null) event.put("stop_reason", reason);
        put(jdbc, mapper, scope, String.valueOf(event.get("id")), event, null);
        jdbc.update(
                "UPDATE ma.protocol_session SET status=? WHERE scope_key=?",
                status,
                scope.messageGroup());
    }

    private static void emit(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ManagedScope scope,
            String source,
            String type,
            Map<String, Object> extension) {
        // Native Inbox records and durable Input receipts describe the same transition.
        // Deduplicate by Input and state rather than their different source event IDs.
        String id =
                "evt_"
                        + (type.startsWith("input.") && extension.get("input_id") != null
                                ? ManagedScope.fingerprint(
                                        scope.messageGroup(),
                                        String.valueOf(extension.get("turn_id")),
                                        String.valueOf(extension.get("input_id")),
                                        type)
                                : ManagedScope.fingerprint(source, type));
        if (latest(jdbc, mapper, scope, id) != null) return;
        var event = base(id, EXTENSION + "." + type, System.currentTimeMillis());
        event.put(EXTENSION, extension);
        String nativeKey =
                type.startsWith("turn.")
                        ? "turn_status:" + extension.get("turn_id")
                        : Set.of("input.applied", "input.rejected").contains(type)
                                ? "input_receipt:" + extension.get("input_id")
                                : null;
        put(jdbc, mapper, scope, id, event, nativeKey);
    }

    public Map<String, Object> turnInfo(ManagedScope scope, String turn) {
        var events =
                jdbc.query(
                        "SELECT payload FROM ma.protocol_event WHERE scope_key=? AND native_key=?"
                                + " ORDER BY sequence",
                        (row, index) -> read(mapper, row.getString(1)),
                        scope.messageGroup(),
                        "turn_status:" + turn);
        var result = new LinkedHashMap<String, Object>();
        result.put("updated_at", null);
        result.put("started_at", null);
        result.put("completed_at", null);
        result.put("error", null);
        for (var event : events) {
            String type = String.valueOf(event.get("type"));
            Object at = event.get("processed_at");
            result.put("updated_at", at);
            if (result.get("started_at") == null
                    && Set.of(
                                    "agentscope_service.turn.preparing",
                                    "agentscope_service.turn.running")
                            .contains(type)) result.put("started_at", at);
            if (Set.of(
                            "agentscope_service.turn.completed",
                            "agentscope_service.turn.failed",
                            "agentscope_service.turn.cancelled")
                    .contains(type)) result.put("completed_at", at);
            if (map(event.get(EXTENSION)).get("error") != null)
                result.put("error", map(event.get(EXTENSION)).get("error"));
        }
        return result;
    }

    private static void ensure(JdbcTemplate jdbc, ManagedScope scope) {
        jdbc.update(
                "INSERT INTO ma.protocol_session(scope_key) VALUES(?) ON CONFLICT DO NOTHING",
                scope.messageGroup());
    }

    private static void put(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ManagedScope scope,
            String id,
            Map<String, Object> event,
            String nativeKey) {
        long next =
                jdbc.queryForObject(
                                "SELECT sequence FROM ma.protocol_session WHERE scope_key=? FOR"
                                        + " UPDATE",
                                Long.class,
                                scope.messageGroup())
                        + 1;
        Object processed = event.get("processed_at");
        jdbc.update(
                "INSERT INTO"
                    + " ma.protocol_event(scope_key,event_id,sequence,event_type,processed_at,payload,native_key)"
                    + " VALUES(?,?,?,?,?,?,?)",
                scope.messageGroup(),
                id,
                next,
                event.get("type"),
                processed == null ? null : Instant.parse(String.valueOf(processed)).toEpochMilli(),
                json(mapper, event),
                nativeKey);
        jdbc.update(
                "UPDATE ma.protocol_session SET sequence=? WHERE scope_key=?",
                next,
                scope.messageGroup());
    }

    private static Map<String, Object> latest(
            JdbcTemplate jdbc, ObjectMapper mapper, ManagedScope scope, String id) {
        var rows =
                jdbc.query(
                        "SELECT payload FROM ma.protocol_event WHERE scope_key=? AND event_id=?"
                                + " ORDER BY sequence DESC LIMIT 1",
                        (row, index) -> read(mapper, row.getString(1)),
                        scope.messageGroup(),
                        id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static Map<String, Object> represent(Map<String, Object> event, boolean extended) {
        var result = new LinkedHashMap<>(event);
        if (!extended) result.remove(EXTENSION);
        return result;
    }

    private static LinkedHashMap<String, Object> base(String id, String type, long now) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", id);
        value.put("type", type);
        value.put("processed_at", time(now));
        return value;
    }

    private static LinkedHashMap<String, Object> extension(
            ManagedScope scope, String turn, String key, Object value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", 1);
        result.put("session_id", scope.sessionId());
        if (turn != null) result.put("turn_id", turn);
        if (value != null) result.put(key, value);
        return result;
    }

    private String encode(Map<String, Object> value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json(mapper, value).getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> cursor(String value, String binding) {
        try {
            if (value.length() > 4096) throw new IllegalArgumentException();
            var result =
                    read(
                            mapper,
                            new String(
                                    Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
            if (!binding.equals(result.get("binding"))
                    || !(result.get("offset") instanceof Integer
                            || result.get("offset") instanceof Long))
                throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException invalid) {
            throw error("invalid_cursor", "Invalid cursor scope or query");
        }
    }

    private static int limit(String value) {
        try {
            int number = Integer.parseInt(value);
            if (number < 1 || number > 256) throw new IllegalArgumentException();
            return number;
        } catch (RuntimeException invalid) {
            throw error("invalid_cursor", "limit must be 1..256");
        }
    }

    private static String first(Map<String, List<String>> query, String key, String fallback) {
        var values = query.get(key);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }

    private static String time(long value) {
        return Instant.ofEpochMilli(value).toString();
    }

    private static String text(Object value) {
        return value instanceof String text ? text : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static List<Object> list(Object value) {
        return value instanceof List<?> values ? new ArrayList<>(values) : List.of();
    }

    private static Map<String, Object> read(ObjectMapper mapper, String value) {
        return map(parse(mapper, value));
    }

    private static Object parse(ObjectMapper mapper, String value) {
        try {
            return mapper.readValue(value, Object.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid protocol ledger JSON", invalid);
        }
    }

    private static String json(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid protocol JSON", invalid);
        }
    }

    private static ManagedStateException error(String code, String message) {
        return new ManagedStateException(code, message);
    }
}
