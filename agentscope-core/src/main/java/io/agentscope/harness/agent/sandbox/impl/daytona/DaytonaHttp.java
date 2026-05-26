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
package io.agentscope.harness.agent.sandbox.impl.daytona;

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

/** Minimal HTTP client for the Daytona control plane and toolbox process API. */
final class DaytonaHttp {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final DaytonaSandboxClientOptions opt;

    DaytonaHttp(DaytonaSandboxClientOptions opt) {
        this.opt = Objects.requireNonNull(opt, "opt");
        OkHttpClient base = opt.getHttpClient();
        if (base != null) {
            this.http = base;
        } else {
            this.http =
                    new OkHttpClient.Builder()
                            .connectTimeout(opt.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                            .readTimeout(opt.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                            .build();
        }
    }

    String createSandbox() throws IOException {
        ObjectNode body = json.createObjectNode();
        if (opt.getSnapshotId() != null && !opt.getSnapshotId().isBlank()) {
            body.put("snapshot", opt.getSnapshotId());
        } else {
            body.put("image", opt.getImage());
        }
        if (opt.getCpu() != null) {
            body.put("cpu", opt.getCpu());
        }
        if (opt.getMemory() != null) {
            body.put("memory", opt.getMemory());
        }
        if (opt.getDisk() != null) {
            body.put("disk", opt.getDisk());
        }
        JsonNode root =
                DaytonaRetry.withRetries(
                        opt.getMaxRetries(),
                        () -> postJson(opt.getControlPlaneBaseUrl() + "/sandbox", body));
        JsonNode id = root.get("id");
        if (id == null || id.asText().isBlank()) {
            id = root.get("sandboxId");
        }
        if (id == null || id.asText().isBlank()) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "Daytona create sandbox response missing id: " + root);
        }
        return id.asText();
    }

    void startSandbox(String sandboxId) throws IOException {
        String url = opt.getControlPlaneBaseUrl() + "/sandbox/" + sandboxId + "/start";
        DaytonaRetry.withRetries(opt.getMaxRetries(), () -> postJson(url, json.createObjectNode()));
    }

    JsonNode getSandbox(String sandboxId) throws IOException {
        String url = opt.getControlPlaneBaseUrl() + "/sandbox/" + sandboxId;
        return DaytonaRetry.withRetries(opt.getMaxRetries(), () -> getJson(url));
    }

    void deleteSandbox(String sandboxId) throws IOException {
        String url = opt.getControlPlaneBaseUrl() + "/sandbox/" + sandboxId;
        Request req =
                new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", bearer())
                        .delete()
                        .build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful() && res.code() != 404) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "Daytona delete failed: HTTP " + res.code() + " " + res.message());
            }
        }
    }

    JsonNode execute(String sandboxId, String command, String cwd, int timeoutSeconds)
            throws IOException {
        ObjectNode body = json.createObjectNode();
        body.put("command", command);
        if (cwd != null && !cwd.isBlank()) {
            body.put("cwd", cwd);
        }
        body.put("timeout", Math.max(1, timeoutSeconds));
        String url = opt.getToolboxBaseUrl() + "/toolbox/" + sandboxId + "/process/execute";
        return DaytonaRetry.withRetries(
                opt.getMaxRetries(), () -> postJson(url, body, /* toolbox */ true));
    }

    void waitUntilStarted(String sandboxId, int maxWaitSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(maxWaitSeconds);
        while (System.nanoTime() < deadline) {
            JsonNode s = getSandbox(sandboxId);
            String st = textState(s);
            if (st != null && st.toLowerCase().contains("start")) {
                return;
            }
            Thread.sleep(1500L);
        }
        throw new SandboxException.SandboxRuntimeException(
                SandboxErrorCode.WORKSPACE_START_ERROR,
                "Daytona sandbox did not become ready in time: " + sandboxId);
    }

    private static String textState(JsonNode s) {
        if (s == null) {
            return null;
        }
        JsonNode st = s.get("state");
        if (st != null && st.isTextual()) {
            return st.asText();
        }
        JsonNode status = s.get("status");
        if (status != null && status.isTextual()) {
            return status.asText();
        }
        return null;
    }

    private JsonNode postJson(String url, ObjectNode body) throws IOException {
        return postJson(url, body, false);
    }

    private JsonNode postJson(String url, ObjectNode body, boolean toolbox) throws IOException {
        Request.Builder rb =
                new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(body.toString(), JSON))
                        .addHeader("Authorization", bearer());
        if (toolbox) {
            rb.addHeader("Content-Type", "application/json");
        }
        try (Response res = http.newCall(rb.build()).execute()) {
            String text = res.body() != null ? res.body().string() : "";
            if (!res.isSuccessful()) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "Daytona HTTP " + res.code() + " " + res.message() + ": " + text);
            }
            if (text.isBlank()) {
                return json.createObjectNode();
            }
            return json.readTree(text);
        }
    }

    private JsonNode getJson(String url) throws IOException {
        Request req =
                new Request.Builder().url(url).get().addHeader("Authorization", bearer()).build();
        try (Response res = http.newCall(req).execute()) {
            String text = res.body() != null ? res.body().string() : "";
            if (!res.isSuccessful()) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "Daytona HTTP " + res.code() + " " + res.message() + ": " + text);
            }
            return json.readTree(text);
        }
    }

    private String bearer() {
        String key = opt.getApiKey();
        if (key == null || key.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "Daytona API key is required (set DaytonaSandboxClientOptions#setApiKey)");
        }
        return "Bearer " + key;
    }
}
