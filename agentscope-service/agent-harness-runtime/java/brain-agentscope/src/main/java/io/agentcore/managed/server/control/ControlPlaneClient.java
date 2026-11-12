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
package io.agentcore.managed.server.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.web.auth.InternalTokenAuthFilter;
import io.agentcore.managed.server.web.managed.MemoryDocumentStore;
import io.agentcore.managed.server.web.managed.MemoryDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** Immutable native Session configuration and control-plane resource calls; no execution routing. */
@Service
public class ControlPlaneClient {
    private final WebClient webClient;
    private final String controlPlaneUrl;
    private final String internalToken;
    private ManagedCatalog managedCatalog;
    private SessionServiceClient sessionService;
    private ObjectMapper mapper;

    @Autowired
    public ControlPlaneClient(
            SessionServiceClient client,
            ObjectMapper mapper,
            @Value(
                            "${managed-agents.session-service.url:http://localhost:8084/internal/session-service/v0}")
                    String url,
            @Value("${managed-agents.internal-token:${MANAGED_AGENTS_INTERNAL_TOKEN:}}")
                    String token) {
        this(url, token);
        this.sessionService = client;
        this.mapper = mapper;
    }

    public ControlPlaneClient(
            @Value("${managed-agents.control-plane-url:http://localhost:8081}") String url,
            @Value("${managed-agents.internal-token:${MANAGED_AGENTS_INTERNAL_TOKEN:}}")
                    String token) {
        controlPlaneUrl = url.replaceAll("/+$", "");
        internalToken = token;
        webClient = WebClient.builder().baseUrl(controlPlaneUrl).build();
    }

    @Autowired
    public void setManagedCatalog(ManagedCatalog catalog) {
        managedCatalog = catalog;
    }

    public String collaborationMcpUrl() {
        if (sessionService != null)
            return sessionService
                    .post("prepared/resources", Map.of("operation", "collaboration-url"))
                    .required("url")
                    .asText();
        return controlPlaneUrl + "/mcp/collaboration";
    }

    public SessionResolveResult resolveSession(String id) {
        if (managedCatalog == null) throw new IllegalStateException("Managed catalog is required");
        return managedCatalog.resolve(id);
    }

    /** The sealed Session binds mounts; CP rechecks live ownership and Environment access. */
    public MemoryDocumentStore memoryDocuments(String sessionId, String storeId) {
        if (sessionService != null) return remoteMemory(sessionId, storeId);
        String base = "/api/internal/memory-stores/{store}/memories";
        return new MemoryDocumentStore() {
            public List<MemoryDto> list() {
                try {
                    return webClient
                            .get()
                            .uri(base, storeId)
                            .headers(memoryHeaders(sessionId, storeId))
                            .retrieve()
                            .bodyToFlux(MemoryDto.class)
                            .collectList()
                            .block();
                } catch (WebClientResponseException e) {
                    throw mapWebClientError(e, "Memory mount unavailable");
                }
            }

            public MemoryDto get(String path) {
                try {
                    return webClient
                            .get()
                            .uri(base + "/{path}", storeId, path)
                            .headers(memoryHeaders(sessionId, storeId))
                            .retrieve()
                            .bodyToMono(MemoryDto.class)
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
                            .uri(base + "/{path}", storeId, path)
                            .headers(memoryHeaders(sessionId, storeId))
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
                            .uri(base + "/{path}", storeId, path)
                            .headers(memoryHeaders(sessionId, storeId))
                            .retrieve()
                            .toBodilessEntity()
                            .block();
                } catch (WebClientResponseException e) {
                    throw mapWebClientError(e, "Memory not found");
                }
            }
        };
    }

    private MemoryDocumentStore remoteMemory(String sessionId, String storeId) {
        var scope = managedCatalog.scope(sessionId);
        return new MemoryDocumentStore() {
            private JsonNode call(String operation, String path, String content, Integer version) {
                var request =
                        mapper.createObjectNode()
                                .put("operation", operation)
                                .put("storeId", storeId);
                request.set("scope", mapper.valueToTree(scope));
                if (path != null) request.put("path", path);
                if (content != null) request.put("content", content);
                if (version != null) request.put("expectedVersion", version);
                return sessionService.post("prepared/resources", request);
            }

            public List<MemoryDto> list() {
                return mapper.convertValue(
                        call("memory-list", null, null, null),
                        mapper.getTypeFactory()
                                .constructCollectionType(List.class, MemoryDto.class));
            }

            public MemoryDto get(String path) {
                return mapper.convertValue(call("memory-get", path, null, null), MemoryDto.class);
            }

            public void put(String path, String content, Integer version) {
                call("memory-put", path, content, version);
            }

            public void delete(String path) {
                call("memory-delete", path, null, null);
            }
        };
    }

    private Consumer<HttpHeaders> memoryHeaders(String sessionId, String storeId) {
        var resolved = resolveSession(sessionId);
        var session = resolved.session();
        if (session == null
                || !sessionId.equals(session.id())
                || session.ownerId() == null
                || session.ownerId().isBlank()
                || session.memoryStoreIds() == null
                || !session.memoryStoreIds().contains(storeId)
                || resolved.memoryMounts() == null
                || resolved.memoryMounts().stream()
                        .noneMatch(mount -> storeId.equals(mount.get("storeId"))))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Memory is not bound to Session");
        return headers -> {
            headers.set(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, internalToken);
            headers.set(InternalTokenAuthFilter.INTERNAL_USER_HEADER, session.ownerId());
            if (session.environmentId() != null && !session.environmentId().isBlank())
                headers.set("X-Managed-Agents-Internal-Environment", session.environmentId());
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
