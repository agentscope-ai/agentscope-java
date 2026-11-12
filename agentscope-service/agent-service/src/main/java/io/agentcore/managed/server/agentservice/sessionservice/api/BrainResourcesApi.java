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
package io.agentcore.managed.server.agentservice.sessionservice.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentcore.managed.harness.runtime.ProtocolSchemas;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.web.auth.InternalTokenAuthFilter;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Fixed resource operations: Session Service resolves and authorizes the admitted mount. */
@RestController
public final class BrainResourcesApi {
    private final ManagedCatalog catalog;
    private final ObjectMapper mapper;
    private final WebClient control;
    private final String base;
    private final String token;

    public BrainResourcesApi(
            ManagedCatalog catalog,
            ObjectMapper mapper,
            @Value("${managed-agents.control-plane-url:http://localhost:8081}") String base,
            @Value("${managed-agents.internal-token:${MANAGED_AGENTS_INTERNAL_TOKEN:}}")
                    String token) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.base = base.replaceAll("/+$", "");
        this.token = token;
        this.control =
                WebClient.builder()
                        .baseUrl(this.base)
                        .codecs(config -> config.defaultCodecs().maxInMemorySize(1024 * 1024))
                        .build();
    }

    @PostMapping("/internal/session-service/v0/prepared/resources")
    public Mono<JsonNode> resource(@RequestBody JsonNode request) {
        return Mono.fromCallable(() -> invoke(request)).subscribeOn(Schedulers.boundedElastic());
    }

    JsonNode invoke(JsonNode request) {
        try {
            new ProtocolSchemas().validate("prepared/ResourceRequest", request);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid resource request");
        }
        String operation = request.required("operation").asText();
        if ("collaboration-url".equals(operation))
            return mapper.valueToTree(Map.of("url", base + "/mcp/collaboration"));
        if (!Set.of("memory-list", "memory-get", "memory-put", "memory-delete").contains(operation))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unsupported resource operation");
        var scope = mapper.convertValue(request.required("scope"), ManagedScope.class);
        String store = request.required("storeId").asText();
        var resolved = catalog.resolve(scope);
        var session = resolved.session();
        if (!scope.sessionId().equals(session.id())
                || session.ownerId() == null
                || session.memoryStoreIds() == null
                || !session.memoryStoreIds().contains(store)
                || resolved.memoryMounts() == null
                || resolved.memoryMounts().stream()
                        .noneMatch(mount -> store.equals(mount.get("storeId"))))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Memory is not bound to Session");
        var method =
                switch (operation) {
                    case "memory-put" -> HttpMethod.PUT;
                    case "memory-delete" -> HttpMethod.DELETE;
                    default -> HttpMethod.GET;
                };
        var spec = control.method(method);
        var call =
                "memory-list".equals(operation)
                        ? spec.uri("/api/internal/memory-stores/{store}/memories", store)
                        : spec.uri(
                                "/api/internal/memory-stores/{store}/memories/{path}",
                                store,
                                request.required("path").asText());
        call.header(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, token)
                .header(InternalTokenAuthFilter.INTERNAL_USER_HEADER, session.ownerId());
        if (session.environmentId() != null)
            call.header("X-Managed-Agents-Internal-Environment", session.environmentId());
        if ("memory-put".equals(operation)) {
            var body = mapper.createObjectNode().set("content", request.required("content"));
            if (request.has("expectedVersion"))
                ((ObjectNode) body).set("expectedVersion", request.get("expectedVersion"));
            call.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        }
        try {
            return call.retrieve()
                    .bodyToMono(JsonNode.class)
                    .defaultIfEmpty(mapper.nullNode())
                    .block(Duration.ofSeconds(30));
        } catch (WebClientResponseException failure) {
            throw new ResponseStatusException(failure.getStatusCode(), "Resource request rejected");
        }
    }
}
