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
package io.agentscope.extensions.sandbox.e2b;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** HTTP client for {@code https://api.e2b.app} sandbox lifecycle. */
final class E2bPlatformHttp {

    private static final Logger log = LoggerFactory.getLogger(E2bPlatformHttp.class);

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /**
     * AgentScope-native snapshot alias: {@code agentscope-<sandboxId>-<epochMillis>}, where the
     * middle segment is the lower-case alphanumeric E2B sandbox id (base32-style, e.g. {@code
     * i1xfu3xo4sywzgi7rfd9k}). The trailing 13-digit epoch millis timestamp makes ordering
     * deterministic; anything not matching this format is treated as a legacy/foreign snapshot and
     * never pruned.
     */
    private static final Pattern SNAPSHOT_TIMESTAMP_PATTERN =
            Pattern.compile("^agentscope-[a-z0-9-]+-(\\d{13})$");

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

    JsonNode createSandboxSnapshot(String sandboxId, String name) throws IOException {
        ObjectNode body = json.createObjectNode();
        if (name != null && !name.isBlank()) {
            body.put("name", name);
        }
        String url = trimSlash(opt.getApiBaseUrl()) + "/sandboxes/" + sandboxId + "/snapshots";
        return E2bRetry.withRetries(opt.getMaxRetries(), () -> postJson(url, body, true));
    }

    List<String> listSnapshots(String sandboxId) throws IOException {
        HttpUrl parsed = HttpUrl.parse(trimSlash(opt.getApiBaseUrl()) + "/snapshots");
        if (parsed == null) {
            throw new SandboxException.SandboxConfigurationException(
                    "Invalid E2B apiBaseUrl: " + opt.getApiBaseUrl());
        }
        HttpUrl url = parsed.newBuilder().addQueryParameter("sandboxID", sandboxId).build();
        JsonNode node = E2bRetry.withRetries(opt.getMaxRetries(), () -> getJson(url.toString()));
        List<String> ids = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String id = item.path("snapshotID").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    void deleteSnapshot(String snapshotId) throws IOException {
        HttpUrl parsed = HttpUrl.parse(trimSlash(opt.getApiBaseUrl()) + "/templates");
        if (parsed == null) {
            throw new SandboxException.SandboxConfigurationException(
                    "Invalid E2B apiBaseUrl: " + opt.getApiBaseUrl());
        }
        HttpUrl url = parsed.newBuilder().addPathSegment(snapshotId).build();
        Request req =
                new Request.Builder()
                        .url(url)
                        .addHeader("X-API-Key", requireApiKey())
                        .delete()
                        .build();
        E2bRetry.withRetries(
                opt.getMaxRetries(),
                () -> {
                    try (Response res = http.newCall(req).execute()) {
                        if (!res.isSuccessful() && res.code() != 404) {
                            throw new SandboxException.SandboxRuntimeException(
                                    SandboxErrorCode.SNAPSHOT_PERSIST_ERROR,
                                    "E2B snapshot delete failed: HTTP " + res.code());
                        }
                    }
                    return null;
                });
    }

    /**
     * Best-effort pruning: keeps at least {@code retention} AgentScope-native snapshots for the
     * sandbox (including the just-created {@code keepSnapshotId}). {@code retention <= 0} disables
     * pruning. Individual delete failures are logged and skipped so a hiccup never aborts the whole
     * pass.
     *
     * <p>Only snapshots whose alias matches {@code agentscope-<sandboxId>-<epochMillis>}
     * are candidates for pruning, ordered by their embedded timestamp (the most recent {@code
     * retention - 1} are kept, the {@code keepSnapshotId} is always kept). Legacy or foreign
     * snapshots are never touched and must be cleaned up manually. {@code GET /snapshots} pagination
     * is not handled (the default limit is assumed sufficient). Any snapshot at or after the
     * {@code keepSnapshotId}'s embedded timestamp is never pruned, which covers both a missing keep
     * id (e.g. pagination cut-off) and two persists within the same millisecond that reuse the same
     * E2B template, so nothing just-created is wrongly deleted.
     */
    void pruneSnapshots(String sandboxId, String keepSnapshotId, int retention) throws IOException {
        if (retention <= 0) {
            return;
        }
        List<String> snapshots = listSnapshots(sandboxId);
        long keepTs = snapshotTimestampMillis(keepSnapshotId);
        List<StaleSnapshot> stale = new ArrayList<>();
        for (String id : snapshots) {
            if (id == null || id.isBlank() || id.equals(keepSnapshotId)) {
                continue;
            }
            long ts = snapshotTimestampMillis(id);
            if (ts > 0 && (keepTs <= 0 || ts < keepTs)) {
                stale.add(new StaleSnapshot(id, ts));
            }
        }
        stale.sort(Comparator.comparingLong(s -> s.timestamp));
        int toKeep = retention - 1;
        for (int i = 0; i < stale.size() - toKeep; i++) {
            String old = stale.get(i).id;
            try {
                deleteSnapshot(old);
            } catch (Exception e) {
                log.warn("[sandbox-e2b] failed to prune snapshot {}: {}", old, e.getMessage());
            }
        }
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

    private JsonNode getJson(String url) throws IOException {
        Request req =
                new Request.Builder()
                        .url(url)
                        .addHeader("X-API-Key", requireApiKey())
                        .get()
                        .build();
        try (Response res = http.newCall(req).execute()) {
            String text = res.body() != null ? res.body().string() : "";
            if (!res.isSuccessful()) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.SNAPSHOT_PERSIST_ERROR,
                        "E2B HTTP " + res.code() + ": " + text);
            }
            if (text.isBlank()) {
                return json.createArrayNode();
            }
            return json.readTree(text);
        }
    }

    /**
     * Extracts the embedded epoch-millis timestamp from an AgentScope-native snapshot id, or {@code
     * -1} when the id is not in the {@code agentscope-...-<epochMillis>} format. The E2B snapshot id
     * is {@code <namespace>/<alias>:<tag>}; the namespace prefix and tag suffix are stripped before
     * matching.
     */
    private static long snapshotTimestampMillis(String snapshotId) {
        String alias = snapshotId;
        int colon = alias.lastIndexOf(':');
        if (colon >= 0) {
            alias = alias.substring(0, colon);
        }
        int slash = alias.lastIndexOf('/');
        if (slash >= 0) {
            alias = alias.substring(slash + 1);
        }
        Matcher m = SNAPSHOT_TIMESTAMP_PATTERN.matcher(alias);
        if (!m.matches()) {
            return -1;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private record StaleSnapshot(String id, long timestamp) {}

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
