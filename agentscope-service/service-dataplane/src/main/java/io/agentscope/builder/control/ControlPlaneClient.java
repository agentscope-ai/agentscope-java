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

import io.agentscope.builder.runtime.ManagedCatalog;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.builder.web.managed.MemoryDocumentStore;
import io.agentscope.builder.web.managed.MemoryDto;
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

    public ControlPlaneClient(
            @Value("${builder.control-plane-url:http://localhost:8081}") String url,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String token) {
        controlPlaneUrl = url.replaceAll("/+$", "");
        internalToken = token;
        webClient = WebClient.builder().baseUrl(controlPlaneUrl).build();
    }

    @Autowired
    public void setManagedCatalog(ManagedCatalog catalog) {
        managedCatalog = catalog;
    }

    public String collaborationMcpUrl() {
        return controlPlaneUrl + "/mcp/collaboration";
    }

    public SessionResolveResult resolveSession(String id) {
        if (managedCatalog == null) throw new IllegalStateException("Managed catalog is required");
        return managedCatalog.resolve(id);
    }

    /** The sealed Session binds mounts; CP rechecks live ownership and Environment access. */
    public MemoryDocumentStore memoryDocuments(String sessionId, String storeId) {
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
                headers.set("X-Builder-Internal-Environment", session.environmentId());
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
