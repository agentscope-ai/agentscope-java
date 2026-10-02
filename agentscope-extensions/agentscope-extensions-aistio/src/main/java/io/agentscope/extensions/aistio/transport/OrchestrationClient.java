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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.transport;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Operator client for orchestration definitions, runs, attempts, and runtime policies. */
public final class OrchestrationClient {

    private final ControlPlaneHttpClient http;
    private final String tenant;
    private final String namespace;

    public OrchestrationClient(ControlPlaneHttpClient http, String tenant, String namespace) {
        this.http = http;
        this.tenant = tenant;
        this.namespace = namespace;
    }

    public JsonNode definitions() {
        return get(scope("/api/v1/orchestration-definitions"));
    }

    public JsonNode definition(String id) {
        return get("/api/v1/orchestration-definitions/" + path(id));
    }

    public JsonNode createDefinition(Object body) {
        return send("POST", "/api/v1/orchestration-definitions", body);
    }

    public JsonNode updateDefinition(String id, Object body) {
        return send("PATCH", "/api/v1/orchestration-definitions/" + path(id), body);
    }

    public JsonNode validateDefinition(String id, Object spec) {
        return send(
                "POST",
                "/api/v1/orchestration-definitions/" + path(id) + "/validate",
                spec == null ? Map.of() : Map.of("spec", spec));
    }

    public JsonNode publishDefinition(String id) {
        return send("POST", "/api/v1/orchestration-definitions/" + path(id) + "/publish", Map.of());
    }

    public JsonNode revisions(String id) {
        return get("/api/v1/orchestration-definitions/" + path(id) + "/revisions");
    }

    public JsonNode start(String id, Object request) {
        return send("POST", "/api/v1/orchestration-definitions/" + path(id) + "/runs", request);
    }

    public JsonNode runs() {
        return get(scope("/api/v1/orchestration-runs"));
    }

    public JsonNode run(String id) {
        return get("/api/v1/orchestration-runs/" + path(id));
    }

    public JsonNode graph(String id) {
        return get("/api/v1/orchestration-runs/" + path(id) + "/graph");
    }

    public JsonNode events(String id) {
        return get("/api/v1/orchestration-runs/" + path(id) + "/events");
    }

    public JsonNode control(String id, String action) {
        if (!action.equals("pause") && !action.equals("resume") && !action.equals("cancel"))
            throw new IllegalArgumentException("action must be pause, resume, or cancel");
        return send("POST", "/api/v1/orchestration-runs/" + path(id) + "/" + action, Map.of());
    }

    public JsonNode rerun(String id, String idempotencyKey, Object input) {
        Object body =
                input == null
                        ? Map.of("idempotencyKey", idempotencyKey)
                        : Map.of("idempotencyKey", idempotencyKey, "input", input);
        return send("POST", "/api/v1/orchestration-runs/" + path(id) + "/rerun", body);
    }

    public JsonNode signal(String id, String name, String idempotencyKey, Object payload) {
        return send(
                "POST",
                "/api/v1/orchestration-runs/" + path(id) + "/signals/" + path(name),
                Map.of(
                        "idempotencyKey",
                        idempotencyKey,
                        "payload",
                        payload == null ? Map.of() : payload));
    }

    public JsonNode attempts() {
        return get(scope("/api/v1/execution-attempts"));
    }

    public JsonNode attempt(String id) {
        return get("/api/v1/execution-attempts/" + path(id));
    }

    public JsonNode runtimePolicy(String agentRef) {
        return get(scope("/api/v1/agent-runtime-policies/" + path(agentRef)));
    }

    public JsonNode putRuntimePolicy(String agentRef, Object policy) {
        return send("PUT", "/api/v1/agent-runtime-policies/" + path(agentRef), policy);
    }

    private JsonNode get(String path) {
        return send("GET", path, null);
    }

    private String scope(String path) {
        return path + "?tenant=" + path(tenant) + "&namespace=" + path(namespace);
    }

    private JsonNode send(String method, String path, Object body) {
        try {
            ControlPlaneHttpClient.Response response = http.send(method, path, body);
            if (response.status() < 200 || response.status() >= 300)
                throw new IllegalStateException(
                        "orchestration request failed: HTTP "
                                + response.status()
                                + ": "
                                + response.body());
            return response.body().isBlank()
                    ? ControlPlaneHttpClient.mapper().createObjectNode()
                    : ControlPlaneHttpClient.mapper().readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("orchestration request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("orchestration request interrupted", e);
        }
    }

    private static String path(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
