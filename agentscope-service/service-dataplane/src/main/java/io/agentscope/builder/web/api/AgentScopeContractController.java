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
package io.agentscope.builder.web.api;

import io.agentscope.builder.control.ControlPlaneClient;
import io.agentscope.builder.control.SessionListItem;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.SessionEventTypes;
import io.agentscope.builder.web.managed.SessionStatuses;
import io.agentscope.builder.web.managed.service.ManagedJsonHelper;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntityRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * aistio data-plane HTTP contract ({@code /agentscope/*}). Public so the control-plane poller can
 * probe without a console JWT. Session rows come from the control plane; transcripts from the local
 * event log.
 */
@RestController
@RequestMapping("/agentscope")
public class AgentScopeContractController {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeContractController.class);

    private static final String RUNTIME = "agentscope-java";
    private static final String FRAMEWORK = "agentscope-java";
    private static final int CONTRACT_LEVEL = 3;
    private static final List<String> CAPABILITIES =
            List.of("session-reporting", "context-query", "message-query", "session-command");
    private static final Set<String> MESSAGE_EVENT_TYPES =
            Set.of(
                    SessionEventTypes.USER_MESSAGE,
                    SessionEventTypes.AGENT_MESSAGE,
                    SessionEventTypes.SYSTEM_MESSAGE,
                    SessionEventTypes.AGENT_THREAD_CONTEXT_COMPACTED,
                    SessionEventTypes.AGENT_TOOL_USE,
                    SessionEventTypes.AGENT_TOOL_RESULT,
                    SessionEventTypes.USER_TOOL_RESULT,
                    SessionEventTypes.USER_CUSTOM_TOOL_RESULT);
    private static final Set<String> CONTEXT_MESSAGE_TYPES =
            Set.of(
                    SessionEventTypes.USER_MESSAGE,
                    SessionEventTypes.AGENT_MESSAGE,
                    SessionEventTypes.SYSTEM_MESSAGE,
                    SessionEventTypes.AGENT_THREAD_CONTEXT_COMPACTED);
    private static final List<String> USER_AGENT_MESSAGE_TYPES =
            List.of(SessionEventTypes.USER_MESSAGE, SessionEventTypes.AGENT_MESSAGE);

    private final ControlPlaneClient controlPlaneClient;
    private final DataSessionService sessionService;
    private final SessionEventLog eventLog;
    private final SessionEventEntityRepository eventRepository;
    private final ManagedJsonHelper jsonHelper;
    private final int serverPort;
    private final String agentName;
    private final String version;

    public AgentScopeContractController(
            ControlPlaneClient controlPlaneClient,
            DataSessionService sessionService,
            SessionEventLog eventLog,
            SessionEventEntityRepository eventRepository,
            ManagedJsonHelper jsonHelper,
            @Value("${server.port:8082}") int serverPort,
            @Value("${builder.dataplane.agent-name:agentscope-java-dataplane}") String agentName,
            @Value("${builder.dataplane.version:2.0.1}") String version) {
        this.controlPlaneClient = controlPlaneClient;
        this.sessionService = sessionService;
        this.eventLog = eventLog;
        this.eventRepository = eventRepository;
        this.jsonHelper = jsonHelper;
        this.serverPort = serverPort;
        this.agentName = agentName;
        this.version = version;
    }

    /** Level-1 metadata for discovery / registry aggregation. */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", agentName);
        body.put("runtime", RUNTIME);
        body.put("version", version);
        body.put("contractLevel", CONTRACT_LEVEL);
        body.put("capabilities", CAPABILITIES);
        body.put("port", serverPort);
        return body;
    }

    /** Level-1 health probe. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /** Level-2 session snapshots for the aistiod poller. */
    @GetMapping("/sessions")
    public Map<String, Object> sessions() {
        List<Map<String, Object>> snapshots = new ArrayList<>();
        List<SessionListItem> items;
        try {
            items = controlPlaneClient.listSessions(500);
        } catch (ResponseStatusException ex) {
            log.warn("agentscope sessions: control-plane list failed: {}", ex.getReason());
            return Map.of("sessions", List.of());
        }
        for (SessionListItem item : items) {
            if (item == null || item.id() == null || item.id().isBlank()) {
                continue;
            }
            if (isInactive(item.status()) || item.archivedAt() != null) {
                continue;
            }
            snapshots.add(toSnapshot(item));
        }
        return Map.of("sessions", snapshots);
    }

    /** Level-3 compress command (best-effort; no harness compact API yet). */
    @PostMapping("/sessions/{id}/compress")
    public Map<String, String> compress(@PathVariable("id") String id) {
        requireKnownSession(id);
        log.info("agentscope compress requested for session {} (best-effort / no-op)", id);
        try {
            eventLog.append(
                    id,
                    SessionEventTypes.SESSION_UPDATED,
                    Map.of("command", "compress", "status", "initiated"));
        } catch (Exception ex) {
            log.debug("compress event append failed for {}: {}", id, ex.getMessage());
        }
        return Map.of("sessionId", id, "command", "compress", "status", "initiated");
    }

    /** Level-3 terminate command. */
    @PostMapping("/sessions/{id}/terminate")
    public Map<String, String> terminate(@PathVariable("id") String id) {
        SessionResolveResult resolved = requireKnownSession(id);
        String ownerId = resolved.session().ownerId();
        sessionService.updateStatus(
                ownerId, id, DataSessionService.STATUS_TERMINATED, Map.of("source", "agentscope"));
        return Map.of("sessionId", id, "command", "terminate", "status", "initiated");
    }

    /** Level-4 effective context exported from the event log + agent snapshot. */
    @GetMapping("/sessions/{id}/context")
    public Map<String, Object> context(@PathVariable("id") String id) {
        SessionResolveResult resolved = requireKnownSession(id);
        List<SessionEventDto> events = eventLog.list(id);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (SessionEventDto event : events) {
            if (!CONTEXT_MESSAGE_TYPES.contains(event.type())) {
                continue;
            }
            Map<String, Object> msg = toContextMessage(event);
            if (msg != null) {
                messages.add(msg);
            }
        }
        String systemPrompt = extractSystemPrompt(resolved.agentSnapshot());
        boolean compacted =
                eventRepository.existsBySessionIdAndEventType(
                        id, SessionEventTypes.AGENT_THREAD_CONTEXT_COMPACTED);
        String contextHash = hashContext(systemPrompt, messages);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", id);
        body.put("capturedAt", Instant.now().toString());
        body.put("contextHash", contextHash);
        body.put("systemPrompt", systemPrompt != null ? systemPrompt : "");
        body.put("messages", messages);
        body.put("isCompacted", compacted);
        body.put("originalMessageCount", messages.size());
        body.put("framework", FRAMEWORK);
        return body;
    }

    /** Level-3 paginated full-history messages from the event log. */
    @GetMapping("/sessions/{id}/messages")
    public Map<String, Object> messages(
            @PathVariable("id") String id,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        requireKnownSession(id);
        int safeOffset = Math.max(0, offset);
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 1000);
        List<SessionEventEntity> events =
                eventRepository.findBySessionIdAndEventTypeInOrderBySeqAsc(id, MESSAGE_EVENT_TYPES);
        int total = events.size();
        int to = Math.min(safeOffset + safeLimit, total);
        List<Map<String, Object>> page = new ArrayList<>();
        if (safeOffset < total) {
            for (SessionEventEntity entity : events.subList(safeOffset, to)) {
                Map<String, Object> item = toMessageItem(entity);
                if (item != null) {
                    page.add(item);
                }
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", id);
        body.put("offset", safeOffset);
        body.put("limit", safeLimit);
        body.put("total", total);
        body.put("messages", page);
        return body;
    }

    private Map<String, Object> toSnapshot(SessionListItem item) {
        String id = item.id();
        long messageCount =
                eventRepository.countBySessionIdAndEventTypeIn(id, USER_AGENT_MESSAGE_TYPES);
        long lastEventAt = eventRepository.maxCreatedAt(id);
        long lastActiveMs = Math.max(item.updatedAt(), lastEventAt);
        long promptTokens = 0L;
        long completionTokens = 0L;
        List<SessionEventEntity> usageEvents =
                eventRepository.findBySessionIdAndEventTypeInOrderBySeqAsc(
                        id, List.of(SessionEventTypes.SPAN_MODEL_REQUEST_END));
        for (SessionEventEntity entity : usageEvents) {
            long[] tokens = extractUsage(entity.getPayloadJson());
            promptTokens += tokens[0];
            completionTokens += tokens[1];
        }
        boolean compacted =
                eventRepository.existsBySessionIdAndEventType(
                        id, SessionEventTypes.AGENT_THREAD_CONTEXT_COMPACTED);
        int effective =
                compacted
                        ? (int)
                                eventRepository.countBySessionIdAndEventTypeIn(
                                        id, CONTEXT_MESSAGE_TYPES)
                        : (int) messageCount;
        String contextHash =
                hashContext(
                        null,
                        List.of(
                                Map.of(
                                        "id",
                                        id,
                                        "messageCount",
                                        messageCount,
                                        "updatedAt",
                                        item.updatedAt())));

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", id);
        snap.put("phase", toPhase(item.status()));
        snap.put("startedAt", toRfc3339(item.createdAt()));
        snap.put("lastActiveAt", toRfc3339(lastActiveMs > 0 ? lastActiveMs : item.updatedAt()));
        snap.put("messageCount", (int) messageCount);
        snap.put(
                "tokenUsage",
                Map.of("promptTokens", promptTokens, "completionTokens", completionTokens));
        snap.put("contextPressure", 0.0d);
        snap.put("framework", FRAMEWORK);
        snap.put("contextHash", contextHash);
        snap.put("isCompacted", compacted);
        snap.put("effectiveMessageCount", effective);
        return snap;
    }

    private SessionResolveResult requireKnownSession(String id) {
        return controlPlaneClient.resolveSession(id);
    }

    private static boolean isInactive(String status) {
        return SessionStatuses.TERMINATED.equals(status) || SessionStatuses.ARCHIVED.equals(status);
    }

    private static String toPhase(String status) {
        if (status == null) {
            return "active";
        }
        return switch (status) {
            case SessionStatuses.IDLE -> "idle";
            case SessionStatuses.TERMINATED, SessionStatuses.ARCHIVED -> "terminated";
            default -> "active";
        };
    }

    private static String toRfc3339(long epochMillis) {
        if (epochMillis <= 0L) {
            return Instant.EPOCH.toString();
        }
        return Instant.ofEpochMilli(epochMillis).toString();
    }

    private static String extractSystemPrompt(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return "";
        }
        Object system = snapshot.get("system");
        if (system instanceof String s && !s.isBlank()) {
            return s;
        }
        Object sysPrompt = snapshot.get("sysPrompt");
        if (sysPrompt instanceof String s && !s.isBlank()) {
            return s;
        }
        return "";
    }

    private Map<String, Object> toContextMessage(SessionEventDto event) {
        String role = roleForType(event.type());
        if (role == null) {
            return null;
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", textFromPayload(event.payload()));
        if (SessionEventTypes.AGENT_THREAD_CONTEXT_COMPACTED.equals(event.type())) {
            msg.put("isCompaction", true);
        }
        return msg;
    }

    private Map<String, Object> toMessageItem(SessionEventEntity entity) {
        String role = roleForType(entity.getEventType());
        if (role == null) {
            return null;
        }
        Map<String, Object> payload = jsonHelper.readMap(entity.getPayloadJson());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("seq", (int) Math.min(Integer.MAX_VALUE, entity.getSeq()));
        item.put("role", role);
        item.put("content", textFromPayload(payload));
        if (payload.get("name") instanceof String name) {
            item.put("toolName", name);
        } else if (payload.get("toolName") instanceof String toolName) {
            item.put("toolName", toolName);
        }
        if (payload.get("input") != null) {
            item.put("toolInput", payload.get("input"));
        }
        if (SessionEventTypes.AGENT_TOOL_RESULT.equals(entity.getEventType())
                || SessionEventTypes.USER_TOOL_RESULT.equals(entity.getEventType())
                || SessionEventTypes.USER_CUSTOM_TOOL_RESULT.equals(entity.getEventType())) {
            Object out = payload.get("output");
            if (out == null) {
                out = payload.get("text");
            }
            if (out != null) {
                item.put("toolOutput", String.valueOf(out));
            }
        }
        item.put("occurredAt", toRfc3339(entity.getCreatedAt()));
        return item;
    }

    private static String roleForType(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case SessionEventTypes.USER_MESSAGE,
                    SessionEventTypes.USER_TOOL_RESULT,
                    SessionEventTypes.USER_CUSTOM_TOOL_RESULT ->
                    "user";
            case SessionEventTypes.AGENT_MESSAGE,
                    SessionEventTypes.AGENT_TOOL_USE,
                    SessionEventTypes.AGENT_TOOL_RESULT ->
                    "assistant";
            case SessionEventTypes.SYSTEM_MESSAGE,
                    SessionEventTypes.AGENT_THREAD_CONTEXT_COMPACTED ->
                    "system";
            default -> null;
        };
    }

    private static String textFromPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        for (String key : List.of("text", "message", "content", "summary")) {
            Object value = payload.get(key);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
            if (value instanceof List<?> list && !list.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Object part : list) {
                    if (part instanceof Map<?, ?> m) {
                        Object t = m.get("text");
                        if (t != null) {
                            if (!sb.isEmpty()) {
                                sb.append('\n');
                            }
                            sb.append(t);
                        }
                    }
                }
                if (!sb.isEmpty()) {
                    return sb.toString();
                }
            }
        }
        return "";
    }

    private long[] extractUsage(String payloadJson) {
        Map<String, Object> payload = jsonHelper.readMap(payloadJson);
        Object usage = payload.get("usage");
        if (!(usage instanceof Map<?, ?> map)) {
            return new long[] {0L, 0L};
        }
        return new long[] {asLong(map.get("inputTokens")), asLong(map.get("outputTokens"))};
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static String hashContext(String systemPrompt, List<Map<String, Object>> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (systemPrompt != null) {
                digest.update(systemPrompt.getBytes(StandardCharsets.UTF_8));
            }
            digest.update(String.valueOf(messages).getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception ex) {
            return "";
        }
    }
}
