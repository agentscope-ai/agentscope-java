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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final String internalToken;
    private final ObjectMapper objectMapper;

    public ControlPlaneClient(
            @Value("${builder.control-plane-url:http://localhost:8081}") String controlPlaneUrl,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String internalToken,
            ObjectMapper objectMapper) {
        this.webClient = WebClient.builder().baseUrl(controlPlaneUrl).build();
        this.internalToken = internalToken;
        this.objectMapper =
                objectMapper
                        .copy()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
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
        patchSessionRuntime(sessionId, status, stopReason, null);
    }

    /** Patches session runtime status, optionally attributing the call to {@code actingUserId}. */
    public void patchSessionRuntime(
            String sessionId, String status, Map<String, Object> stopReason, String actingUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (status != null) {
            body.put("status", status);
        }
        if (stopReason != null) {
            body.put("stopReason", stopReason);
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
