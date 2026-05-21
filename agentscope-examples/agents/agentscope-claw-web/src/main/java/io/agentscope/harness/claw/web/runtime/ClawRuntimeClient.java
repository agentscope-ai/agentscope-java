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
package io.agentscope.harness.claw.web.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * HTTP client that calls agentscope-claw's read-only admin runtime REST endpoints.
 *
 * <p>Configure the claw base URL in {@code application.yml}:
 *
 * <pre>
 * claw-web:
 *   claw-url: http://localhost:8080
 *   system-token: ${CLAW_ADMIN_TOKEN:}   # used for system-initiated probes only
 * </pre>
 *
 * <h2>Auth forwarding</h2>
 *
 * <p>For requests originating from an admin user, callers should pass the inbound {@code
 * Authorization} header (extracted with {@link #extractBearer(ServerWebExchange)}) to
 * {@link #get(String, String)} so claw's access log attributes the call to the actual admin user,
 * not to a synthetic system identity.
 *
 * <p>The {@code system-token} is reserved for system-initiated probes (e.g. {@code
 * isClawReachable()}) and is used as a fallback only when no forwarded bearer is available.
 *
 * <p>The legacy property {@code claw-web.admin-token} is still read as a fallback for backward
 * compatibility with existing {@code CLAW_ADMIN_TOKEN} deployments, but new deployments should
 * use {@code system-token}.
 */
@Component
public class ClawRuntimeClient {

    private static final Logger log = LoggerFactory.getLogger(ClawRuntimeClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String clawUrl;
    private final String systemToken;

    public ClawRuntimeClient(
            @Value("${claw-web.claw-url:http://localhost:8080}") String clawUrl,
            @Value("${claw-web.system-token:${claw-web.admin-token:}}") String systemToken) {
        this.clawUrl = clawUrl.replaceAll("/$", "");
        this.systemToken = systemToken;
    }

    /**
     * Returns the raw JSON response body for the given claw runtime path. Uses the configured
     * system token for authentication — prefer {@link #get(String, String)} when handling a
     * user-initiated request so the admin's actual identity is forwarded to claw.
     */
    public JsonNode get(String path) {
        return get(path, null);
    }

    /**
     * Returns the raw JSON response body for the given claw runtime path. Forwards {@code
     * bearerToken} as the {@code Authorization} header when non-blank; falls back to the
     * configured system token otherwise.
     */
    public JsonNode get(String path, String bearerToken) {
        try {
            HttpRequest.Builder reqBuilder =
                    HttpRequest.newBuilder()
                            .uri(URI.create(clawUrl + path))
                            .timeout(Duration.ofSeconds(10))
                            .GET();
            String tokenToUse =
                    (bearerToken != null && !bearerToken.isBlank()) ? bearerToken : systemToken;
            if (tokenToUse != null && !tokenToUse.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + tokenToUse);
            }
            HttpResponse<String> resp =
                    http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn(
                        "claw runtime call {} returned {}: {}",
                        path,
                        resp.statusCode(),
                        resp.body());
                return MAPPER.createObjectNode().put("error", "claw returned " + resp.statusCode());
            }
            return MAPPER.readTree(resp.body());
        } catch (IOException | InterruptedException e) {
            log.warn("claw runtime call {} failed: {}", path, e.getMessage());
            return MAPPER.createObjectNode().put("error", e.getMessage());
        }
    }

    /**
     * Extracts the bearer token from the inbound request's {@code Authorization} header. Returns
     * {@code null} when missing or malformed; callers should pass the result straight to
     * {@link #get(String, String)}, which will fall back to the system token.
     */
    public static String extractBearer(ServerWebExchange exchange) {
        if (exchange == null) return null;
        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        return header.substring(7);
    }

    /** Typed convenience: fetch a JSON array and deserialize to a list. */
    public <T> List<T> getList(String path, TypeReference<List<T>> type) {
        try {
            JsonNode node = get(path);
            return MAPPER.convertValue(node, type);
        } catch (Exception e) {
            log.warn("claw runtime list call {} failed: {}", path, e.getMessage());
            return List.of();
        }
    }

    /** Returns the configured claw base URL. */
    public String clawUrl() {
        return clawUrl;
    }

    /**
     * Health probe: returns {@code true} if claw responds to its actuator health endpoint
     * successfully.
     */
    public boolean isClawReachable() {
        try {
            HttpResponse<String> resp =
                    http.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(clawUrl + "/actuator/health"))
                                    .timeout(Duration.ofSeconds(3))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() < 400;
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns the log stream URL for SSE consumption in the admin debug page. */
    public String logStreamUrl() {
        return clawUrl + "/api/admin/runtime/logs";
    }
}
