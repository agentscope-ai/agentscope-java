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
package io.agentscope.builder.portal.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.runtime.managed.ManagedSessionProtocol;
import io.agentscope.builder.runtime.protocol.RuntimeBootstrapDigest;
import io.agentscope.builder.runtime.protocol.RuntimeProtocol;
import io.agentscope.builder.runtime.protocol.RuntimeRequestContext;
import io.agentscope.builder.runtime.protocol.RuntimeSessionAdmission;
import io.agentscope.builder.runtime.protocol.RuntimeSessionBootstrap;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** Control-plane resource admission followed by Session Portal registration and runtime commit confirmation. */
@Service
public class PortalSessionCreation {
    private final PortalSessionRepository repository;
    private final PortalSessionProvisioning provisioning;
    private final PortalSessionAccess access;
    private final ObjectMapper mapper;
    private final WebClient control;
    private final String internalToken;

    public PortalSessionCreation(
            PortalSessionRepository repository,
            PortalSessionProvisioning provisioning,
            PortalSessionAccess access,
            ObjectMapper mapper,
            @Value("${builder.control-plane-url:http://localhost:8081}") String controlUrl,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String internalToken) {
        this.repository = repository;
        this.provisioning = provisioning;
        this.access = access;
        this.mapper = mapper;
        this.control =
                WebClient.builder()
                        .baseUrl(controlUrl)
                        .codecs(config -> config.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                        .build();
        this.internalToken = internalToken;
    }

    public Map<String, Object> create(Map<String, Object> input, HttpHeaders headers) {
        return create(input, headers, false);
    }

    public Map<String, Object> createLegacy(Map<String, Object> input, HttpHeaders headers) {
        return create(input, headers, true);
    }

    private Map<String, Object> create(
            Map<String, Object> input, HttpHeaders headers, boolean legacy) {
        // Even idempotent retries are checked against the real caller's current resource grants.
        // Use a generated key for this entire server attempt when a caller did not supply one.
        String key = headers.getFirst("Idempotency-Key");
        if (key == null || key.isBlank()) key = UUID.randomUUID().toString();
        if (key.length() > 256)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Idempotency-Key exceeds 256 characters");
        try {
            String admissionKey = key;
            var selected =
                    control.post()
                            .uri("/api/internal/runtime-admissions/sessions")
                            .headers(
                                    out -> {
                                        publicHeaders(headers, out);
                                        out.set("Idempotency-Key", admissionKey);
                                        out.set(
                                                InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER,
                                                internalToken);
                                    })
                            .bodyValue(input)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .block(Duration.ofSeconds(10));
            if (selected == null)
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Empty configuration admission");
            String id = String.valueOf(selected.get("id"));
            String runtimeKind = String.valueOf(selected.get("runtimeKind"));
            if (legacy && "managed".equals(runtimeKind))
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Managed Session creation requires /v1/sessions");
            if (!Set.of("managed", "hosted-runtime", "workflow", "team").contains(runtimeKind))
                throw new ResponseStatusException(
                        HttpStatus.NOT_IMPLEMENTED,
                        "Runtime backend is not yet connected to Session Portal");
            if (repository.existsById(id)) {
                access.authorize(id, headers, "invoke");
                provisioning.deliver(id);
                return provisioning.readyView(id);
            }
            String tenant = String.valueOf(selected.get("tenant"));
            var projection =
                    control.get()
                            .uri(
                                    builder ->
                                            builder.path(
                                                            "/api/internal/runtime-sessions/{id}/projection")
                                                    .queryParam("tenant", tenant)
                                                    .queryParam("configuration", true)
                                                    .build(id))
                            .header(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, internalToken)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .block(Duration.ofSeconds(10));
            if (projection == null || !runtimeKind.equals(projection.get("runtimeKind")))
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Invalid configuration projection");
            var resolved = map(projection.get("resolved"));
            if ("managed".equals(runtimeKind)) ManagedSessionProtocol.validateAgent(resolved);
            var policy = map(projection.get("policy"));
            if (!(policy.get("scope_id") instanceof String scope) || !scope.matches("[0-9a-f]{64}"))
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Configuration admission has no shared policy scope");
            var actor = map(selected.get("admissionActor"));
            if (actor.isEmpty() || Boolean.TRUE.equals(actor.get("internal")))
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Configuration admission did not retain the caller");
            var app = map(policy.get("application"));
            var context =
                    new RuntimeRequestContext(
                            RuntimeProtocol.VERSION,
                            String.valueOf(policy.get("tenant")),
                            String.valueOf(policy.get("namespace")),
                            app.get("id") == null ? null : String.valueOf(app.get("id")),
                            id,
                            null,
                            "bootstrap:" + id,
                            UUID.randomUUID().toString(),
                            actor,
                            "initial",
                            ((Number) policy.get("valid_until")).longValue());
            var snapshot =
                    new RuntimeSessionBootstrap(
                            context,
                            runtimeKind,
                            RuntimeBootstrapDigest.configurationRevision(mapper, resolved),
                            resolved);
            var publicView = map(projection.get("view"));
            var agent = map(resolved.get("agentSnapshot"));
            var reference = new LinkedHashMap<String, Object>();
            var sessionSnapshot = map(resolved.get("session"));
            for (String field : List.of("agentId", "agentVersion", "environmentId", "vaultIds"))
                if (!publicView.containsKey(field) && sessionSnapshot.containsKey(field))
                    publicView.put(field, sessionSnapshot.get(field));
            reference.put("id", publicView.getOrDefault("agentId", ""));
            reference.put("version", publicView.getOrDefault("agentVersion", 1));
            reference.put("name", agent.getOrDefault("name", ""));
            Object model = agent.get("model");
            reference.put(
                    "model",
                    model instanceof String name ? name : map(model).getOrDefault("id", ""));
            // Session API exposes identifiers, not prompts or excluded resource definitions.
            publicView.put("sessionAgentReference", reference);
            provisioning.provision(new RuntimeSessionAdmission(snapshot, policy, publicView));
            return provisioning.readyView(id);
        } catch (WebClientResponseException error) {
            throw new ResponseStatusException(
                    error.getStatusCode(), "Session configuration admission failed");
        }
    }

    private static void publicHeaders(HttpHeaders source, HttpHeaders target) {
        for (String name :
                new String[] {
                    "Authorization", "X-API-Key", "X-AgentScope-Tenant", "X-AgentScope-Namespace"
                }) {
            String value = source.getFirst(name);
            if (value != null) target.set(name, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();
    }
}
