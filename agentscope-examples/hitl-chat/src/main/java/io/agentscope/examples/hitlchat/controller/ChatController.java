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
package io.agentscope.examples.hitlchat.controller;

import io.agentscope.examples.hitlchat.dto.ChatEvent;
import io.agentscope.examples.hitlchat.dto.ChatRequest;
import io.agentscope.examples.hitlchat.dto.McpConfigRequest;
import io.agentscope.examples.hitlchat.dto.ToolConfirmRequest;
import io.agentscope.examples.hitlchat.service.AgentService;
import io.agentscope.examples.hitlchat.service.McpService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST controller for HITL Chat API.
 *
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Chat messaging with SSE streaming (interrupt via connection cancel)</li>
 *   <li>Tool confirmation</li>
 *   <li>MCP server management</li>
 *   <li>Dangerous tools configuration</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final AgentService agentService;
    private final McpService mcpService;

    public ChatController(AgentService agentService, McpService mcpService) {
        this.agentService = agentService;
        this.mcpService = mcpService;
    }

    // ==================== Chat Endpoints ====================

    /**
     * Send a chat message and receive streaming response.
     *
     * @param request Chat request containing message and sessionId
     * @return SSE stream of chat events
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatEvent>> chat(@RequestBody ChatRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "default";
        }

        return agentService
                .chat(sessionId, request.getMessage())
                .map(event -> ServerSentEvent.<ChatEvent>builder().data(event).build());
    }

    /**
     * Confirm or reject pending tool execution.
     *
     * @param request Tool confirmation request
     * @return SSE stream of chat events
     */
    @PostMapping(value = "/chat/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatEvent>> confirmTool(@RequestBody ToolConfirmRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "default";
        }

        return agentService
                .confirmTool(
                        sessionId,
                        request.isConfirmed(),
                        request.getReason(),
                        request.getToolCalls())
                .map(event -> ServerSentEvent.<ChatEvent>builder().data(event).build());
    }

    /**
     * Clear a chat session.
     *
     * @param sessionId Session ID
     * @return Success response
     */
    @DeleteMapping("/chat/session/{sessionId}")
    public ResponseEntity<Map<String, Object>> clearSession(@PathVariable String sessionId) {
        agentService.clearSession(sessionId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Interrupt a running agent.
     *
     * @param sessionId Session ID
     * @return Success response with interrupted status
     */
    @PostMapping("/chat/interrupt/{sessionId}")
    public ResponseEntity<Map<String, Object>> interrupt(@PathVariable String sessionId) {
        boolean interrupted = agentService.interrupt(sessionId);
        return ResponseEntity.ok(Map.of("success", true, "interrupted", interrupted));
    }

    // ==================== MCP Endpoints ====================

    /**
     * Add a new MCP server.
     *
     * @param request MCP configuration request
     * @return Success response
     */
    @PostMapping("/mcp/add")
    public Mono<ResponseEntity<Map<String, Object>>> addMcpServer(
            @RequestBody McpConfigRequest request) {
        return mcpService
                .addMcpServer(request, agentService.getToolkit())
                .then(Mono.just(ResponseEntity.ok(Map.<String, Object>of("success", true))))
                .onErrorResume(
                        e ->
                                Mono.just(
                                        ResponseEntity.badRequest()
                                                .body(
                                                        Map.of(
                                                                "success",
                                                                false,
                                                                "error",
                                                                e.getMessage()))));
    }

    /**
     * Remove an MCP server.
     *
     * @param name MCP server name
     * @return Success response
     */
    @DeleteMapping("/mcp/{name}")
    public Mono<ResponseEntity<Map<String, Object>>> removeMcpServer(@PathVariable String name) {
        return mcpService
                .removeMcpServer(name, agentService.getToolkit())
                .then(Mono.just(ResponseEntity.ok(Map.<String, Object>of("success", true))))
                .onErrorResume(
                        e ->
                                Mono.just(
                                        ResponseEntity.badRequest()
                                                .body(
                                                        Map.of(
                                                                "success",
                                                                false,
                                                                "error",
                                                                e.getMessage()))));
    }

    /**
     * List all configured MCP servers.
     *
     * @return List of MCP server names
     */
    @GetMapping("/mcp/list")
    public ResponseEntity<List<String>> listMcpServers() {
        return ResponseEntity.ok(mcpService.listMcpServers());
    }

    // ==================== Tools Endpoints ====================

    /**
     * Get all available tool names from the toolkit.
     *
     * @return Set of tool names
     */
    @GetMapping("/tools")
    public ResponseEntity<Set<String>> getTools() {
        return ResponseEntity.ok(agentService.getToolNames());
    }

    /**
     * Get the list of dangerous tools.
     *
     * @return Set of dangerous tool names
     */
    @GetMapping("/settings/dangerous-tools")
    public ResponseEntity<Set<String>> getDangerousTools() {
        return ResponseEntity.ok(agentService.getConfirmationHook().getDangerousTools());
    }

    /**
     * Set the list of dangerous tools.
     *
     * @param toolNames Set of tool names to mark as dangerous
     * @return Success response
     */
    @PostMapping("/settings/dangerous-tools")
    public ResponseEntity<Map<String, Object>> setDangerousTools(
            @RequestBody Set<String> toolNames) {
        agentService.getConfirmationHook().setDangerousTools(toolNames);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
