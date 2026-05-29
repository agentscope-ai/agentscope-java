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
package io.agentscope.examples.shutdown.e2e;

import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.ShutdownState;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for testing graceful shutdown scenarios.
 *
 * <p>Agent operations are delegated to {@link AgentService}.
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private static final String DEFAULT_SESSION_ID = "default_01";

    private final AgentService agentService;
    private final GracefulShutdownManager shutdownManager = GracefulShutdownManager.getInstance();

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    // ==================== Chat ====================
    // for acting phase case
    @PostMapping("/chat/data-analyze")
    public ResponseEntity<Map<String, Object>> chatDataAnalyze(
            @RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        String sessionId = body.getOrDefault("sessionId", DEFAULT_SESSION_ID);

        return agentService.chatDataAnalyze(message, sessionId);
    }

    // for reasoning phase case
    @PostMapping("/chat/article-generate")
    public ResponseEntity<Map<String, Object>> chatArticleGenerate(
            @RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        String sessionId = body.getOrDefault("sessionId", DEFAULT_SESSION_ID);

        return agentService.chatArticleGenerate(message, sessionId);
    }

    // ==================== Shutdown ====================

    @PostMapping("/shutdown")
    public ResponseEntity<Map<String, Object>> shutdown() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", shutdownManager.getState().name());
        result.put("activeRequests", shutdownManager.getActiveRequestCount());
        shutdownManager.performGracefulShutdown();
        result.put("message", "Graceful shutdown initiated");
        return ResponseEntity.ok(result);
    }

    // ==================== readiness ====================

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> readiness() {
        Map<String, Object> result = new LinkedHashMap<>();
        ShutdownState state = shutdownManager.getState();
        result.put("state", state.name());
        result.put("activeRequests", shutdownManager.getActiveRequestCount());
        if (state != ShutdownState.RUNNING) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", shutdownManager.getState().name());
        result.put("activeRequests", shutdownManager.getActiveRequestCount());
        return ResponseEntity.ok(result);
    }
}
