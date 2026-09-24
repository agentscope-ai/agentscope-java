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
package io.agentscope.examples.copilotkit.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.examples.copilotkit.model.CopilotKitModels.ThreadInfo;
import io.agentscope.examples.copilotkit.model.CopilotKitModels.ThreadMutationRequest;
import io.agentscope.examples.copilotkit.model.CopilotKitModels.ThreadsResponse;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager.ThreadSession;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * CopilotKit thread APIs backed by {@link ThreadSessionManager} sessions.
 *
 *
 */
@Component
public final class DemoThreadStore {

    public static final String DEFAULT_AGENT_ID = "default";

    /** Demo identity; matches {@code AgentConfiguration} when {@code X-Token} is absent. */
    public static final String DEMO_USER_ID = "user-001";

    private static final TypeReference<Map<String, Object>> AGUI_EVENT_JSON =
            new TypeReference<>() {};

    private final ThreadSessionManager sessionManager;
    private final AguiAgentRegistry agentRegistry;
    private final InMemoryAgentEventStore eventStore;
    private final AgentEventAguiReplayer eventReplayer;

    public DemoThreadStore(
            ThreadSessionManager sessionManager,
            AguiAgentRegistry agentRegistry,
            InMemoryAgentEventStore eventStore,
            AgentEventAguiReplayer eventReplayer) {
        this.sessionManager = sessionManager;
        this.agentRegistry = agentRegistry;
        this.eventStore = eventStore;
        this.eventReplayer = eventReplayer;
    }

    public ThreadsResponse list(
            String agentId, boolean includeArchived, Integer limit, String cursor) {
        int offset = parseCursor(cursor);
        int pageSize = limit == null || limit <= 0 ? Integer.MAX_VALUE : limit;

        List<ThreadInfo> all =
                sessionManager.getSessions().entrySet().stream()
                        .filter(entry -> DEMO_USER_ID.equals(entry.getValue().getUserId()))
                        .filter(entry -> agentId.equals(entry.getValue().getAgentId()))
                        .filter(entry -> includeArchived || !entry.getValue().isArchived())
                        .map(
                                entry ->
                                        toThreadInfo(
                                                entry.getValue().getThreadId(), entry.getValue()))
                        .sorted(Comparator.comparing(ThreadInfo::lastRunAt).reversed())
                        .toList();

        List<ThreadInfo> page = all.stream().skip(offset).limit(pageSize).toList();
        int nextOffset = offset + page.size();
        String nextCursor = nextOffset < all.size() ? String.valueOf(nextOffset) : null;
        return new ThreadsResponse(page, nextCursor, null);
    }

    public ThreadInfo create(ThreadMutationRequest request) {
        String agentId = resolveAgentId(request);
        String name = resolveName(request, "New Thread");
        String threadId = "thread-" + UUID.randomUUID();
        ThreadSession session =
                sessionManager.ensureSession(
                        DEMO_USER_ID, threadId, agentId, name, () -> createAgent(agentId));
        return toThreadInfo(threadId, session);
    }

    public ThreadInfo update(String threadId, ThreadMutationRequest request) {
        ThreadSession session = getOrCreateSession(threadId, request);
        String name = resolveName(request, displayName(threadId, session));
        session.setName(name);
        Optional.ofNullable(request)
                .map(ThreadMutationRequest::archived)
                .ifPresent(session::setArchived);
        session.updateLastAccess();
        return toThreadInfo(threadId, session);
    }

    public ThreadInfo archive(String threadId, ThreadMutationRequest request) {
        ThreadSession session = getOrCreateSession(threadId, request);
        session.setArchived(true);
        session.updateLastAccess();
        return toThreadInfo(threadId, session);
    }

    public Map<String, Object> delete(String threadId) {
        sessionManager.removeSession(DEMO_USER_ID, threadId);
        eventStore.clear(threadId);
        return Map.of("deleted", true, "threadId", threadId);
    }

    public Map<String, Object> events(String threadId) {
        List<Map<String, Object>> events =
                eventReplayer.replay(threadId, null).stream()
                        .map(DemoThreadStore::toEventPayload)
                        .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("events", events);
        return payload;
    }

    public Map<String, Object> messages(String threadId) {
        List<Map<String, Object>> messages =
                sessionManager
                        .getSession(DEMO_USER_ID, threadId)
                        .map(ThreadSession::getAgent)
                        .filter(ReActAgent.class::isInstance)
                        .map(ReActAgent.class::cast)
                        .map(agent -> agent.getAgentState(DEMO_USER_ID, threadId).getContext())
                        .orElse(List.of())
                        .stream()
                        .map(this::toMessage)
                        .toList();
        return Map.of("messages", messages);
    }

    public Map<String, Object> state(String threadId) {
        Map<String, Object> state = new LinkedHashMap<>();
        sessionManager
                .getSession(DEMO_USER_ID, threadId)
                .ifPresent(
                        session -> {
                            state.put("agentId", session.getAgentId());
                            state.put(
                                    "hasMemory",
                                    sessionManager.hasMemory(
                                            RuntimeContext.builder()
                                                    .sessionId(threadId)
                                                    .userId(DEMO_USER_ID)
                                                    .build()));
                            state.put("archived", session.isArchived());
                            state.put("updatedAt", session.getLastAccess().toString());
                        });
        return Map.of("state", state);
    }

    private ThreadSession getOrCreateSession(String threadId, ThreadMutationRequest request) {
        String agentId = resolveAgentId(request);
        return sessionManager.ensureSession(
                DEMO_USER_ID,
                threadId,
                agentId,
                resolveName(request, null),
                () -> createAgent(agentId));
    }

    private Agent createAgent(String agentId) {
        return agentRegistry
                .getAgent(agentId)
                .orElseGet(
                        () ->
                                agentRegistry
                                        .getAgent(DEFAULT_AGENT_ID)
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "No agent registered for id: "
                                                                        + agentId)));
    }

    private ThreadInfo toThreadInfo(String threadId, ThreadSession session) {
        String createdAt = session.getCreatedAt().atOffset(ZoneOffset.UTC).toString();
        String lastAccess = session.getLastAccess().atOffset(ZoneOffset.UTC).toString();
        return new ThreadInfo(
                threadId,
                displayName(threadId, session),
                session.getAgentId(),
                session.isArchived(),
                createdAt,
                lastAccess,
                lastAccess);
    }

    private static String displayName(String threadId, ThreadSession session) {
        String name = session.getName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return threadId;
    }

    /**
     * Encode one AG-UI event the same way {@code /connect} does, and keep {@code type} even when
     * Jackson serializes the concrete record ( {@link AguiEvent#getType()} is {@code @JsonIgnore} ).
     */
    private static Map<String, Object> toEventPayload(AguiEvent event) {
        Map<String, Object> encoded =
                JsonUtils.getJsonCodec()
                        .fromJson(JsonUtils.getJsonCodec().toJson(event), AGUI_EVENT_JSON);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", event.getType().name());
        encoded.remove("type");
        payload.putAll(encoded);
        return payload;
    }

    private Map<String, Object> toMessage(Msg msg) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", msg.getId());
        message.put("role", msg.getRole().name().toLowerCase(Locale.ROOT));
        String content = msg.getTextContent();
        message.put("content", content == null ? "" : content);
        return message;
    }

    private static String resolveName(ThreadMutationRequest request, String fallback) {
        return Optional.ofNullable(request)
                .map(ThreadMutationRequest::name)
                .filter(name -> !name.isBlank())
                .orElse(fallback);
    }

    private static String resolveAgentId(ThreadMutationRequest request) {
        return Optional.ofNullable(request)
                .map(ThreadMutationRequest::agentId)
                .filter(agentId -> !agentId.isBlank())
                .orElse(DEFAULT_AGENT_ID);
    }

    private static int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(cursor), 0);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
