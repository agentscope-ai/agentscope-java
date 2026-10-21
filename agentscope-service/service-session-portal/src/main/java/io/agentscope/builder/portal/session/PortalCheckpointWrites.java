/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.portal.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.builder.portal.persistence.PortalSessionEntity;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.runtime.managed.ManagedCheckpointOperations;
import io.agentscope.builder.runtime.managed.ManagedScope;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Durable native administrative intents. Hands executes them; Session Portal has no execution lease. */
@Service
public final class PortalCheckpointWrites {
    private final PortalSessionRepository sessions;
    private final PortalSessionAccess access;
    private final ManagedCheckpointOperations operations;
    private final ObjectMapper mapper;

    public PortalCheckpointWrites(
            PortalSessionRepository sessions,
            PortalSessionAccess access,
            ManagedCheckpointOperations operations,
            ObjectMapper mapper) {
        this.sessions = sessions;
        this.access = access;
        this.operations = operations;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public Map<String, Object> stage(
            String sourceId, String targetId, Map<String, Object> body, HttpHeaders headers) {
        if (!Set.of("checkpoint_id", "target_session_id").containsAll(body.keySet())
                || !(body.get("checkpoint_id") instanceof String checkpoint)
                || checkpoint.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid checkpoint request");
        if (body.containsKey("target_session_id")
                ? sourceId.equals(targetId) || !targetId.equals(body.get("target_session_id"))
                : !sourceId.equals(targetId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Checkpoint target differs");
        String key = headers.getFirst("Idempotency-Key");
        if (key == null || key.isBlank() || key.length() > 256)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Idempotency-Key is required (at most 256 characters)");
        var actor = access.authorize(sourceId, headers, "interact");
        var source = resource(sourceId);
        var target = resource(targetId);
        access.authorizeActor(target, actor, "interact");
        if (!source.tenant.equals(target.tenant) || !source.namespace.equals(target.namespace))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Checkpoint resource scope differs");
        var receipt =
                operations.prepare(scope(source), scope(target), actor(actor), key, checkpoint);
        return operations.view(scope(source), receipt.id());
    }

    public Map<String, Object> get(String source, String id, HttpHeaders headers) {
        access.authorize(source, headers, "read");
        return operations.view(scope(resource(source)), id);
    }

    public Map<String, Object> cancel(String source, String id, HttpHeaders headers) {
        var actor = access.authorize(source, headers, "cancel");
        var scope = scope(resource(source));
        var receipt = operations.cancel(scope, id, actor(actor));
        if (!Set.of("cancelled", "completed", "rejected").contains(receipt.status()))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "workspace_import_pending: cannot release an unconfirmed import");
        return operations.view(scope, id);
    }

    public boolean owns(String source, String id) {
        var row = sessions.findById(source);
        return row.isPresent()
                && "managed".equals(row.get().runtimeKind)
                && operations.exists(scope(row.get()), id);
    }

    private PortalSessionEntity resource(String id) {
        var row =
                sessions.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"managed".equals(row.runtimeKind) || row.rootSessionId != null)
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Checkpoint writes require Managed root Sessions");
        return row;
    }

    private String actor(Map<String, Object> actor) {
        try {
            return mapper.writeValueAsString(actor);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid original checkpoint Actor", invalid);
        }
    }

    private static ManagedScope scope(PortalSessionEntity row) {
        return new ManagedScope(row.tenant, row.namespace, row.id);
    }
}
