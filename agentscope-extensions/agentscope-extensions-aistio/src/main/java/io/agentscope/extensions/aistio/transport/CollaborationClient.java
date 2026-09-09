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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Issue/Comment/AgentTask client shared by Java external-agent runtimes. */
public final class CollaborationClient {

    private static final String TASK_TOKEN_HEADER = "X-Agent-Task-Token";
    private final ControlPlaneHttpClient http;

    public CollaborationClient(ControlPlaneHttpClient http) {
        this.http = http;
    }

    public JsonNode issue(String issueId, String token) {
        return send(
                "GET",
                "/api/v1/issues/" + path(issueId),
                null,
                Map.of(TASK_TOKEN_HEADER, token),
                "issue.get");
    }

    public JsonNode comments(String issueId, String token) {
        return send(
                "GET",
                "/api/v1/issues/" + path(issueId) + "/comments",
                null,
                Map.of(TASK_TOKEN_HEADER, token),
                "issue.comment.list");
    }

    public JsonNode addComment(
            String issueId,
            String token,
            String content,
            List<Map<String, String>> mentions,
            String parentId) {
        return send(
                "POST",
                "/api/v1/issues/" + path(issueId) + "/comments",
                Map.of(
                        "content", content,
                        "mentions", mentions == null ? List.of() : mentions,
                        "parentId", parentId == null ? "" : parentId),
                Map.of(TASK_TOKEN_HEADER, token),
                "issue.comment.add");
    }

    public JsonNode team(String teamId, String token) {
        return send(
                "GET",
                "/api/v1/teams/" + path(teamId),
                null,
                Map.of(TASK_TOKEN_HEADER, token),
                "team.get");
    }

    public JsonNode requestApproval(String token, Object request) {
        return send(
                "POST",
                "/api/v1/approvals",
                request,
                Map.of(TASK_TOKEN_HEADER, token),
                "approval.request");
    }

    /** Requests a fenced runtime tool decision and blocks without holding control-plane threads. */
    public RuntimeApprovalDecision awaitRuntimeToolApproval(
            String taskId, String token, String toolUseId, String toolName, Object inputPreview) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("kind", "tool_confirmation");
        request.put("toolUseId", toolUseId);
        request.put("toolName", toolName);
        request.put("inputPreview", inputPreview == null ? Map.of() : inputPreview);
        JsonNode created =
                taskSend(
                        "POST",
                        taskId,
                        "runtime-approvals",
                        token,
                        request,
                        "runtime-approval.request");
        String approvalId = created.path("approval").path("id").asText();
        if (approvalId.isBlank()) {
            throw new IllegalStateException("runtime-approval.request returned no approval id");
        }
        for (; ; ) {
            JsonNode decision =
                    taskSend(
                            "GET",
                            taskId,
                            "runtime-approvals/" + path(approvalId) + "/decision",
                            token,
                            null,
                            "runtime-approval.poll");
            long decisionVersion = decision.path("decisionVersion").asLong();
            if (decisionVersion > 0) {
                taskSend(
                        "POST",
                        taskId,
                        "runtime-approvals/" + path(approvalId) + "/ack",
                        token,
                        Map.of("decisionVersion", decisionVersion),
                        "runtime-approval.ack");
                return new RuntimeApprovalDecision(
                        approvalId,
                        decisionVersion,
                        decision.path("allow").asBoolean(false),
                        decision.path("denyMessage").asText(""));
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("runtime-approval.poll interrupted", e);
            }
        }
    }

    public record RuntimeApprovalDecision(
            String approvalId, long decisionVersion, boolean allow, String denyMessage) {}

    public JsonNode workspaceApplied(String taskId, String token, String digest) {
        return taskSend(
                "POST",
                taskId,
                "workspace-application",
                token,
                Map.of("digest", digest),
                "workspace.applied");
    }

    public JsonNode taskContext(String taskId, String token) {
        return taskSend("GET", taskId, "context", token, null, "task.context");
    }

    /** Returns the canonical task-scoped collaboration tools advertised by the control plane. */
    public JsonNode tools(String taskId, String token) {
        return mcp(token, "tools/list", Map.of("taskId", taskId)).path("tools");
    }

    /** Invokes one canonical task-scoped collaboration tool through the control-plane MCP. */
    public JsonNode callTool(
            String taskId, String token, String toolName, Map<String, Object> arguments) {
        return callTool(taskId, token, toolName, arguments, null);
    }

    /** Invokes a tool while preserving the framework call identity for diagnostics. */
    public JsonNode callTool(
            String taskId,
            String token,
            String toolName,
            Map<String, Object> arguments,
            String toolCallId) {
        Map<String, Object> scoped =
                arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments);
        scoped.put("taskId", taskId);
        if (toolCallId != null && !toolCallId.isBlank()) {
            scoped.put("_toolCallId", toolCallId);
        }
        return mcp(token, "tools/call", Map.of("name", toolName, "arguments", scoped));
    }

    public JsonNode createChild(String taskId, String token, Object request) {
        return taskSend("POST", taskId, "children", token, request, "issue.child.create");
    }

    public JsonNode acknowledge(String taskId, String token, List<String> inputIds) {
        return taskSend("POST", taskId, "ack", token, Map.of("inputIds", inputIds), "task.ack");
    }

    public JsonNode start(String taskId, String token, long expectedVersion) {
        return taskSend(
                "POST",
                taskId,
                "start",
                token,
                Map.of("expectedVersion", expectedVersion),
                "task.start");
    }

    public JsonNode progress(String taskId, String token, String content) {
        return taskSend(
                "POST", taskId, "progress", token, Map.of("content", content), "task.progress");
    }

    public JsonNode respond(String taskId, String token, String content) {
        return taskSend(
                "POST",
                taskId,
                "respond",
                token,
                Map.of("content", content, "type", "result"),
                "task.respond");
    }

    public JsonNode complete(
            String taskId,
            String token,
            long expectedVersion,
            String summary,
            Object result,
            List<String> processedInputIds,
            List<String> deferredInputIds) {
        return taskSend(
                "POST",
                taskId,
                "complete",
                token,
                Map.of(
                        "expectedVersion", expectedVersion,
                        "summary", summary == null ? "" : summary,
                        "result", result == null ? Map.of() : result,
                        "processedInputIds",
                                processedInputIds == null ? List.of() : processedInputIds,
                        "deferredInputIds",
                                deferredInputIds == null ? List.of() : deferredInputIds),
                "task.complete");
    }

    /** Submit an explicit business outcome; the server preserves partial results on failure. */
    public JsonNode finish(
            String taskId,
            String token,
            long expectedVersion,
            String outcome,
            String reason,
            Object result,
            List<String> processedInputIds,
            List<String> deferredInputIds) {
        return taskSend(
                "POST",
                taskId,
                "complete",
                token,
                Map.of(
                        "expectedVersion",
                        expectedVersion,
                        "outcome",
                        outcome,
                        "summary",
                        reason == null ? "" : reason,
                        "result",
                        result == null ? Map.of() : result,
                        "processedInputIds",
                        processedInputIds == null ? List.of() : processedInputIds,
                        "deferredInputIds",
                        deferredInputIds == null ? List.of() : deferredInputIds),
                "task.complete");
    }

    public JsonNode fail(
            String taskId, String token, long expectedVersion, String code, String message) {
        return taskSend(
                "POST",
                taskId,
                "fail",
                token,
                Map.of(
                        "expectedVersion", expectedVersion,
                        "code", code == null ? "agent_failed" : code,
                        "message", message == null ? "" : message),
                "task.fail");
    }

    public JsonNode run(String taskId, String token) {
        return taskSend("GET", taskId, "run", token, null, "run.get");
    }

    public JsonNode runGraph(String taskId, String token) {
        return taskSend("GET", taskId, "run/graph", token, null, "run.graph");
    }

    public JsonNode completeRunNode(String taskId, String token, Object output) {
        return taskSend(
                "POST",
                taskId,
                "run/node/complete",
                token,
                Map.of("output", output == null ? Map.of() : output),
                "run.node.complete");
    }

    public JsonNode failRunNode(String taskId, String token, String code, String message) {
        return taskSend(
                "POST",
                taskId,
                "run/node/fail",
                token,
                Map.of("code", code, "message", message),
                "run.node.fail");
    }

    public JsonNode replanRun(String taskId, String token, Object node) {
        return taskSend("POST", taskId, "run/replan", token, node, "run.replan");
    }

    public JsonNode signalRun(
            String taskId, String token, String name, String idempotencyKey, Object payload) {
        return taskSend(
                "POST",
                taskId,
                "run/signals/" + path(name),
                token,
                Map.of(
                        "idempotencyKey",
                        idempotencyKey,
                        "payload",
                        payload == null ? Map.of() : payload),
                "run.signal");
    }

    public JsonNode runArtifacts(String taskId, String token) {
        return taskSend("GET", taskId, "run/artifacts", token, null, "run.artifacts");
    }

    /** Uploads shared bytes; local runtime paths never cross the collaboration boundary. */
    public JsonNode uploadArtifact(
            String taskId,
            String token,
            String filename,
            String contentType,
            byte[] content,
            String targetType,
            String targetRef) {
        String boundary = "aistio-" + UUID.randomUUID();
        String safeName = filename.replace('"', '_').replace('\r', '_').replace('\n', '_');
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeField(body, boundary, "sourceTaskId", taskId);
            writeField(
                    body, boundary, "targetType", targetType == null ? "agent-task" : targetType);
            writeField(body, boundary, "targetRef", targetRef == null ? taskId : targetRef);
            body.write(
                    ("--"
                                    + boundary
                                    + "\r\n"
                                    + "Content-Disposition: form-data; name=\"file\"; filename=\""
                                    + safeName
                                    + "\"\r\nContent-Type: "
                                    + (contentType == null
                                            ? "application/octet-stream"
                                            : contentType)
                                    + "\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            body.write(content);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            ControlPlaneHttpClient.Response response =
                    http.sendBytes(
                            "POST",
                            "/api/v1/artifacts/uploads",
                            "multipart/form-data; boundary=" + boundary,
                            body.toByteArray(),
                            Map.of(TASK_TOKEN_HEADER, token));
            if (response.status() < 200 || response.status() >= 300) {
                throw new CollaborationHttpException(
                        "artifact.upload", response.status(), response.body());
            }
            return ControlPlaneHttpClient.mapper().readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("artifact.upload failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("artifact.upload interrupted", e);
        }
    }

    public byte[] downloadArtifact(String artifactId, String taskId, String token) {
        try {
            ControlPlaneHttpClient.BytesResponse response =
                    http.sendForBytes(
                            "POST",
                            "/api/v1/artifacts/"
                                    + path(artifactId)
                                    + "/download?taskId="
                                    + path(taskId),
                            null,
                            null,
                            Map.of(TASK_TOKEN_HEADER, token));
            if (response.status() < 200 || response.status() >= 300) {
                throw new CollaborationHttpException(
                        "artifact.download",
                        response.status(),
                        new String(response.body(), StandardCharsets.UTF_8));
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("artifact.download failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("artifact.download interrupted", e);
        }
    }

    private JsonNode mcp(String token, String method, Object params) {
        try {
            ControlPlaneHttpClient.Response response =
                    http.send(
                            "POST",
                            "/mcp/collaboration",
                            Map.of(
                                    "jsonrpc",
                                    "2.0",
                                    "id",
                                    UUID.randomUUID().toString(),
                                    "method",
                                    method,
                                    "params",
                                    params == null ? Map.of() : params),
                            Map.of(TASK_TOKEN_HEADER, token));
            if (response.status() < 200 || response.status() >= 300) {
                throw new CollaborationHttpException(method, response.status(), response.body());
            }
            JsonNode body = ControlPlaneHttpClient.mapper().readTree(response.body());
            if (!body.path("error").isMissingNode() && !body.path("error").isNull()) {
                throw new CollaborationHttpException(
                        method, response.status(), body.path("error").toString());
            }
            JsonNode result = body.path("result");
            if (result.path("isError").asBoolean(false)) {
                throw new CollaborationHttpException(
                        method, response.status(), result.path("content").toString());
            }
            JsonNode structured = result.path("structuredContent");
            return structured.isMissingNode() || structured.isNull() ? result : structured;
        } catch (IOException e) {
            throw new IllegalStateException(method + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " interrupted", e);
        }
    }

    private static void writeField(
            ByteArrayOutputStream body, String boundary, String name, String value)
            throws IOException {
        body.write(
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\""
                                + name
                                + "\"\r\n\r\n"
                                + value
                                + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode taskSend(
            String method,
            String taskId,
            String action,
            String token,
            Object body,
            String operation) {
        return send(
                method,
                "/api/v1/agent-tasks/" + path(taskId) + "/" + action,
                body,
                Map.of(TASK_TOKEN_HEADER, token),
                operation);
    }

    private JsonNode send(
            String method,
            String path,
            Object body,
            Map<String, String> headers,
            String operation) {
        try {
            ControlPlaneHttpClient.Response response = http.send(method, path, body, headers);
            if (response.status() < 200 || response.status() >= 300) {
                throw new CollaborationHttpException(operation, response.status(), response.body());
            }
            return response.body().isBlank()
                    ? ControlPlaneHttpClient.mapper().createObjectNode()
                    : ControlPlaneHttpClient.mapper().readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException(operation + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " interrupted", e);
        }
    }

    private static String path(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Non-2xx response from the collaboration API. */
    public static final class CollaborationHttpException extends RuntimeException {
        private final int status;
        private final String responseBody;

        public CollaborationHttpException(String operation, int status, String responseBody) {
            super(operation + " failed: HTTP " + status + errorDetail(responseBody));
            this.status = status;
            this.responseBody = responseBody;
        }

        private static String errorDetail(String body) {
            if (body == null || body.isBlank()) {
                return "";
            }
            String compact = body.replaceAll("\\s+", " ").trim();
            int limit = Math.min(compact.length(), 512);
            return ": " + compact.substring(0, limit) + (compact.length() > limit ? "..." : "");
        }

        public int status() {
            return status;
        }

        public String responseBody() {
            return responseBody;
        }
    }
}
