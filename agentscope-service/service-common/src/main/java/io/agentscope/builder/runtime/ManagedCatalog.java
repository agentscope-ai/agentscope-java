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
package io.agentscope.builder.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.runtime.managed.ManagedAdmissionStore;
import io.agentscope.builder.runtime.managed.ManagedExecutionTargets;
import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.builder.runtime.managed.ManagedStateException;
import io.agentscope.builder.runtime.managed.ManagedSubagentDefinition;
import io.agentscope.builder.runtime.managed.ManagedSubagents;
import io.agentscope.builder.runtime.protocol.RuntimeSessionBootstrap;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Reads the Session Portal-admitted immutable configuration. No runtime bootstrap or ready record exists. */
@Service
public class ManagedCatalog implements ManagedExecutionTargets {
    private final ManagedAdmissionStore sessions;
    private final ObjectMapper mapper;
    private final VaultCrypto crypto;
    private final ManagedSubagents children;

    public ManagedCatalog(
            ManagedAdmissionStore sessions,
            ObjectMapper mapper,
            VaultCrypto crypto,
            ManagedSubagents children) {
        this.sessions = sessions;
        this.mapper = mapper;
        this.crypto = crypto;
        this.children = children;
    }

    public boolean owns(String id) {
        return !sessions.sessionsById(id).isEmpty();
    }

    /** Internal catalog callers use globally generated IDs; ambiguous names never select a tenant. */
    public ManagedScope scope(String id) {
        var rows = sessions.sessionsById(id);
        if (rows.size() != 1)
            throw new ManagedStateException(
                    "session_scope_required", "Session needs an explicit scope");
        return rows.get(0).scope();
    }

    public SessionResolveResult resolve(String id) {
        var rows = sessions.sessionsById(id);
        if (rows.size() != 1)
            throw new ManagedStateException(
                    "session_scope_required", "Session needs an explicit scope");
        return resolve(rows.get(0).scope());
    }

    public SessionResolveResult resolve(ManagedScope scope) {
        return resolve(scope, 0);
    }

    public ManagedSubagentDefinition childDefinition(ManagedScope scope) {
        var call = children.parent(scope).orElse(null);
        if (call == null) return null;
        try {
            var definition =
                    mapper.readValue(
                            call.specification().frozenDefinition(),
                            ManagedSubagentDefinition.class);
            var configuration = mapper.readTree(sessions.session(scope).configuration());
            if (!call.parent().messageGroup().equals(definition.parentScopeKey())
                    || !call.root()
                            .sessionId()
                            .equals(configuration.path("root_session_id").asText())
                    || !call.parent()
                            .sessionId()
                            .equals(configuration.path("parent_session_id").asText())
                    || !call.specification()
                            .frozenDefinition()
                            .equals(
                                    configuration
                                            .path("subagent")
                                            .path("specification")
                                            .path("frozenDefinition")
                                            .asText()))
                throw new ManagedStateException(
                        "subagent_definition_mismatch",
                        "Child definition differs from its durable parent call");
            return definition;
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid frozen child definition", invalid);
        }
    }

    private SessionResolveResult resolve(ManagedScope scope, int depth) {
        if (depth > 16)
            throw new ManagedStateException(
                    "subagent_depth_exceeded", "Child definition lineage exceeds 16");
        try {
            var call = children.parent(scope).orElse(null);
            if (call != null) {
                childDefinition(scope);
                var parent = resolve(call.parent(), depth + 1);
                var session = mapper.valueToTree(parent.session());
                ((ObjectNode) session).put("id", scope.sessionId()).putNull("externalKey");
                return new SessionResolveResult(
                        mapper.treeToValue(session, ManagedSessionDto.class),
                        parent.agentSnapshot(),
                        parent.workspacePath(),
                        parent.workspaceId(),
                        parent.workspaceVersion(),
                        parent.definitionFiles(),
                        parent.environment(),
                        parent.vaultCredentials(),
                        parent.memoryMounts(),
                        null,
                        Map.of());
            }
            var configuration = mapper.readTree(sessions.session(scope).configuration());
            String encrypted = configuration.path("encrypted_definition").asText();
            if (encrypted.isBlank())
                throw new ManagedStateException(
                        "definition_missing", "Session has no sealed definition");
            var definition =
                    mapper.readValue(crypto.decrypt(encrypted), RuntimeSessionBootstrap.class);
            if (!scope.tenant().equals(definition.context().tenant())
                    || !scope.namespace().equals(definition.context().namespace())
                    || !scope.sessionId().equals(definition.context().sessionId()))
                throw new ManagedStateException(
                        "definition_scope_mismatch", "Sealed definition scope differs");
            return mapper.convertValue(definition.configuration(), SessionResolveResult.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid sealed Managed definition", invalid);
        }
    }
}
