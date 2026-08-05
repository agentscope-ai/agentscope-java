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
     * AgentScope-native snapshot alias: {@code agentscope-<shortId>-<epochMillis>}, where the
     * middle segment is an 8-hex-char short UUID. The trailing 13-digit epoch millis timestamp
     * makes ordering deterministic; anything not matching this format is treated as a legacy/foreign
     * snapshot and never pruned.
     */
    private static final Pattern SNAPSHOT_TIMESTAMP_PATTERN =
            Pattern.compile("^agentscope-[0-9a-f]{8}-(\\d{13})$");

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
     * Best-effort pruning of this session's own snapshots. Keeps at least {@code retention}
     * snapshots (including the just-created {@code keepSnapshotId}): the keep snapshot plus the
     * newest {@code retention - 1} from {@code olderSnapshotIds}, ordered by their embedded
     * timestamp. Older ones are deleted ({@code 404} idempotent, individual failures logged and
     * skipped). {@code retention <= 0} disables pruning and nothing is deleted.
     *
     * <p>The caller passes the snapshots it previously created for this session (from its own
     * persisted state) rather than a {@code GET /snapshots} listing, so pruning never depends on
     * the source sandbox id (which changes when a session is resumed from an earlier snapshot) or
     * a team-wide listing. Snapshots whose alias does not match {@code
     * agentscope-<shortId>-<epochMillis>} (timestamp unparseable) are conservatively kept.
     *
     * @return the snapshot ids to keep afterwards: {@code keepSnapshotId} plus the retained older
     *     ones (older ids with an unparseable timestamp are always kept). Callers should replace
     *     their persisted record with this list.
     */
    List<String> pruneSnapshots(
            String keepSnapshotId, List<String> olderSnapshotIds, int retention) {
        if (retention <= 0) {
            return append(keepSnapshotId, olderSnapshotIds);
        }
        List<StaleSnapshot> stale = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (String id : olderSnapshotIds) {
            if (id == null || id.isBlank() || id.equals(keepSnapshotId)) {
                continue;
            }
            long ts = snapshotTimestampMillis(id);
            if (ts > 0) {
                stale.add(new StaleSnapshot(id, ts));
            } else {
                kept.add(id);
            }
        }
        stale.sort(Comparator.comparingLong(s -> s.timestamp));
        int toKeep = Math.max(0, retention - 1);
        int deleteCount = Math.max(0, stale.size() - toKeep);
        for (int i = deleteCount; i < stale.size(); i++) {
            kept.add(stale.get(i).id);
        }
        for (int i = 0; i < deleteCount; i++) {
            String old = stale.get(i).id;
            try {
                deleteSnapshot(old);
            } catch (Exception e) {
                log.warn("[sandbox-e2b] failed to prune snapshot {}: {}", old, e.getMessage());
                kept.add(old);
            }
        }
        kept.add(keepSnapshotId);
        return kept;
    }

    private static List<String> append(String keepSnapshotId, List<String> olderSnapshotIds) {
        List<String> all = new ArrayList<>(olderSnapshotIds);
        all.add(keepSnapshotId);
        return all;
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
