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
package io.agentscope.harness.agent.sandbox.impl.e2b;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** HTTP client for {@code https://api.e2b.app} sandbox lifecycle. */
final class E2bPlatformHttp {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final E2bSandboxClientOptions opt;

    E2bPlatformHttp(E2bSandboxClientOptions opt) {
        this.opt = Objects.requireNonNull(opt, "opt");
        if (opt.getHttpClient() != null) {
            this.http = opt.getHttpClient();
        } else {
            this.http =
                    new OkHttpClient.Builder()
                            .connectTimeout(opt.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                            .readTimeout(opt.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                            .build();
        }
    }

    JsonNode createSandbox(String templateId, int timeoutSeconds) throws IOException {
        ObjectNode body = json.createObjectNode();
        body.put("templateID", templateId);
        body.put("timeout", timeoutSeconds);
        String url = trimSlash(opt.getApiBaseUrl()) + "/sandboxes";
        return E2bRetry.withRetries(
                opt.getMaxRetries(), () -> postJson(url, body, /* apiKey */ true));
    }

    JsonNode connectSandbox(String sandboxId, int timeoutSeconds) throws IOException {
        ObjectNode body = json.createObjectNode();
        body.put("timeout", timeoutSeconds);
        String url = trimSlash(opt.getApiBaseUrl()) + "/sandboxes/" + sandboxId + "/connect";
        return E2bRetry.withRetries(opt.getMaxRetries(), () -> postJson(url, body, true));
    }

    JsonNode createSandboxSnapshot(String sandboxId) throws IOException {
        ObjectNode body = json.createObjectNode();
        String url = trimSlash(opt.getApiBaseUrl()) + "/sandboxes/" + sandboxId + "/snapshots";
        return E2bRetry.withRetries(opt.getMaxRetries(), () -> postJson(url, body, true));
    }

    void killSandbox(String sandboxId) throws IOException {
        String url = trimSlash(opt.getApiBaseUrl()) + "/sandboxes/" + sandboxId;
        Request req =
                new Request.Builder()
                        .url(url)
                        .addHeader("X-API-Key", requireApiKey())
                        .delete()
                        .build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful() && res.code() != 404) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "E2B delete failed: HTTP " + res.code());
            }
        }
    }

    void applySandboxFields(E2bSandboxState state, JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.hasNonNull("sandboxID")) {
            state.setSandboxId(node.get("sandboxID").asText());
        }
        if (node.hasNonNull("domain")) {
            state.setSandboxDomain(node.get("domain").asText());
        }
        if (node.hasNonNull("envdAccessToken")) {
            state.setEnvdAccessToken(node.get("envdAccessToken").asText());
        }
        if (node.hasNonNull("envdVersion")) {
            state.setEnvdVersion(node.get("envdVersion").asText());
        }
    }

    private JsonNode postJson(String url, ObjectNode body, boolean apiKey) throws IOException {
        Request.Builder rb =
                new Request.Builder().url(url).post(RequestBody.create(body.toString(), JSON));
        if (apiKey) {
            rb.addHeader("X-API-Key", requireApiKey());
        }
        try (Response res = http.newCall(rb.build()).execute()) {
            String text = res.body() != null ? res.body().string() : "";
            if (!res.isSuccessful()) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "E2B HTTP " + res.code() + ": " + text);
            }
            if (text.isBlank()) {
                return json.createObjectNode();
            }
            return json.readTree(text);
        }
    }

    private String requireApiKey() {
        if (opt.getApiKey() == null || opt.getApiKey().isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "E2B API key is required (E2bSandboxClientOptions#setApiKey)");
        }
        return opt.getApiKey();
    }

    private static String trimSlash(String u) {
        if (u == null || u.isBlank()) {
            return "https://api.e2b.app";
        }
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
