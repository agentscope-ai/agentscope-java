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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.persistence.AgentServicePolicyEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionPermissionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionPermissionRepository;
import io.agentcore.managed.server.runtime.protocol.RuntimePayloadDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Materializes bounded-query read grants in the same transaction as their policy projection. */
@Service
public class AgentServiceSessionPermissions {
    private final AgentServiceSessionPermissionRepository permissions;
    private final ObjectMapper mapper;
    private final boolean localDev;

    public AgentServiceSessionPermissions(
            AgentServiceSessionPermissionRepository permissions,
            ObjectMapper mapper,
            @Value("${managed-agents.local-dev:${MANAGED_AGENTS_LOCAL_DEV:false}}")
                    boolean localDev) {
        this.permissions = permissions;
        this.mapper = mapper;
        this.localDev = localDev;
    }

    public void replace(AgentServiceSessionEntity session, Map<String, Object> policy) {
        if (session.policyId != null) return;
        replace(
                new Owner(
                        session.id,
                        null,
                        session.tenant,
                        session.namespace,
                        session.policyVersion,
                        session.createdAt),
                policy);
    }

    public void replaceGroup(AgentServicePolicyEntity policy, Map<String, Object> projection) {
        replace(
                new Owner(
                        "policy:" + policy.id,
                        policy.id,
                        policy.tenant,
                        policy.namespace,
                        policy.policyVersion,
                        policy.createdAt),
                projection);
    }

    private void replace(Owner owner, Map<String, Object> policy) {
        permissions.removeSession(owner.id);
        if (localDev) add(owner, "human", "local-developer", Long.MAX_VALUE);
        if (!Boolean.TRUE.equals(policy.get("enabled"))
                || !(policy.get("valid_until") instanceof Number expiry)) return;
        long until = owner.policyId == null ? expiry.longValue() : Long.MAX_VALUE;
        if (policy.get("humans") instanceof Map<?, ?> humans)
            for (var entry : humans.entrySet())
                if (entry.getValue() instanceof List<?> scopes && scopes.contains("read"))
                    add(owner, "human", String.valueOf(entry.getKey()), until);
        if (policy.get("credentials") instanceof List<?> credentials)
            for (var raw : credentials)
                if (raw instanceof Map<?, ?> key
                        && key.get("hash") instanceof String hash
                        && key.get("scopes") instanceof List<?> scopes
                        && scopes.contains("read")) {
                    long keyUntil = until;
                    if (key.get("expiresAt") != null)
                        keyUntil =
                                Math.min(
                                        keyUntil,
                                        Instant.parse(String.valueOf(key.get("expiresAt")))
                                                .toEpochMilli());
                    add(owner, "key", hash, keyUntil);
                }
    }

    private void add(Owner owner, String kind, String principal, long until) {
        var row = new AgentServiceSessionPermissionEntity();
        row.id =
                RuntimePayloadDigest.of(
                        mapper, Map.of("session", owner.id, "kind", kind, "principal", principal));
        row.sessionId = owner.id;
        row.policyId = owner.policyId;
        row.principalKind = kind;
        row.principalId = principal;
        row.tenant = owner.tenant;
        row.namespace = owner.namespace;
        row.policyVersion = owner.version;
        row.validUntil = until;
        row.createdAt = owner.createdAt;
        permissions.save(row);
    }

    private record Owner(
            String id,
            String policyId,
            String tenant,
            String namespace,
            long version,
            long createdAt) {}
}
