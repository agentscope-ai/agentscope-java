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
package io.agentscope.builder.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.builder.web.managed.selfhosted.LocalHandsToolExecutor;
import io.agentscope.builder.web.managed.selfhosted.SessionInputStager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standalone Environment Worker for production self-hosted Hands fleets (Claude-style outbound).
 *
 * <p>Usage:
 *
 * <pre>
 *   java -cp service-scheduler.jar io.agentscope.builder.worker.HandsWorkerMain \
 *     --base-url http://builder:8080 \
 *     --environment-id env_xxx \
 *     --environment-key ebk_... \
 *     --hands-root /var/lib/agentscope/hands
 * </pre>
 *
 * <p>The scheduler plane is the only Hands execution plane; the control and data planes never
 * execute self-hosted tools locally.
 */
public final class HandsWorkerMain {

    private static final String ENV_KEY_HEADER = "X-Builder-Environment-Key";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;
    private static final long PENDING_POLL_MS = 1_000L;

    private HandsWorkerMain() {}

    public static void main(String[] args) throws Exception {
        String baseUrl = opt(args, "--base-url", "http://localhost:8080");
        String environmentId = require(args, "--environment-id");
        String environmentKey = opt(args, "--environment-key", null);
        if (environmentKey == null || environmentKey.isBlank()) {
            environmentKey = opt(args, "--token", null);
        }
        if (environmentKey == null || environmentKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required arg --environment-key (or legacy --token)");
        }
        String handsRoot =
                opt(
                        args,
                        "--hands-root",
                        System.getProperty("java.io.tmpdir") + "/agentscope-hands");
        String workerId = opt(args, "--worker-id", "worker-" + UUID.randomUUID());

        Path root = Path.of(handsRoot);
        Files.createDirectories(root);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        AtomicBoolean running = new AtomicBoolean(true);
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    running.set(false);
                                    System.out.println("Hands worker shutting down...");
                                }));

        System.out.println("Hands worker starting: id=" + workerId + " env=" + environmentId);

        while (running.get()) {
            try {
                Optional<WorkItem> item =
                        poll(client, baseUrl, environmentId, environmentKey, workerId);
                if (item.isEmpty()) {
                    continue;
                }
                WorkItem work = item.get();
                Path workDir = root.resolve(sanitize(work.sessionId()));
                Files.createDirectories(workDir);
                ack(
                        client,
                        baseUrl,
                        environmentId,
                        environmentKey,
                        work.workId(),
                        workerId,
                        workDir);
                downloadSkills(
                        client, baseUrl, environmentId, environmentKey, work.sessionId(), workDir);
                SessionInputStager.stage(work.metadata(), workDir);

                LocalHandsToolExecutor executor = new LocalHandsToolExecutor(workDir);
                long lastHeartbeat = 0L;
                int idleRounds = 0;
                while (running.get() && idleRounds < 120) {
                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                        heartbeat(client, baseUrl, environmentId, environmentKey, work.workId());
                        lastHeartbeat = now;
                    }
                    JsonNode pending =
                            getPendingTools(
                                    client,
                                    baseUrl,
                                    environmentId,
                                    environmentKey,
                                    work.sessionId());
                    if (pending != null && pending.isArray() && !pending.isEmpty()) {
                        idleRounds = 0;
                        ArrayNode results = MAPPER.createArrayNode();
                        for (JsonNode tool : pending) {
                            String id = text(tool, "id");
                            String name = text(tool, "name");
                            Map<String, Object> input = toMap(tool.get("input"));
                            LocalHandsToolExecutor.ToolExecResult exec =
                                    executor.execute(name, input);
                            ObjectNode result = MAPPER.createObjectNode();
                            result.put("tool_use_id", id);
                            result.put("name", name);
                            result.put("content", exec.content());
                            result.put("is_error", exec.error());
                            results.add(result);
                            System.out.println(
                                    "Executed "
                                            + name
                                            + " for session="
                                            + work.sessionId()
                                            + " error="
                                            + exec.error());
                        }
                        postToolResults(
                                client,
                                baseUrl,
                                environmentId,
                                environmentKey,
                                work.sessionId(),
                                results);
                    } else {
                        idleRounds++;
                        Thread.sleep(PENDING_POLL_MS);
                    }
                }
                stop(client, baseUrl, environmentId, environmentKey, work.workId());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                System.err.println("worker cycle failed: " + ex.getMessage());
                Thread.sleep(2000);
            }
        }
    }

    private static Optional<WorkItem> poll(
            HttpClient client, String baseUrl, String envId, String environmentKey, String workerId)
            throws Exception {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        baseUrl
                                                + "/api/environments/"
                                                + envId
                                                + "/work/poll?workerId="
                                                + workerId
                                                + "&timeoutMs=25000"))
                        .timeout(Duration.ofSeconds(40))
                        .header(ENV_KEY_HEADER, environmentKey)
                        .GET()
                        .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 204 || resp.body() == null || resp.body().isBlank()) {
            return Optional.empty();
        }
        if (resp.statusCode() >= 300) {
            System.err.println("poll failed: " + resp.statusCode() + " " + resp.body());
            Thread.sleep(1000);
            return Optional.empty();
        }
        JsonNode body = MAPPER.readTree(resp.body());
        String workId = firstText(body, "workId", "leaseId", "id");
        String sessionId = text(body, "sessionId");
        if (workId == null || sessionId == null) {
            return Optional.empty();
        }
        Map<String, Object> metadata = toMap(body.get("metadata"));
        return Optional.of(new WorkItem(workId, sessionId, metadata));
    }

    private static void ack(
            HttpClient client,
            String baseUrl,
            String envId,
            String environmentKey,
            String workId,
            String workerId,
            Path workDir)
            throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("workerId", workerId);
        body.put("workDir", workDir.toString());
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        baseUrl
                                                + "/api/environments/"
                                                + envId
                                                + "/work/"
                                                + workId
                                                + "/ack"))
                        .timeout(Duration.ofSeconds(30))
                        .header(ENV_KEY_HEADER, environmentKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                        .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException("ack failed: " + resp.statusCode() + " " + resp.body());
        }
    }

    private static void heartbeat(
            HttpClient client, String baseUrl, String envId, String environmentKey, String workId)
            throws Exception {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        baseUrl
                                                + "/api/environments/"
                                                + envId
                                                + "/work/"
                                                + workId
                                                + "/heartbeat"))
                        .timeout(Duration.ofSeconds(15))
                        .header(ENV_KEY_HEADER, environmentKey)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
        HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() >= 300) {
            System.err.println("heartbeat failed: " + resp.statusCode());
        }
    }

    private static void stop(
            HttpClient client, String baseUrl, String envId, String environmentKey, String workId)
            throws Exception {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        baseUrl
                                                + "/api/environments/"
                                                + envId
                                                + "/work/"
                                                + workId
                                                + "/stop"))
                        .timeout(Duration.ofSeconds(15))
                        .header(ENV_KEY_HEADER, environmentKey)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
        client.send(req, HttpResponse.BodyHandlers.discarding());
    }

    private static JsonNode getPendingTools(
            HttpClient client,
            String baseUrl,
            String envId,
            String environmentKey,
            String sessionId)
            throws Exception {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        baseUrl
                                                + "/api/environments/"
                                                + envId
                                                + "/sessions/"
                                                + sessionId
                                                + "/pending-tools"))
                        .timeout(Duration.ofSeconds(20))
                        .header(ENV_KEY_HEADER, environmentKey)
                        .GET()
                        .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            System.err.println("pending-tools failed: " + resp.statusCode() + " " + resp.body());
            return MAPPER.createArrayNode();
        }
        if (resp.body() == null || resp.body().isBlank()) {
            return MAPPER.createArrayNode();
        }
        return MAPPER.readTree(resp.body());
    }

    private static void postToolResults(
            HttpClient client,
            String baseUrl,
            String envId,
            String environmentKey,
            String sessionId,
            ArrayNode results)
            throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.set("results", results);
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        baseUrl
                                                + "/api/environments/"
                                                + envId
                                                + "/sessions/"
                                                + sessionId
                                                + "/tool-results"))
                        .timeout(Duration.ofSeconds(60))
                        .header(ENV_KEY_HEADER, environmentKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                        .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException(
                    "tool-results failed: " + resp.statusCode() + " " + resp.body());
        }
    }

    private static void downloadSkills(
            HttpClient client,
            String baseUrl,
            String envId,
            String environmentKey,
            String sessionId,
            Path workDir)
            throws Exception {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        baseUrl
                                                + "/api/environments/"
                                                + envId
                                                + "/sessions/"
                                                + sessionId
                                                + "/skills"))
                        .timeout(Duration.ofSeconds(60))
                        .header(ENV_KEY_HEADER, environmentKey)
                        .GET()
                        .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            System.err.println("skills download failed: " + resp.statusCode() + " " + resp.body());
            return;
        }
        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode skills = root.get("skills");
        if (skills == null || !skills.isArray()) {
            return;
        }
        Path skillsRoot = workDir.resolve("skills");
        Files.createDirectories(skillsRoot);
        for (JsonNode skill : skills) {
            String name = text(skill, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            Path skillDir = skillsRoot.resolve(sanitize(name));
            Files.createDirectories(skillDir);
            String skillContent = text(skill, "skillContent");
            if (skillContent != null) {
                Files.writeString(
                        skillDir.resolve("SKILL.md"), skillContent, StandardCharsets.UTF_8);
            }
            JsonNode resources = skill.get("resources");
            if (resources != null && resources.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = resources.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    Path file = skillDir.resolve(e.getKey()).normalize();
                    if (!file.startsWith(skillDir)) {
                        continue;
                    }
                    Files.createDirectories(file.getParent());
                    JsonNode meta = e.getValue();
                    byte[] bytes;
                    if (meta != null && "base64".equals(text(meta, "encoding"))) {
                        bytes = Base64.getDecoder().decode(text(meta, "content"));
                    } else if (meta != null && meta.has("contentBase64")) {
                        bytes = Base64.getDecoder().decode(text(meta, "contentBase64"));
                    } else {
                        bytes =
                                text(meta, "content") != null
                                        ? text(meta, "content").getBytes(StandardCharsets.UTF_8)
                                        : new byte[0];
                    }
                    Files.write(file, bytes);
                    if (meta != null && meta.path("executable").asBoolean(false)) {
                        try {
                            Set<PosixFilePermission> perms =
                                    new HashSet<>(Files.getPosixFilePermissions(file));
                            perms.add(PosixFilePermission.OWNER_EXECUTE);
                            perms.add(PosixFilePermission.GROUP_EXECUTE);
                            perms.add(PosixFilePermission.OTHERS_EXECUTE);
                            Files.setPosixFilePermissions(file, perms);
                        } catch (UnsupportedOperationException ignored) {
                            // non-posix FS
                        }
                    }
                }
            }
        }
        System.out.println("Staged skills into " + skillsRoot);
    }

    /**
     * Stages session metadata file references into the workspace. Delegates to {@link
     * SessionInputStager} (kept as a thin wrapper for tests / callers).
     */
    static void stageMetadataFiles(Map<String, Object> metadata, Path workDir) throws Exception {
        SessionInputStager.stage(metadata, workDir);
    }

    private static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return new HashMap<>();
        }
        return MAPPER.convertValue(node, Map.class);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = text(node, f);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String require(String[] args, String name) {
        String v = opt(args, name, null);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing required arg " + name);
        }
        return v;
    }

    private static String opt(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    private record WorkItem(String workId, String sessionId, Map<String, Object> metadata) {}
}
