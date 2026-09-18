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
package io.agentscope.extensions.agentprotocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/** Internal AgentScope task protocol ({@code /tasks/...}). */
@RestController
public class AgentProtocolController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentProtocolTaskStore store;
    private final AgentProtocolProperties properties;

    public AgentProtocolController(
            AgentProtocolTaskStore store, AgentProtocolProperties properties) {
        this.store = store;
        this.properties = properties != null ? properties : new AgentProtocolProperties();
    }

    public AgentProtocolController(AgentProtocolTaskStore store) {
        this(store, new AgentProtocolProperties());
    }

    @PostMapping(value = "/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        String taskId = stringVal(body.get("task_id"));
        String agentId = stringVal(body.get("agent_id"));
        String input = stringVal(body.get("input"));
        if (taskId == null || taskId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "task_id is required"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> context =
                body.get("context") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        try {
            store.submit(taskId, agentId, input, context);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("task_id", taskId);
            ok.put("status", "pending");
            return ResponseEntity.ok(ok);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping(value = "/tasks/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> get(@PathVariable("taskId") String taskId) {
        return store.snapshot(taskId);
    }

    /** Blocks until the task completes. {@code timeout_seconds} defaults to 7200 (2 hours). */
    @GetMapping(value = "/tasks/{taskId}/wait", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> waitFor(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "timeout_seconds", defaultValue = "7200") long timeoutSeconds)
            throws Exception {
        return store.waitFor(taskId, timeoutSeconds * 1_000L);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable("taskId") String taskId) {
        store.cancel(taskId);
        return ResponseEntity.ok().build();
    }

    /**
     * SSE event stream for a task. Supports {@code from_seq} query param and {@code Last-Event-ID}
     * header for reconnect.
     */
    @GetMapping(value = "/tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "from_seq", required = false) Long fromSeq,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        if (!properties.isStreamingEnabled()) {
            return Flux.error(new IllegalStateException("streaming is disabled"));
        }
        long from = 0L;
        if (fromSeq != null && fromSeq > 0) {
            from = fromSeq;
        } else if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                from = Long.parseLong(lastEventId.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        Duration timeout = Duration.ofMillis(Math.max(1_000L, properties.getSseTimeoutMs()));
        return store.eventBus().subscribe(taskId, from).map(this::toSse).take(timeout);
    }

    @PostMapping(value = "/tasks/{taskId}/resume", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> resume(
            @PathVariable("taskId") String taskId, @RequestBody Map<String, Object> body) {
        List<RemoteConfirmDecision> decisions = parseDecisions(body.get("decisions"));
        try {
            store.resume(taskId, decisions);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("task_id", taskId);
            ok.put("status", "running");
            return ResponseEntity.ok(ok);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    private ServerSentEvent<String> toSse(RemoteAgentEvent event) {
        String data;
        try {
            data = JSON.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            data = "{\"error\":\"serialize_failed\"}";
        }
        return ServerSentEvent.<String>builder()
                .id(String.valueOf(event.getSeq()))
                .event(event.getType() != null ? event.getType().name() : "message")
                .data(data)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<RemoteConfirmDecision> parseDecisions(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<RemoteConfirmDecision> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) map;
            String toolCallId = stringVal(m.get("toolCallId"));
            if (toolCallId == null) {
                toolCallId = stringVal(m.get("tool_call_id"));
            }
            Object approved = m.get("approved");
            boolean ok =
                    approved instanceof Boolean b
                            ? b
                            : approved != null && Boolean.parseBoolean(String.valueOf(approved));
            out.add(new RemoteConfirmDecision(toolCallId, ok));
        }
        return out;
    }

    private static String stringVal(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
