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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceEventEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceEventRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.runtime.protocol.RuntimeFact;
import io.agentcore.managed.server.runtime.protocol.RuntimePayloadDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Runs inside the root fact transaction; children have independent public sequence and resources. */
@Service
public class AgentServiceChildProjection {
    private final AgentServiceSessionRepository sessions;
    private final AgentServiceEventRepository events;
    private final AgentServiceResourceProjection resources;
    private final AgentServiceEventNotifications notifications;
    private final ObjectMapper mapper;

    public AgentServiceChildProjection(
            AgentServiceSessionRepository sessions,
            AgentServiceEventRepository events,
            AgentServiceResourceProjection resources,
            AgentServiceEventNotifications notifications,
            ObjectMapper mapper) {
        this.sessions = sessions;
        this.events = events;
        this.resources = resources;
        this.notifications = notifications;
        this.mapper = mapper;
    }

    public void spawned(AgentServiceSessionEntity root, RuntimeFact fact) {
        register(root, root.id, fact.payload());
    }

    /** Returns a bounded parent summary; full child event data stays in the child's own stream. */
    public Map<String, Object> record(AgentServiceSessionEntity root, RuntimeFact fact) {
        String id = text(fact.payload().get("child_session_id"));
        String parent = text(fact.payload().get("parent_session_id"));
        String type = text(fact.payload().get("event_type"));
        if (!(fact.payload().get("event_payload") instanceof Map<?, ?> raw)) throw invalid();
        var data = new LinkedHashMap<String, Object>();
        raw.forEach((key, value) -> data.put(String.valueOf(key), value));
        var child = sessions.lockById(id).orElseThrow(AgentServiceChildProjection::invalid);
        if (!root.id.equals(child.rootSessionId) || !parent.equals(child.parentSessionId))
            throw invalid();
        if ("subagent.started".equals(type)) register(root, id, data);
        var event = new AgentServiceEventEntity();
        event.id =
                RuntimePayloadDigest.of(mapper, Map.of("child", id, "root_fact", fact.eventId()));
        event.sessionId = id;
        event.turnId = data.get("turn_id") instanceof String turn ? turn : null;
        event.seq = ++child.nextEventSequence;
        event.type = type;
        data.put("schema_version", 1);
        data.put(
                "runtime_source",
                Map.of(
                        "backend",
                        fact.source(),
                        "event_id",
                        fact.eventId(),
                        "sequence",
                        fact.sourceSequence(),
                        "root_session_id",
                        root.id,
                        "root_turn_id",
                        fact.context().turnId()));
        event.data = json(data);
        event.createdAt = System.currentTimeMillis();
        events.saveAndFlush(event);
        resources.apply(child, event);
        sessions.saveAndFlush(child);
        notifications.committed(id);
        var summary = new LinkedHashMap<String, Object>();
        summary.put("childSessionId", id);
        summary.put("parentSessionId", parent);
        summary.put("child_sequence", event.seq);
        summary.put("child_event_type", type);
        if (type.startsWith("turn.")) summary.put("status", type.substring(5));
        return summary;
    }

    private void register(AgentServiceSessionEntity root, String parent, Map<String, Object> data) {
        String id = text(data.get("childSessionId"));
        if (id.equals(root.id) || id.equals(parent)) throw invalid();
        var known = sessions.lockById(id).orElse(null);
        if (known != null) {
            if (!Objects.equals(known.rootSessionId, root.id)
                    || !Objects.equals(known.parentSessionId, parent)) throw invalid();
            return;
        }
        var child = new AgentServiceSessionEntity();
        child.id = id;
        child.rootSessionId = root.id;
        child.parentSessionId = parent;
        child.tenant = root.tenant;
        child.namespace = root.namespace;
        child.ownerId = root.ownerId;
        child.applicationId = root.applicationId;
        child.runtimeKind = root.runtimeKind;
        child.commandId = RuntimePayloadDigest.of(mapper, Map.of("root", root.id, "child", id));
        child.revision = root.revision;
        child.digest = child.commandId;
        child.state = "ready";
        child.lifecycle = "active";
        child.policy = "{}";
        var view = new LinkedHashMap<String, Object>();
        view.put("id", id);
        view.put("root_session_id", root.id);
        view.put("parent_session_id", parent);
        if (data.get("childAgentId") != null) view.put("agentId", data.get("childAgentId"));
        child.publicView = json(view);
        child.createdAt = System.currentTimeMillis();
        child.updatedAt = child.createdAt;
        sessions.saveAndFlush(child);
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 256)
            throw invalid();
        return text;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Child fact has no matching committed parent link");
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode child projection", error);
        }
    }
}
