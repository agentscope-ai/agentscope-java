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
package io.agentscope.builder.control;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.builder.web.managed.EnvironmentDto;
import io.agentscope.builder.web.managed.SessionEventDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Data-plane HTTP client for control-plane (aistiod) internal APIs. Session and environment
 * metadata live in the CP schema; the data plane must not SELECT those tables directly.
 *
 * <p>Authenticates with {@code X-Builder-Internal-Token}; optional {@code
 * X-Builder-Internal-User} attributes the call to an acting owner. Methods currently {@code
 * block()} for simplicity — callers on WebFlux handlers must schedule onto {@code
 * Schedulers.boundedElastic()} (see {@code DataSessionApiController}).
 */
@Service
public class ControlPlaneClient {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneClient.class);

    private final WebClient webClient;
    private final String controlPlaneUrl;
    private final String internalToken;
    private final ObjectMapper objectMapper;
    private final Map<String, ManagedExecutionScope> managedExecutionScopes =
            new ConcurrentHashMap<>();

    public ControlPlaneClient(
            @Value("${builder.control-plane-url:http://localhost:8081}") String controlPlaneUrl,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String internalToken,
            ObjectMapper objectMapper) {
        this.controlPlaneUrl = controlPlaneUrl.replaceAll("/+$", "");
        this.webClient = WebClient.builder().baseUrl(this.controlPlaneUrl).build();
        this.internalToken = internalToken;
        this.objectMapper =
                objectMapper
                        .copy()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** Absolute URL of the task-scoped collaboration MCP endpoint. */
    public String collaborationMcpUrl() {
        return controlPlaneUrl + "/mcp/collaboration";
    }

    /** Mirrors one durable managed event into the control-plane session read model. */
    public void appendSessionEvent(SessionEventDto event) {
        appendSessionEvent(event, managedExecutionScopes.get(event.sessionId()));
    }

    /** Mirrors one event with an explicit immutable fence, including from a different replica. */
    @SuppressWarnings("unchecked")
    public void appendSessionEvent(SessionEventDto event, ManagedExecutionScope scope) {
        Map<String, Object> body = objectMapper.convertValue(event, LinkedHashMap.class);
        if (scope != null) {
            if (scope.agentTaskId() != null && !scope.agentTaskId().isBlank()) {
                body.put("agentTaskId", scope.agentTaskId());
            }
            body.put("attemptId", scope.attemptId());
            body.put("dispatchGeneration", scope.dispatchGeneration());
            body.put("turnId", scope.turnId());
        }
        webClient
                .post()
                .uri("/api/internal/runtime-sessions/{sessionId}/events", event.sessionId())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(internalHeaders(null))
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    /** Renews the current managed AgentTask attempt while its model turn is active. */
    public void heartbeatManagedExecution(String sessionId) {
        ManagedExecutionScope scope = managedExecutionScopes.get(sessionId);
        if (scope == null) {
            return;
        }
        heartbeatManagedExecution(sessionId, scope);
    }

    /** Heartbeats one captured turn without accidentally adopting a newer session scope. */
    public void heartbeatManagedExecution(String sessionId, ManagedExecutionScope scope) {
        if (scope == null) {
            return;
        }
        webClient
                .post()
                .uri("/api/internal/runtime-sessions/{sessionId}/heartbeat", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(internalHeaders(null))
                .bodyValue(
                        Map.of(
                                "attemptId",
                                scope.attemptId(),
                                "dispatchGeneration",
                                scope.dispatchGeneration(),
                                "turnId",
                                scope.turnId()))
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    /**
     * Resolves a session plus agent snapshot, environment, vault credentials and memory mounts in
     * one round-trip.
     */
    public SessionResolveResult resolveSession(String sessionId) {
        return resolveSession(sessionId, null);
    }

    /** Resolves a session, optionally attributing the call to {@code actingUserId}. */
    public SessionResolveResult resolveSession(String sessionId, String actingUserId) {
        try {
            String body =
                    webClient
                            .get()
                            .uri("/api/internal/sessions/{id}/resolve", sessionId)
                            .headers(internalHeaders(actingUserId))
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
            SessionResolveResult result =
                    body == null || body.isBlank()
                            ? null
                            : objectMapper.readValue(body, SessionResolveResult.class);
            if (result == null || result.session() == null) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Session not found: " + sessionId);
            }
            return result;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (WebClientResponseException ex) {
            throw mapWebClientError(ex, "Session not found: " + sessionId);
        } catch (Exception ex) {
            log.warn("resolveSession failed for {}: {}", sessionId, ex.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Control plane resolve failed for session "
                            + sessionId
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
    }

    /**
     * Captures the Attempt fence for one admitted physical turn. Later session resolves may observe
     * a retry Attempt, but events from this turn must remain attached to the Attempt that launched
     * it until {@link #endManagedExecution(String)} is called.
     */
    public ManagedExecutionScope beginManagedExecution(String sessionId) {
        SessionResolveResult result = resolveSession(sessionId);
        ManagedExecutionScope scope = executionScope(result.executionContext());
        if (scope == null) {
            managedExecutionScopes.remove(sessionId);
        } else {
            managedExecutionScopes.put(sessionId, scope);
        }
        return scope;
    }

    /** Releases the immutable Attempt fence captured for a completed physical turn. */
    public void endManagedExecution(String sessionId) {
        managedExecutionScopes.remove(sessionId);
    }

    /** Releases this turn's scope without deleting a newer turn that reused the same session. */
    public void endManagedExecution(String sessionId, ManagedExecutionScope expectedScope) {
        if (expectedScope != null) {
            managedExecutionScopes.remove(sessionId, expectedScope);
        }
    }

    /** Returns the immutable AgentTask execution fence captured for the active physical turn. */
    public ManagedExecutionScope managedExecutionScope(String sessionId) {
        return managedExecutionScopes.get(sessionId);
    }

    private static ManagedExecutionScope executionScope(Map<String, Object> executionContext) {
        if (executionContext == null
                || !(executionContext.get("attemptId") instanceof String attemptId)
                || attemptId.isBlank()) {
            return null;
        }
        long generation =
                executionContext.get("dispatchGeneration") instanceof Number n ? n.longValue() : 0L;
        String turnId = String.valueOf(executionContext.getOrDefault("turnId", ""));
        String agentTaskId = managedAgentTaskId(executionContext);
        String tenant = managedTaskValue(executionContext, "tenant");
        return new ManagedExecutionScope(tenant, agentTaskId, attemptId, generation, turnId);
    }

    private static String managedAgentTaskId(Map<String, Object> executionContext) {
        Object direct = executionContext.get("agentTaskId");
        if (direct != null && !String.valueOf(direct).isBlank()) {
            return String.valueOf(direct);
        }
        return managedTaskValue(executionContext, "id");
    }

    private static String managedTaskValue(Map<String, Object> executionContext, String fieldName) {
        Object taskContext = executionContext.get("taskContext");
        if (!(taskContext instanceof Map<?, ?> context)
                || !(context.get("task") instanceof Map<?, ?> task)) {
            return null;
        }
        Object value = task.get(fieldName);
        return value != null && !String.valueOf(value).isBlank() ? String.valueOf(value) : null;
    }

    /** Immutable fence attached to all events emitted by one admitted Managed AgentTask turn. */
    public record ManagedExecutionScope(
            String tenant,
            String agentTaskId,
            String attemptId,
            long dispatchGeneration,
            String turnId) {}

    /**
     * Lists recent product sessions for data-plane contract probing ({@code GET
     * /agentscope/sessions}).
     */
    public List<SessionListItem> listSessions() {
        return listSessions(500);
    }

    /**
     * Lists recent product sessions, capped at {@code limit} (server may clamp further).
     *
     * @param limit preferred upper bound
     * @return sessions newest-first; empty when the CP returns none
     */
    @SuppressWarnings("unchecked")
    public List<SessionListItem> listSessions(int limit) {
        int capped = Math.max(1, Math.min(limit, 2000));
        try {
            String body =
                    webClient
                            .get()
                            .uri(
                                    uriBuilder ->
                                            uriBuilder
                                                    .path("/api/internal/sessions")
                                                    .queryParam("limit", capped)
                                                    .build())
                            .headers(internalHeaders(null))
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
            if (body == null || body.isBlank()) {
                return List.of();
            }
            Map<String, Object> root = objectMapper.readValue(body, Map.class);
            Object sessions = root.get("sessions");
            if (!(sessions instanceof List<?> raw) || raw.isEmpty()) {
                return List.of();
            }
            return objectMapper.convertValue(
                    raw,
                    objectMapper
                            .getTypeFactory()
                            .constructCollectionType(List.class, SessionListItem.class));
        } catch (WebClientResponseException ex) {
            throw mapWebClientError(ex, "Failed to list sessions from control plane");
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("listSessions failed: {}", ex.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Control plane session list failed: " + ex.getMessage(),
                    ex);
        }
    }

    /**
     * Registers this data-plane instance with aistiod ({@code POST /api/v1/dataplanes/register}).
     *
     * @return heartbeat interval seconds from the response, or 15 when absent
     */
    public long registerDataPlane(Map<String, Object> body) {
        try {
            Map<?, ?> resp =
                    webClient
                            .post()
                            .uri("/api/v1/dataplanes/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .headers(internalHeaders(null))
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .block();
            if (resp != null && resp.get("heartbeatInterval") instanceof Number n) {
                return Math.max(5L, n.longValue());
            }
            return 15L;
        } catch (WebClientResponseException ex) {
            throw mapWebClientError(ex, "Data plane register rejected");
        } catch (Exception ex) {
            log.warn("registerDataPlane failed: {}", ex.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Control plane register failed: " + ex.getMessage(),
                    ex);
        }
    }

    /** Heartbeats a registered instance ({@code POST /api/v1/dataplanes/{id}/heartbeat}). */
    public void heartbeatDataPlane(String instanceId) {
        try {
            webClient
                    .post()
                    .uri("/api/v1/dataplanes/{instanceId}/heartbeat", instanceId)
                    .headers(internalHeaders(null))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException ex) {
            throw mapWebClientError(ex, "Unknown data plane instance: " + instanceId);
        } catch (Exception ex) {
            log.warn("heartbeatDataPlane failed for {}: {}", instanceId, ex.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Control plane heartbeat failed for " + instanceId + ": " + ex.getMessage(),
                    ex);
        }
    }

    /** Unregisters a data-plane instance ({@code DELETE /api/v1/dataplanes/{id}}). */
    public void deleteDataPlane(String instanceId) {
        try {
            webClient
                    .delete()
                    .uri("/api/v1/dataplanes/{instanceId}", instanceId)
                    .headers(internalHeaders(null))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return;
            }
            log.warn("deleteDataPlane failed for {}: {}", instanceId, ex.getMessage());
        } catch (Exception ex) {
            log.warn("deleteDataPlane failed for {}: {}", instanceId, ex.getMessage());
        }
    }

    /**
     * Patches runtime fields ({@code status}, {@code stopReason}) on a session. Lifecycle fields
     * remain owned by the control plane.
     */
    public void patchSessionRuntime(
            String sessionId, String status, Map<String, Object> stopReason) {
        patchSessionRuntime(sessionId, status, stopReason, null, null);
    }

    /** Patches session runtime status, optionally attributing the call to {@code actingUserId}. */
    public void patchSessionRuntime(
            String sessionId, String status, Map<String, Object> stopReason, String actingUserId) {
        patchSessionRuntime(sessionId, status, stopReason, actingUserId, null);
    }

    /**
     * Patches runtime status with the immutable fence captured when this physical turn was
     * admitted. A delayed turn must never adopt a newer Attempt's session-scoped fence.
     */
    public void patchSessionRuntime(
            String sessionId,
            String status,
            Map<String, Object> stopReason,
            String actingUserId,
            ManagedExecutionScope scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (status != null) {
            body.put("status", status);
        }
        if (stopReason != null) {
            body.put("stopReason", stopReason);
        }
        if (scope != null) {
            if (scope.agentTaskId() != null && !scope.agentTaskId().isBlank()) {
                body.put("agentTaskId", scope.agentTaskId());
            }
            body.put("attemptId", scope.attemptId());
            body.put("dispatchGeneration", scope.dispatchGeneration());
            body.put("turnId", scope.turnId());
        }
        try {
            webClient
                    .patch()
                    .uri("/api/internal/sessions/{id}/runtime", sessionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(internalHeaders(actingUserId))
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException ex) {
            throw mapWebClientError(ex, "Session not found: " + sessionId);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("patchSessionRuntime failed for {}: {}", sessionId, ex.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Control plane runtime patch failed for session "
                            + sessionId
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
    }

    /** Loads an environment template by id from the control plane. */
    public EnvironmentDto getEnvironment(String environmentId) {
        return getEnvironment(environmentId, null);
    }

    /** Loads an environment template, optionally attributing the call to {@code actingUserId}. */
    public EnvironmentDto getEnvironment(String environmentId, String actingUserId) {
        try {
            String body =
                    webClient
                            .get()
                            .uri("/api/internal/environments/{id}", environmentId)
                            .headers(internalHeaders(actingUserId))
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
            EnvironmentDto dto =
                    body == null || body.isBlank()
                            ? null
                            : objectMapper.readValue(body, EnvironmentDto.class);
            if (dto == null) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Environment not found: " + environmentId);
            }
            return dto;
        } catch (WebClientResponseException ex) {
            throw mapWebClientError(ex, "Environment not found: " + environmentId);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("getEnvironment failed for {}: {}", environmentId, ex.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Control plane environment lookup failed for "
                            + environmentId
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
    }

    /**
     * Verifies an Environment Worker API key via the control plane. Returns false on any CP error
     * so the auth filter can fall through without 5xx.
     */
    public boolean verifyEnvironmentKey(String environmentId, String plaintextKey) {
        try {
            Map<String, Object> body = Map.of("key", plaintextKey);
            Map<?, ?> resp =
                    webClient
                            .post()
                            .uri("/api/internal/environments/{id}/verify-key", environmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .headers(internalHeaders(null))
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .block();
            return resp != null && Boolean.TRUE.equals(resp.get("ok"));
        } catch (Exception ex) {
            log.debug("verifyEnvironmentKey failed for {}: {}", environmentId, ex.getMessage());
            return false;
        }
    }

    /** Memory calls remain session-scoped; CP rechecks mount ownership and access on each request. */
    public io.agentscope.builder.web.managed.MemoryDocumentStore memoryDocuments(
            String sessionId, String storeId) {
        String base = "/api/internal/sessions/{session}/memory-stores/{store}/memories";
        return new io.agentscope.builder.web.managed.MemoryDocumentStore() {
            public List<io.agentscope.builder.web.managed.MemoryDto> list() {
                try {
                    return webClient
                            .get()
                            .uri(base, sessionId, storeId)
                            .headers(internalHeaders(null))
                            .retrieve()
                            .bodyToFlux(io.agentscope.builder.web.managed.MemoryDto.class)
                            .collectList()
                            .block();
                } catch (WebClientResponseException e) {
                    throw mapWebClientError(e, "Memory mount unavailable");
                }
            }

            public io.agentscope.builder.web.managed.MemoryDto get(String path) {
                try {
                    return webClient
                            .get()
                            .uri(base + "/{path}", sessionId, storeId, path)
                            .headers(internalHeaders(null))
                            .retrieve()
                            .bodyToMono(io.agentscope.builder.web.managed.MemoryDto.class)
                            .block();
                } catch (WebClientResponseException e) {
                    throw mapWebClientError(e, "Memory not found");
                }
            }

            public void put(String path, String content, Integer expectedVersion) {
                try {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("content", content);
                    body.put("expectedVersion", expectedVersion);
                    webClient
                            .put()
                            .uri(base + "/{path}", sessionId, storeId, path)
                            .headers(internalHeaders(null))
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .retrieve()
                            .toBodilessEntity()
                            .block();
                } catch (WebClientResponseException e) {
                    throw mapWebClientError(e, "Memory not found");
                }
            }

            public void delete(String path) {
                try {
                    webClient
                            .delete()
                            .uri(base + "/{path}", sessionId, storeId, path)
                            .headers(internalHeaders(null))
                            .retrieve()
                            .toBodilessEntity()
                            .block();
                } catch (WebClientResponseException e) {
                    throw mapWebClientError(e, "Memory not found");
                }
            }
        };
    }

    private Consumer<HttpHeaders> internalHeaders(String actingUserId) {
        return headers -> {
            headers.set(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, internalToken);
            if (actingUserId != null && !actingUserId.isBlank()) {
                headers.set(InternalTokenAuthFilter.INTERNAL_USER_HEADER, actingUserId);
            }
        };
    }

    private static ResponseStatusException mapWebClientError(
            WebClientResponseException ex, String notFoundMessage) {
        if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, notFoundMessage);
        }
        String body = ex.getResponseBodyAsString();
        return new ResponseStatusException(
                HttpStatus.valueOf(ex.getStatusCode().value()),
                body != null && !body.isBlank() ? body : ex.getMessage());
    }
}
