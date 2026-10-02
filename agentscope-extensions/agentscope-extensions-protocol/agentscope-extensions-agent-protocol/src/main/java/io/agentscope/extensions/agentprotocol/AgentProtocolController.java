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

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal AgentScope task protocol ({@code /tasks/...}). */
@RestController
public class AgentProtocolController {

    private final AgentProtocolTaskStore store;

    public AgentProtocolController(AgentProtocolTaskStore store) {
        this.store = store;
    }

    @PostMapping(value = "/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, String> body) {
        String taskId = body.get("task_id");
        String agentId = body.get("agent_id");
        String input = body.get("input");
        if (taskId == null || taskId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "task_id is required"));
        }
        try {
            store.submit(taskId, agentId, input);
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
}
