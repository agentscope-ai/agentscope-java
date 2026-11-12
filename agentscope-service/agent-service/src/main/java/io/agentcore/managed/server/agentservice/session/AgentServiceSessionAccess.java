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
package io.agentcore.managed.server.agentservice.session;

import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.runtime.protocol.InteractionCommand;
import io.agentcore.managed.server.runtime.protocol.RuntimeConfigurationOverlay;
import io.agentcore.managed.server.runtime.protocol.RuntimeRequestContext;
import io.agentcore.managed.server.web.auth.JwtService;
import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Public requests always retain the real human or application-key identity. */
@Service
public class AgentServiceSessionAccess {
    /** A Java-only authenticated carrier; public HTTP headers can never construct this type. */
    private static final class RuntimeHeaders extends HttpHeaders {
        private final RuntimeRequestContext context;
        private final String root;

        private RuntimeHeaders(RuntimeRequestContext context, String root) {
            this.context = context;
            this.root = root;
            set("Idempotency-Key", context.commandId());
            set("X-Managed-Agents-Tenant", context.tenant());
            set("X-Managed-Agents-Namespace", context.namespace());
        }
    }

    private final AgentServiceSessionRepository repository;
    private final JwtService jwt;
    private final boolean localDev;
    private final AgentServicePolicies policies;

    public AgentServiceSessionAccess(
            AgentServiceSessionRepository repository,
            JwtService jwt,
            AgentServicePolicies policies,
            @Value("${managed-agents.local-dev:${MANAGED_AGENTS_LOCAL_DEV:false}}")
                    boolean localDev) {
        this.repository = repository;
        this.jwt = jwt;
        this.policies = policies;
        this.localDev = localDev;
    }

    public Map<String, Object> authorize(String id, HttpHeaders headers, String scope) {
        var actor = authenticate(id, headers);
        authorizeActor(repository.findById(id).orElseThrow(), actor, scope);
        return actor;
    }

    /** Called only by the internal runtime controller after its service role is verified. */
    public HttpHeaders runtimeHeaders(String id, String owner, RuntimeRequestContext context) {
        var row =
                repository
                        .findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"managed".equals(row.runtimeKind)
                || !id.equals(context.sessionId())
                || !row.ownerId.equals(owner)
                || !row.tenant.equals(context.tenant())
                || !row.namespace.equals(context.namespace())
                || !Objects.equals(row.applicationId, context.applicationId()))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Runtime Session binding differs");
        checkRuntimeContext(context);
        return new RuntimeHeaders(context, row.rootSessionId);
    }

    private static void checkRuntimeContext(RuntimeRequestContext context) {
        if (context.permissionExpiresAt() <= System.currentTimeMillis()
                || Boolean.TRUE.equals(context.actor().get("internal"))
                || !(context.actor().get("user") instanceof String user)
                || user.isBlank())
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Original runtime Actor is required");
    }

    static boolean runtimeHeaders(HttpHeaders headers) {
        return headers instanceof RuntimeHeaders;
    }

    public void requireLegacyBackend(String id, HttpHeaders headers, String permission) {
        authorize(id, headers, permission);
        if ("managed".equals(repository.findById(id).orElseThrow().runtimeKind))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Managed Session interactions require /v1/sessions/{id}/events");
    }

    public Map<String, String> principal(HttpHeaders headers) {
        if (localDev) return Map.of("kind", "human", "id", "local-developer");
        String key = headers.getFirst("X-API-Key");
        if (key != null && !key.isBlank()) return Map.of("kind", "key", "id", hash(key));
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try {
            return Map.of(
                    "kind",
                    "human",
                    "id",
                    jwt.extractUserId(jwt.parse(authorization.substring(7))));
        } catch (JwtException | IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid user token");
        }
    }

    public Map<String, Object> authenticate(String id, HttpHeaders headers) {
        var row =
                repository
                        .findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return authenticate(row, headers, false);
    }

    public void authorize(AgentServiceSessionEntity row, HttpHeaders headers, String scope) {
        authorizeActor(row, authenticate(row, headers, false), scope);
    }

    /** Only lifecycle receipts and exact command retries remain accessible after a tombstone. */
    public Map<String, Object> authorizeLifecycle(String id, HttpHeaders headers, String scope) {
        var row =
                repository
                        .findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var actor = authenticate(row, headers, true);
        authorizeActor(row, actor, scope, localDev ? Map.of() : policy(row), true);
        return actor;
    }

    private Map<String, Object> authenticate(
            AgentServiceSessionEntity row, HttpHeaders headers, boolean allowDeleted) {
        if (row.rootSessionId != null) return authenticate(root(row), headers, allowDeleted);
        if (!allowDeleted && "deleted".equals(row.lifecycle))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        for (var entry :
                Map.of(
                                "X-Managed-Agents-Tenant",
                                row.tenant,
                                "X-Managed-Agents-Namespace",
                                row.namespace)
                        .entrySet()) {
            String supplied = headers.getFirst(entry.getKey());
            if (supplied != null && !supplied.equals(entry.getValue()))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (headers instanceof RuntimeHeaders runtime) {
            checkRuntimeContext(runtime.context);
            if (!row.id.equals(runtime.context.sessionId()) && !row.id.equals(runtime.root))
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Runtime request Session differs");
            return runtime.context.actor();
        }
        if (localDev) return Map.of("user", "local-developer", "key_hash", "", "internal", false);
        String key = headers.getFirst("X-API-Key");
        Map<String, Object> actor;
        if (key != null && !key.isBlank()) {
            actor = Map.of("user", row.ownerId, "key_hash", hash(key), "internal", false);
        } else {
            String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
            if (authorization == null || !authorization.startsWith("Bearer "))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            String user;
            try {
                user = jwt.extractUserId(jwt.parse(authorization.substring(7)));
            } catch (JwtException | IllegalArgumentException error) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid user token");
            }
            actor = Map.of("user", user, "key_hash", "", "internal", false);
        }
        return actor;
    }

    public void authorizePage(
            List<AgentServiceSessionEntity> rows, HttpHeaders headers, String scope) {
        var page = policies.readPage(rows);
        for (var row : rows)
            authorizeActor(row, authenticate(row, headers, false), scope, page.get(row.id), false);
    }

    /** Rechecks the persisted original caller. Internal transport credentials never replace it. */
    public void authorizeActor(
            AgentServiceSessionEntity row, Map<String, Object> actor, String scope) {
        authorizeActor(row, actor, scope, localDev ? Map.of() : policy(row), false);
    }

    public void authorizeLifecycleActor(
            AgentServiceSessionEntity row, Map<String, Object> actor, String scope) {
        authorizeActor(row, actor, scope, localDev ? Map.of() : policy(row), true);
    }

    private void authorizeActor(
            AgentServiceSessionEntity row,
            Map<String, Object> actor,
            String scope,
            Map<String, Object> policy,
            boolean allowDeleted) {
        if (row.rootSessionId != null) {
            if (!"read".equals(scope))
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Child controls use the root Session");
            var root = root(row);
            authorizeActor(root, actor, scope, localDev ? Map.of() : policy(root), allowDeleted);
            return;
        }
        if (!(actor.get("user") instanceof String user)
                || user.isBlank()
                || !(actor.get("key_hash") instanceof String digest)
                || !Boolean.FALSE.equals(actor.get("internal")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Original caller is required");
        if (!allowDeleted && "deleted".equals(row.lifecycle))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (localDev) return;
        if (!Boolean.TRUE.equals(policy.get("enabled")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Object expiry = policy.get("valid_until");
        if (!(expiry instanceof Number until) || until.longValue() <= System.currentTimeMillis())
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Session authorization projection expired");
        if (!digest.isBlank()) {
            if (!row.ownerId.equals(user)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            for (Object value : (List<?>) policy.getOrDefault("credentials", List.of())) {
                if (!(value instanceof Map<?, ?> credential)) continue;
                if (!MessageDigest.isEqual(
                        digest.getBytes(StandardCharsets.US_ASCII),
                        String.valueOf(credential.get("hash")).getBytes(StandardCharsets.US_ASCII)))
                    continue;
                if (credential.get("expiresAt") != null
                        && !Instant.parse(String.valueOf(credential.get("expiresAt")))
                                .isAfter(Instant.now()))
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
                if (!(credential.get("scopes") instanceof List<?> scopes)
                        || !scopes.contains(scope))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN);
                return;
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (!(policy.get("humans") instanceof Map<?, ?> humans)
                || !(humans.get(user) instanceof List<?> scopes)
                || !scopes.contains(scope)
                        && !("webhooks:write".equals(scope) && scopes.contains("interact")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    public void authorizeInteraction(
            AgentServiceSessionEntity session, InteractionCommand command) {
        authorizeInteraction(session, command, command.context().actor());
    }

    public void authorizeInteraction(
            AgentServiceSessionEntity session,
            InteractionCommand command,
            Map<String, Object> actor) {
        String scope =
                switch (command.kind()) {
                    case "approval" -> "approve";
                    case "tool_retry", "workspace_recover" -> "interact";
                    case "signal" ->
                            command.context().actor().containsKey("agent_task")
                                    ? "invoke"
                                    : "interact";
                    case "tool_result" ->
                            command.context().actor().containsKey("hands_task")
                                    ? "invoke"
                                    : "interact";
                    case "cancel" -> "cancel";
                    default -> "invoke";
                };
        if ("approval".equals(command.kind())
                && command.context().actor().containsKey("agent_task"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agents cannot approve tools");
        authorizeActor(session, actor, scope);
        if ("approval".equals(command.kind())
                && command.payload().get("answers") instanceof List<?> answers)
            for (var raw : answers)
                if (raw instanceof Map<?, ?> answer
                        && "external_execution".equals(answer.get("kind")))
                    authorizeActor(session, actor, "interact");
    }

    public Map<String, Object> policy(AgentServiceSessionEntity session) {
        return policies.read(session.rootSessionId == null ? session : root(session));
    }

    public long policyVersion(AgentServiceSessionEntity session) {
        return policies.version(session.rootSessionId == null ? session : root(session));
    }

    public RuntimeConfigurationOverlay configurationOverlay(
            AgentServiceSessionEntity session, long version) {
        return policies.configurationOverlay(
                session.rootSessionId == null ? session : root(session), version);
    }

    private AgentServiceSessionEntity root(AgentServiceSessionEntity child) {
        return repository
                .findById(child.rootSessionId)
                .filter(row -> row.rootSessionId == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
