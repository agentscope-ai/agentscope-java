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
package io.agentcore.managed.server.agentservice.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.runtime.managed.ManagedInputDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedLifecycles;
import io.agentcore.managed.server.runtime.managed.ManagedPublicResources;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedSessionProtocol;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import io.agentcore.managed.server.runtime.managed.ManagedTransportException;
import io.agentcore.managed.server.runtime.protocol.RuntimePayloadDigest;
import io.lettuce.core.RedisException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Managed Session resources. Runtime state and public events use the shared ma ledger. */
@Service
public class SessionApi {
    @Value("${managed-agents.runtime.session-queue.enabled:false}")
    private boolean sessionQueueEnabled;

    @Value("${managed-agents.brain.http-enabled:false}")
    private boolean httpBrainEnabled;

    @Value("${managed-agents.runtime.harness-type:agentscope-java}")
    private String harnessType = "agentscope-java";

    private final AgentServiceSessionCreation creation;
    private final AgentServiceSessionRepository sessions;
    private final AgentServiceSessionAccess access;
    private final AgentServiceRegistryReads registry;
    private final ManagedLifecycles lifecycles;
    private final ManagedInputDelivery inputs;
    private final ManagedSessionTurnsApi execution;
    private final ManagedJournalStore journal;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public SessionApi(
            AgentServiceSessionCreation creation,
            AgentServiceSessionRepository sessions,
            AgentServiceSessionAccess access,
            AgentServiceRegistryReads registry,
            ManagedLifecycles lifecycles,
            ManagedInputDelivery inputs,
            ManagedSessionTurnsApi execution,
            ManagedJournalStore journal,
            ObjectMapper mapper,
            TransactionTemplate tx) {
        this.creation = creation;
        this.sessions = sessions;
        this.access = access;
        this.registry = registry;
        this.lifecycles = lifecycles;
        this.inputs = inputs;
        this.execution = execution;
        this.journal = journal;
        this.mapper = mapper;
        this.tx = tx;
    }

    public Map<String, Object> create(Map<String, Object> body, HttpHeaders headers) {
        fields(body, Set.of("agent", "environment_id", "metadata", "title"));
        if (!headers.containsHeader("Idempotency-Key")) {
            var copy = new HttpHeaders();
            copy.putAll(headers);
            copy.set("Idempotency-Key", UUID.randomUUID().toString());
            headers = copy;
        }
        return createPrepared(body, headers);
    }

    private Map<String, Object> createPrepared(Map<String, Object> body, HttpHeaders headers) {
        key(headers);
        title(body.get("title"));
        metadata(body.getOrDefault("metadata", Map.of()));
        var request = new LinkedHashMap<String, Object>();
        Object agent = body.get("agent");
        if (agent instanceof String id) text(id, 256, "agent");
        else if (agent instanceof Map<?, ?> ref) {
            fields(map(ref), Set.of("type", "id", "version"));
            if (ref.get("type") != null && !"agent".equals(ref.get("type")))
                throw invalid("Agent reference type must be agent");
            text(ref.get("id"), 256, "agent.id");
            if (ref.get("version") != null
                    && (!(ref.get("version") instanceof Number version)
                            || version.longValue() < 1
                            || version.doubleValue() != version.longValue()))
                throw invalid("Agent version must be a positive integer");
        } else throw invalid("agent must be an ID or reference");
        request.put("agent", agent);
        if (body.containsKey("environment_id"))
            request.put("environmentId", text(body.get("environment_id"), 256, "environment_id"));
        for (var mapping :
                Map.of("memory_store_ids", "memoryStoreIds", "vault_ids", "vaultIds").entrySet()) {
            if (body.containsKey(mapping.getKey())) {
                Object ids = body.get(mapping.getKey());
                if (!(ids instanceof List<?> list) || list.size() > 64)
                    throw invalid(mapping.getKey() + " must be an array of at most 64 IDs");
                for (Object id : list) text(id, 256, mapping.getKey());
                request.put(mapping.getValue(), ids);
            }
        }
        if (body.containsKey("resources")) {
            if (!(body.get("resources") instanceof List<?> resources)
                    || resources.size() > 64
                    || resources.stream().anyMatch(value -> !(value instanceof Map<?, ?>)))
                throw invalid("resources must contain at most 64 objects");
            request.put("resources", body.get("resources"));
        }
        if (body.containsKey("agent_overrides")) {
            if (!(body.get("agent_overrides") instanceof Map<?, ?>))
                throw invalid("agent_overrides must be an object");
            request.put("agentOverrides", body.get("agent_overrides"));
        }
        boolean queueMode = ManagedSessionTurnsApi.queueExtension(headers);
        boolean durableQueue =
                queueMode
                        || httpBrainEnabled
                        || Set.of("pi", "pi-durable", "codex").contains(harnessType);
        String extensions = headers.getFirst("Managed-Agents-Extensions");

        if (queueMode && !sessionQueueEnabled)
            throw invalid("unsupported_capability: session_queue_v1 is disabled");
        String digest =
                RuntimePayloadDigest.of(
                        mapper,
                        Map.of(
                                "body",
                                body,
                                "scheduler_mode",
                                durableQueue ? "db_queue_v1" : "broker_fifo_v1",
                                "commit_mode",
                                "direct"));
        String requestKey =
                ManagedScope.fingerprint(
                        "create",
                        json(access.principal(headers)),
                        String.valueOf(headers.getFirst("X-Managed-Agents-Tenant")),
                        String.valueOf(headers.getFirst("X-Managed-Agents-Namespace")),
                        key(headers));
        String previousId = execution.protocol().creationSession(requestKey);
        if (previousId != null) {
            access.authorize(previousId, headers, "invoke");
            require(previousId);
            return ManagedSessionProtocol.represent(
                    execution.protocol().creation(requestKey, digest),
                    ManagedSessionTurnsApi.extensions(headers));
        }
        var selected = creation.create(request, headers);
        String id = String.valueOf(selected.get("id"));
        var result =
                journal.prepareResourceEvent(
                        scope(require(id)),
                        "session.created",
                        Map.of("session_id", id),
                        () ->
                                tx.execute(
                                        ignored -> {
                                            var row =
                                                    sessions.lockById(id)
                                                            .orElseThrow(
                                                                    () ->
                                                                            new ResponseStatusException(
                                                                                    HttpStatus
                                                                                            .NOT_FOUND));
                                            managed(row);
                                            access.authorize(row, headers, "invoke");
                                            execution.validateAgent(execution.agentSnapshot(row));
                                            if (row.creationRequestDigest != null) {
                                                if (!digest.equals(row.creationRequestDigest))
                                                    throw new ResponseStatusException(
                                                            HttpStatus.CONFLICT,
                                                            "Session creation idempotency content"
                                                                    + " differs");
                                                var frozen =
                                                        execution
                                                                .protocol()
                                                                .creation(requestKey, digest);
                                                if (frozen != null)
                                                    return ManagedSessionProtocol.represent(
                                                            frozen,
                                                            ManagedSessionTurnsApi.extensions(
                                                                    headers));
                                            }
                                            if (durableQueue) execution.queue().enable(scope(row));
                                            row.creationRequestDigest = digest;
                                            row.title = (String) body.get("title");
                                            row.metadataJson =
                                                    json(body.getOrDefault("metadata", Map.of()));
                                            sessions.saveAndFlush(row);
                                            journal.resourceEvent(
                                                    scope(row),
                                                    resourceId(row, "created"),
                                                    "session.created",
                                                    Map.of("session_id", id));
                                            var representation = new HttpHeaders();
                                            representation.putAll(headers);
                                            representation.set(
                                                    "Managed-Agents-Extensions", "turn-inputs-v1");
                                            var frozen =
                                                    execution
                                                            .protocol()
                                                            .freezeCreation(
                                                                    scope(row),
                                                                    requestKey,
                                                                    digest,
                                                                    view(row, representation));
                                            return ManagedSessionProtocol.represent(
                                                    frozen,
                                                    ManagedSessionTurnsApi.extensions(headers));
                                        }));

        return result;
    }

    public Map<String, Object> get(String id, HttpHeaders headers) {
        access.authorize(id, headers, "read");
        return view(require(id), headers);
    }

    public Map<String, Object> list(Map<String, List<String>> query, HttpHeaders headers) {
        if (!Set.of("after", "limit", "order", "lifecycle", "agent_id").containsAll(query.keySet()))
            throw invalid("Unknown Session list filter");
        int limit;
        try {
            limit = Integer.parseInt(first(query, "limit", "100"));
        } catch (NumberFormatException error) {
            throw invalid("Invalid Session limit");
        }
        if (limit < 1 || limit > 256) throw invalid("limit must be 1..256");
        String order = first(query, "order", "desc");
        String lifecycle = first(query, "lifecycle", "active");
        String agent = first(query, "agent_id", "");
        if (!Set.of("asc", "desc").contains(order)
                || !Set.of("active", "archived", "all").contains(lifecycle))
            throw invalid("Invalid Session list order or lifecycle");
        String signature =
                RuntimePayloadDigest.of(
                        mapper, Map.of("order", order, "lifecycle", lifecycle, "agent", agent));
        String after = first(query, "after", null);
        if (after != null && !after.isBlank()) {
            try {
                if (after.length() > 8192) throw new IllegalArgumentException();
                var cursor =
                        parse(
                                new String(
                                        Base64.getUrlDecoder().decode(after),
                                        StandardCharsets.UTF_8));
                if (!signature.equals(cursor.get("query"))
                        || !(cursor.get("after") instanceof String))
                    throw new IllegalArgumentException();
                after = (String) cursor.get("after");
            } catch (IllegalArgumentException | IllegalStateException malformed) {
                throw invalid("Invalid Session cursor");
            }
        }
        var data = new ArrayList<Map<String, Object>>();
        for (int scanned = 0; scanned < 16 && data.size() < limit; scanned++) {
            var nativePage =
                    registry.sessions(
                            after,
                            Math.min(200, limit - data.size()),
                            lifecycle,
                            null,
                            headers,
                            order);
            for (Object raw : (List<?>) nativePage.get("data")) {
                var item = map(raw);
                if (!"managed".equals(item.get("runtimeKind"))) continue;
                String id = String.valueOf(item.get("id"));
                var value = get(id, headers);
                if (agent.isBlank() || agent.equals(map(value.get("agent")).get("id")))
                    data.add(value);
            }
            after = (String) nativePage.get("next_cursor");
            if (after == null) break;
        }
        var page = new LinkedHashMap<String, Object>();
        page.put("data", data);
        page.put("has_more", after != null);
        page.put(
                "next_cursor",
                after == null
                        ? null
                        : Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        json(Map.of("query", signature, "after", after))
                                                .getBytes(StandardCharsets.UTF_8)));
        return page;
    }

    public Map<String, Object> update(String id, Map<String, Object> body, HttpHeaders headers) {
        for (int attempt = 0; ; attempt++) {
            try {
                return updatePrepared(id, body, headers);
            } catch (ManagedStateException changed) {
                if (!"projection_preparation_changed".equals(changed.code())) throw changed;
                if (attempt >= 2)
                    throw new ManagedTransportException(
                            "Concurrent Session update; retry request", changed);
            }
        }
    }

    private Map<String, Object> updatePrepared(
            String id, Map<String, Object> body, HttpHeaders headers) {
        fields(body, Set.of("title", "metadata"));
        if (body.containsKey("title")) title(body.get("title"));
        if (body.containsKey("metadata")) metadata(body.get("metadata"));
        access.authorize(id, headers, "interact");
        var observed = require(id);
        var preparedFact = new LinkedHashMap<String, Object>();
        preparedFact.put("session_id", id);
        preparedFact.put("title", body.containsKey("title") ? body.get("title") : observed.title);
        preparedFact.put(
                "metadata",
                body.containsKey("metadata") ? body.get("metadata") : parse(observed.metadataJson));
        var result =
                journal.prepareResourceEvent(
                        scope(observed),
                        "session.updated",
                        preparedFact,
                        () ->
                                tx.execute(
                                        ignored -> {
                                            var row =
                                                    sessions.lockById(id)
                                                            .orElseThrow(
                                                                    () ->
                                                                            new ResponseStatusException(
                                                                                    HttpStatus
                                                                                            .NOT_FOUND));
                                            managed(row);
                                            access.authorize(row, headers, "interact");
                                            if (!"active".equals(row.lifecycle))
                                                throw new ResponseStatusException(
                                                        HttpStatus.CONFLICT,
                                                        "Session is not active");
                                            boolean changed = false;
                                            if (body.containsKey("title")
                                                    && !Objects.equals(
                                                            row.title, body.get("title"))) {
                                                row.title = (String) body.get("title");
                                                changed = true;
                                            }
                                            if (body.containsKey("metadata")) {
                                                String encoded = json(body.get("metadata"));
                                                if (!parse(row.metadataJson)
                                                        .equals(body.get("metadata"))) {
                                                    row.metadataJson = encoded;
                                                    changed = true;
                                                }
                                            }
                                            if (changed) {
                                                row.updatedAt = System.currentTimeMillis();
                                                sessions.saveAndFlush(row);
                                                var fact = new LinkedHashMap<String, Object>();
                                                fact.put("session_id", id);
                                                fact.put("title", row.title);
                                                fact.put("metadata", parse(row.metadataJson));
                                                journal.resourceEvent(
                                                        scope(row),
                                                        resourceId(row, "updated:" + row.version),
                                                        "session.updated",
                                                        fact);
                                            }
                                            return view(row, headers);
                                        }));

        return result;
    }

    public Map<String, Object> lifecycle(String id, String desired, HttpHeaders headers) {
        var actor =
                access.authorizeLifecycle(
                        id, headers, "active".equals(desired) ? "invoke" : "interact");
        String requestKey = key(headers);
        var observed = require(id);
        String preparedIdentity;
        try {
            preparedIdentity =
                    mapper.copy()
                            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                            .writeValueAsString(actor);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot encode lifecycle caller", invalid);
        }
        String commandId =
                "lifecycle_"
                        + ManagedScope.fingerprint(
                                scope(observed).messageGroup(), preparedIdentity, requestKey);
        var preparedFact =
                Map.<String, Object>of(
                        "session_id", id, "lifecycle", desired, "command_id", commandId);
        var result =
                journal.prepareResourceEvent(
                        scope(observed),
                        "active".equals(desired) ? "session.restored" : "session." + desired,
                        preparedFact,
                        () ->
                                tx.execute(
                                        ignored -> {
                                            var row =
                                                    sessions.lockById(id)
                                                            .orElseThrow(
                                                                    () ->
                                                                            new ResponseStatusException(
                                                                                    HttpStatus
                                                                                            .NOT_FOUND));
                                            managed(row);
                                            access.authorizeLifecycleActor(
                                                    row,
                                                    actor,
                                                    "active".equals(desired)
                                                            ? "invoke"
                                                            : "interact");
                                            var scope = scope(row);
                                            lifecycles.lockForTransition(scope);
                                            if (!"active".equals(desired)
                                                    && !lifecycles.quiescent(scope))
                                                access.authorizeLifecycleActor(
                                                        row, actor, "cancel");
                                            String identity;
                                            try {
                                                identity =
                                                        mapper.copy()
                                                                .enable(
                                                                        SerializationFeature
                                                                                .ORDER_MAP_ENTRIES_BY_KEYS)
                                                                .writeValueAsString(actor);
                                            } catch (JsonProcessingException invalid) {
                                                throw new IllegalStateException(
                                                        "Cannot encode lifecycle caller", invalid);
                                            }
                                            var command =
                                                    lifecycles.stage(
                                                            scope, desired, identity, requestKey);
                                            if (command.revision() > row.lifecycleRevision) {
                                                row.lifecycle = command.desired();
                                                row.lifecycleRevision = command.revision();
                                                row.updatedAt = System.currentTimeMillis();
                                                sessions.saveAndFlush(row);
                                                journal.resourceEvent(
                                                        scope,
                                                        resourceId(
                                                                row, "lifecycle:" + command.id()),
                                                        "active".equals(command.desired())
                                                                ? "session.restored"
                                                                : "session." + command.desired(),
                                                        Map.of(
                                                                "session_id",
                                                                id,
                                                                "lifecycle",
                                                                command.desired(),
                                                                "command_id",
                                                                command.id()));
                                            }
                                            if ("deleted".equals(command.desired()))
                                                return Map.<String, Object>of(
                                                        "id",
                                                        id,
                                                        "type",
                                                        "session_deleted",
                                                        "lifecycle_command_id",
                                                        command.id(),
                                                        "cleanup_status",
                                                        command.status());
                                            return view(row, headers);
                                        }));
        var row = sessions.findById(id).orElseThrow();
        for (String turn : lifecycles.unsettledTurns(scope(row), 256)) {
            try {
                inputs.refill(scope(row), turn, 0, 256);
            } catch (ManagedTransportException | RedisException unavailable) {
                /* Runtime rebuilds the durable lifecycle flag. */
            }
        }

        return result;
    }

    public Map<String, Object> lifecycleReceipt(String id, String command, HttpHeaders headers) {
        access.authorizeLifecycle(id, headers, "read");
        var row =
                sessions.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var current = lifecycles.get(scope(row), command);
        var view = new LinkedHashMap<String, Object>();
        view.put("id", current.id());
        view.put("session_id", id);
        view.put("lifecycle", current.desired());
        view.put("status", current.status());
        view.put("revision", current.revision());
        if (current.failureCode() != null) view.put("failure_code", current.failureCode());
        return view;
    }

    private Map<String, Object> view(AgentServiceSessionEntity row, HttpHeaders headers) {
        managed(row);
        var pinned = parse(row.publicView);
        var runtime = execution.runtime(row);
        var reference = map(pinned.get("sessionAgentReference"));
        var agent = new LinkedHashMap<String, Object>();
        agent.put("type", "agent");
        agent.put("id", reference.getOrDefault("id", pinned.getOrDefault("agentId", "")));
        agent.put(
                "version",
                reference.getOrDefault("version", pinned.getOrDefault("agentVersion", 1)));
        agent.put("name", reference.getOrDefault("name", ""));
        var snapshot = execution.agentSnapshot(row);
        Object model = snapshot.getOrDefault("model", reference.getOrDefault("model", ""));
        agent.put(
                "model",
                Map.of(
                        "id",
                        model instanceof Map<?, ?> value ? value.getOrDefault("id", null) : model));
        agent.put("description", snapshot.get("description"));
        agent.put("system", snapshot.get("system"));
        // Executions run under this platform's service account; public caller authorization is
        // separate.
        agent.put("execution_identity", Map.of("type", "service_account"));
        var servers = new ArrayList<Map<String, Object>>();
        if (snapshot.get("mcpServers") instanceof List<?> configured)
            for (Object raw : configured) {
                var server = map(raw);
                if (server.get("name") instanceof String name
                        && server.get("url") instanceof String url) {
                    servers.add(Map.of("type", "url", "name", name, "url", url));
                }
            }
        agent.put("mcp_servers", servers);
        agent.put(
                "tools",
                AgentServiceSessionAccess.runtimeHeaders(headers)
                        ? servers.stream()
                                .map(
                                        server ->
                                                Map.of(
                                                        "type",
                                                        "mcp_toolset",
                                                        "mcp_server_name",
                                                        server.get("name")))
                                .toList()
                        : execution.agentToolsets(snapshot));
        agent.put("skills", List.of());
        agent.put("multiagent", null);
        var result = new LinkedHashMap<String, Object>();
        result.put("type", "session");
        result.put("id", row.id);
        result.put("runtime_kind", "managed");
        result.put("owner_id", row.ownerId);
        result.put("agent", agent);
        result.put("environment_id", pinned.getOrDefault("environmentId", ""));
        result.put("agent_owner_id", pinned.getOrDefault("agentOwnerId", row.ownerId));
        result.put("external_key", pinned.get("externalKey"));
        result.put("memory_store_ids", pinned.getOrDefault("memoryStoreIds", List.of()));
        result.put("vault_ids", pinned.getOrDefault("vaultIds", List.of()));
        result.put("status", "active".equals(row.lifecycle) ? runtime.get("status") : "terminated");
        result.put("lifecycle", row.lifecycle);
        result.put("state", row.state);
        result.put("configuration_revision", row.revision);
        result.put("lifecycle_revision", row.lifecycleRevision);
        var command = lifecycles.current(scope(row));
        result.put("lifecycle_command_id", command == null ? null : command.id());
        result.put("cleanup_status", command == null ? "completed" : command.status());
        result.put("created_at", Instant.ofEpochMilli(row.createdAt).toString());
        result.put("updated_at", Instant.ofEpochMilli(row.updatedAt).toString());
        result.put(
                "archived_at",
                "archived".equals(row.lifecycle)
                        ? Instant.ofEpochMilli(row.updatedAt).toString()
                        : null);
        result.put("title", row.title);
        result.put("metadata", parse(row.metadataJson));
        var measured = map(map(runtime.get("usage")).get("totals"));
        var usage = new LinkedHashMap<String, Object>();
        if (measured.get("inputTokens") instanceof Number value) usage.put("input_tokens", value);
        if (measured.get("outputTokens") instanceof Number value) usage.put("output_tokens", value);
        result.put("usage", usage);
        result.put(
                "public_cursor",
                ManagedPublicResources.eventCursor(
                        scope(row), ((Number) runtime.get("public_sequence")).longValue()));
        result.put("budget", null);
        result.put("resources", List.of());
        result.put("outcome_evaluations", List.of());
        result.put("stats", Map.of());
        if (AgentServiceSessionAccess.runtimeHeaders(headers)) return result;
        var ext = new LinkedHashMap<String, Object>();
        for (String key :
                List.of(
                        "runtime_kind",
                        "owner_id",
                        "agent_owner_id",
                        "external_key",
                        "memory_store_ids",
                        "lifecycle",
                        "state",
                        "configuration_revision",
                        "lifecycle_revision",
                        "lifecycle_command_id",
                        "cleanup_status",
                        "public_cursor")) ext.put(key, result.remove(key));
        ext.put("execution_authority", "platform_service_account");
        if (execution.queue().enabled(scope(row))) {
            ext.put("scheduler_mode", "db_queue_v1");
            ext.put("queue_revision", execution.queue().revision(scope(row)));
        }
        ext.put("commit_mode", "direct");
        if (ManagedSessionTurnsApi.extensions(headers)) result.put("managed_agents", ext);
        return result;
    }

    private AgentServiceSessionEntity require(String id) {
        var row =
                sessions.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ("deleted".equals(row.lifecycle))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return row;
    }

    private static void managed(AgentServiceSessionEntity row) {
        if (!"managed".equals(row.runtimeKind))
            throw invalid("This API requires a Managed Session");
    }

    private static ManagedScope scope(AgentServiceSessionEntity row) {
        return new ManagedScope(row.tenant, row.namespace, row.id);
    }

    private static String resourceId(AgentServiceSessionEntity row, String fact) {
        return "resource_" + ManagedScope.fingerprint(row.id, fact);
    }

    private static String key(HttpHeaders headers) {
        return text(headers.getFirst("Idempotency-Key"), 256, "Idempotency-Key");
    }

    private static String first(Map<String, List<String>> params, String key, String fallback) {
        var values = params.get(key);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }

    private static void metadata(Object raw) {
        if (!(raw instanceof Map<?, ?> data) || data.size() > 16)
            throw invalid("metadata requires at most 16 string pairs");
        for (var entry : data.entrySet())
            if (!(entry.getKey() instanceof String key)
                    || key.length() > 64
                    || !(entry.getValue() instanceof String value)
                    || value.length() > 512) throw invalid("Invalid metadata pair");
    }

    private static void title(Object raw) {
        if (raw != null && (!(raw instanceof String value) || value.length() > 500))
            throw invalid("title must be at most 500 characters");
    }

    private static String text(Object raw, int max, String field) {
        if (!(raw instanceof String value) || value.isBlank() || value.length() > max)
            throw invalid("Invalid " + field);
        return value;
    }

    private static void fields(Map<String, Object> body, Set<String> allowed) {
        if (!allowed.containsAll(body.keySet())) throw invalid("Unknown request fields");
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw invalid("Invalid JSON");
        }
    }

    private Map<String, Object> parse(String value) {
        try {
            return map(mapper.readValue(value, Map.class));
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid Session resource JSON", invalid);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
