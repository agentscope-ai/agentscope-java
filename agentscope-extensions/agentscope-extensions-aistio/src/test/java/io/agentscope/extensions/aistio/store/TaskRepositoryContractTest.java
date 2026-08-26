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
package io.agentscope.extensions.aistio.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for {@link TaskRepository} semantics shared by {@link WorkspaceTaskRepository}
 * and {@link ControlPlaneTaskRepository}.
 */
class TaskRepositoryContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "test-token";
    private static final String AGENT = "parent-agent";
    private static final String NS = "default";
    private static final String SESSION = "session-A";

    @TempDir Path tempDir;

    private WorkspaceManager wsm;
    private WorkspaceTaskRepository workspaceRepo;

    private HttpServer server;
    private String baseUrl;
    private final ConcurrentHashMap<String, StubTask> stubTasks = new ConcurrentHashMap<>();
    private ControlPlaneTaskRepository cpRepoA;
    private ControlPlaneTaskRepository cpRepoB;

    @BeforeEach
    void setUp() throws IOException {
        wsm = new WorkspaceManager(tempDir);
        workspaceRepo = WorkspaceTaskRepository.forTests(wsm, AGENT);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/dp/tasks", this::handleTasks);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ControlPlaneHttpClient http = new ControlPlaneHttpClient(baseUrl, TOKEN);
        cpRepoA = ControlPlaneTaskRepository.forTests(http, AGENT, NS, AGENT);
        cpRepoB = ControlPlaneTaskRepository.forTests(http, AGENT, NS, AGENT);
    }

    @AfterEach
    void tearDown() {
        if (workspaceRepo != null) {
            workspaceRepo.shutdown();
        }
        if (cpRepoA != null) {
            cpRepoA.shutdown();
        }
        if (cpRepoB != null) {
            cpRepoB.shutdown();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void markDelivered_idempotent_workspace() {
        markDelivered_idempotent(workspaceRepo, Backend.WORKSPACE);
    }

    @Test
    void markDelivered_idempotent_controlPlane() {
        markDelivered_idempotent(cpRepoA, Backend.CONTROL_PLANE);
    }

    @Test
    void cancelTask_visibleAcrossInstances_workspace() throws Exception {
        WorkspaceTaskRepository repoB = WorkspaceTaskRepository.forTests(wsm, AGENT);
        try {
            cancelTask_visibleAcrossInstances(workspaceRepo, repoB, Backend.WORKSPACE);
        } finally {
            repoB.shutdown();
        }
    }

    @Test
    void cancelTask_visibleAcrossInstances_controlPlane() throws Exception {
        cancelTask_visibleAcrossInstances(cpRepoA, cpRepoB, Backend.CONTROL_PLANE);
    }

    @Test
    void terminalStatus_notOverwrittenByNonTerminal_workspace() throws Exception {
        terminalStatus_notOverwrittenByNonTerminal(workspaceRepo, Backend.WORKSPACE);
    }

    @Test
    void terminalStatus_notOverwrittenByNonTerminal_controlPlane() throws Exception {
        terminalStatus_notOverwrittenByNonTerminal(cpRepoA, Backend.CONTROL_PLANE);
    }

    private enum Backend {
        WORKSPACE,
        CONTROL_PLANE
    }

    private void markDelivered_idempotent(TaskRepository repo, Backend backend) {
        seedCompleted(backend, "t-deliver");

        repo.markDelivered(RuntimeContext.empty(), SESSION, "t-deliver");
        Instant first = deliveredAt(backend, "t-deliver");
        assertNotNull(first);

        repo.markDelivered(RuntimeContext.empty(), SESSION, "t-deliver");
        assertEquals(first, deliveredAt(backend, "t-deliver"));
    }

    private void cancelTask_visibleAcrossInstances(
            TaskRepository repoA, TaskRepository repoB, Backend backend) throws Exception {
        CompletableFuture<String> adopted = new CompletableFuture<>();
        repoA.putTask(
                RuntimeContext.empty(),
                "t-cancel",
                "sub-a",
                SESSION,
                new TaskRunSpec.AdoptedTaskRunSpec(adopted));

        assertTrue(repoB.cancelTask(RuntimeContext.empty(), SESSION, "t-cancel"));

        awaitCancelRequested(backend, "t-cancel");
    }

    private void terminalStatus_notOverwrittenByNonTerminal(TaskRepository repo, Backend backend)
            throws Exception {
        CompletableFuture<String> adopted = new CompletableFuture<>();
        repo.putTask(
                RuntimeContext.empty(),
                "t-term",
                "sub-a",
                SESSION,
                new TaskRunSpec.AdoptedTaskRunSpec(adopted));

        forceTerminalCompleted(backend, "t-term");
        invokeHeartbeat(repo);

        assertEquals(TaskStatus.COMPLETED, readStatus(backend, "t-term"));
        adopted.complete("done");
    }

    private void seedCompleted(Backend backend, String taskId) {
        TaskRecord r = new TaskRecord(taskId, "sub-a", AGENT, SESSION, null);
        r.setStatus(TaskStatus.COMPLETED);
        r.setResult("ok");
        if (backend == Backend.WORKSPACE) {
            wsm.writeTaskRecord(RuntimeContext.empty(), AGENT, SESSION, r);
        } else {
            stubUpsert(taskId, r);
        }
    }

    private Instant deliveredAt(Backend backend, String taskId) {
        if (backend == Backend.WORKSPACE) {
            return wsm.readTaskRecord(RuntimeContext.empty(), AGENT, SESSION, taskId)
                    .map(TaskRecord::getDeliveredAt)
                    .orElse(null);
        }
        StubTask t = stubTasks.get(taskKey(SESSION, taskId));
        return t == null ? null : t.deliveredAt;
    }

    private void awaitCancelRequested(Backend backend, String taskId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (isCancelRequested(backend, taskId)) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(isCancelRequested(backend, taskId));
    }

    private boolean isCancelRequested(Backend backend, String taskId) {
        if (backend == Backend.WORKSPACE) {
            return wsm.readTaskRecord(RuntimeContext.empty(), AGENT, SESSION, taskId)
                    .map(TaskRecord::isCancelRequested)
                    .orElse(false);
        }
        StubTask t = stubTasks.get(taskKey(SESSION, taskId));
        return t != null && t.cancelRequested;
    }

    private void forceTerminalCompleted(Backend backend, String taskId) {
        TaskRecord r = new TaskRecord(taskId, "sub-a", AGENT, SESSION, null);
        r.setStatus(TaskStatus.COMPLETED);
        r.setResult("forced");
        if (backend == Backend.WORKSPACE) {
            wsm.writeTaskRecord(RuntimeContext.empty(), AGENT, SESSION, r);
        } else {
            stubUpsert(taskId, r);
        }
    }

    private TaskStatus readStatus(Backend backend, String taskId) {
        if (backend == Backend.WORKSPACE) {
            return wsm.readTaskRecord(RuntimeContext.empty(), AGENT, SESSION, taskId)
                    .map(TaskRecord::getStatus)
                    .orElse(null);
        }
        StubTask t = stubTasks.get(taskKey(SESSION, taskId));
        return t == null ? null : t.status;
    }

    private static void invokeHeartbeat(TaskRepository repo) throws Exception {
        Method m = repo.getClass().getDeclaredMethod("heartbeat");
        m.setAccessible(true);
        m.invoke(repo);
    }

    private void stubUpsert(String taskId, TaskRecord record) {
        StubTask existing = stubTasks.get(taskKey(SESSION, taskId));
        if (existing != null
                && existing.status != null
                && existing.status.isTerminal()
                && record.getStatus() != null
                && !record.getStatus().isTerminal()) {
            return;
        }
        StubTask t = existing != null ? existing : new StubTask();
        t.taskId = taskId;
        t.parentSessionId = SESSION;
        t.status = record.getStatus();
        t.result = record.getResult();
        t.errorMessage = record.getErrorMessage();
        t.cancelRequested = record.isCancelRequested();
        t.lastUpdatedAt = Instant.now();
        if (t.createdAt == null) {
            t.createdAt = Instant.now();
        }
        stubTasks.put(taskKey(SESSION, taskId), t);
    }

    private void handleTasks(HttpExchange ex) throws IOException {
        if (!TOKEN.equals(ex.getRequestHeaders().getFirst("X-Builder-Internal-Token"))) {
            write(ex, 401, Map.of("error", "unauthorized"));
            return;
        }
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        if ("POST".equals(method) && path.endsWith("/heartbeat")) {
            JsonNode body = MAPPER.readTree(ex.getRequestBody());
            assertTenant(body);
            Instant now = Instant.now();
            for (JsonNode ref : body.path("tasks")) {
                String sid = ref.path("parentSessionId").asText();
                String tid = ref.path("taskId").asText();
                StubTask t = stubTasks.get(taskKey(sid, tid));
                if (t != null && t.status != null && !t.status.isTerminal()) {
                    t.lastUpdatedAt = now;
                }
            }
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (path.contains("/pending-deliveries")) {
            handlePendingDeliveries(ex);
            return;
        }
        int tasksIdx = path.indexOf("/tasks/");
        String taskId =
                tasksIdx >= 0
                        ? URLDecoder.decode(path.substring(tasksIdx + 7), StandardCharsets.UTF_8)
                        : "";
        if (taskId.contains("/")) {
            taskId = taskId.substring(0, taskId.indexOf('/'));
        }

        if ("PUT".equals(method)) {
            JsonNode body = MAPPER.readTree(ex.getRequestBody());
            assertTenant(body);
            String sid = body.path("parentSessionId").asText();
            StubTask existing = stubTasks.get(taskKey(sid, taskId));
            String statusStr = body.path("status").asText(null);
            TaskStatus incoming =
                    statusStr == null || statusStr.isBlank()
                            ? TaskStatus.PENDING
                            : TaskStatus.valueOf(statusStr);
            if (existing != null
                    && existing.status != null
                    && existing.status.isTerminal()
                    && !incoming.isTerminal()) {
                write(ex, 200, existing.toJson());
                return;
            }
            StubTask t = existing != null ? existing : new StubTask();
            t.taskId = taskId;
            t.parentSessionId = sid;
            t.status = incoming;
            if (body.hasNonNull("result")) {
                t.result = body.path("result").asText();
            }
            if (body.hasNonNull("errorMessage")) {
                t.errorMessage = body.path("errorMessage").asText();
            }
            t.cancelRequested = body.path("cancelRequested").asBoolean(false);
            t.lastUpdatedAt = Instant.now();
            if (t.createdAt == null) {
                t.createdAt = Instant.now();
            }
            stubTasks.put(taskKey(sid, taskId), t);
            write(ex, 200, t.toJson());
            return;
        }
        if ("GET".equals(method) && !path.endsWith("/tasks")) {
            Map<String, List<String>> q = parseQuery(ex.getRequestURI().getRawQuery());
            String sid = first(q, "parentSessionId");
            StubTask t = stubTasks.get(taskKey(sid, taskId));
            if (t == null) {
                write(ex, 404, Map.of("error", "not found"));
                return;
            }
            write(ex, 200, t.toJson());
            return;
        }
        if ("GET".equals(method) && path.endsWith("/tasks")) {
            Map<String, List<String>> q = parseQuery(ex.getRequestURI().getRawQuery());
            String sid = first(q, "parentSessionId");
            List<Map<String, Object>> out = new ArrayList<>();
            for (StubTask t : stubTasks.values()) {
                if (sid.equals(t.parentSessionId)) {
                    out.add(t.toJson());
                }
            }
            write(ex, 200, Map.of("tasks", out));
            return;
        }
        if ("POST".equals(method) && path.endsWith("/cancel")) {
            JsonNode body = MAPPER.readTree(ex.getRequestBody());
            assertTenant(body);
            String sid = body.path("parentSessionId").asText();
            StubTask t = stubTasks.get(taskKey(sid, taskId));
            if (t == null) {
                write(ex, 404, Map.of("error", "not found"));
                return;
            }
            t.cancelRequested = true;
            if (t.status != null && !t.status.isTerminal()) {
                t.status = TaskStatus.CANCELLED;
            }
            t.lastUpdatedAt = Instant.now();
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if ("POST".equals(method) && path.endsWith("/delivered")) {
            JsonNode body = MAPPER.readTree(ex.getRequestBody());
            assertTenant(body);
            String sid = body.path("parentSessionId").asText();
            StubTask t = stubTasks.get(taskKey(sid, taskId));
            if (t == null) {
                write(ex, 404, Map.of("error", "not found"));
                return;
            }
            boolean written = false;
            if (t.deliveredAt == null) {
                t.deliveredAt = Instant.now();
                written = true;
            }
            write(ex, 200, Map.of("written", written));
            return;
        }
        write(ex, 405, Map.of("error", "method not allowed"));
    }

    private void handlePendingDeliveries(HttpExchange ex) throws IOException {
        Map<String, List<String>> q = parseQuery(ex.getRequestURI().getRawQuery());
        String sid = first(q, "parentSessionId");
        List<Map<String, Object>> out = new ArrayList<>();
        for (StubTask t : stubTasks.values()) {
            if (sid.equals(t.parentSessionId)
                    && t.status != null
                    && t.status.isTerminal()
                    && t.deliveredAt == null) {
                out.add(t.toJson());
            }
        }
        write(ex, 200, Map.of("tasks", out));
    }

    private static String taskKey(String sessionId, String taskId) {
        return sessionId + "\u0000" + taskId;
    }

    private static void assertTenant(JsonNode body) {
        if (!AGENT.equals(body.path("agentName").asText())
                || !NS.equals(body.path("namespace").asText())) {
            throw new IllegalStateException("tenant mismatch");
        }
    }

    private static String first(Map<String, List<String>> q, String name) {
        List<String> v = q.get(name);
        return v == null || v.isEmpty() ? "" : v.get(0);
    }

    private static Map<String, List<String>> parseQuery(String raw) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            k = URLDecoder.decode(k, StandardCharsets.UTF_8);
            v = URLDecoder.decode(v, StandardCharsets.UTF_8);
            out.computeIfAbsent(k, ignored -> new ArrayList<>()).add(v);
        }
        return out;
    }

    private static void write(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static final class StubTask {
        String taskId;
        String parentSessionId;
        TaskStatus status;
        String result;
        String errorMessage;
        boolean cancelRequested;
        Instant createdAt;
        Instant lastUpdatedAt;
        Instant deliveredAt;

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", taskId);
            m.put("parentAgentId", AGENT);
            m.put("parentSessionId", parentSessionId);
            m.put("status", status != null ? status.name() : null);
            m.put("terminal", status != null && status.isTerminal());
            m.put("result", result);
            m.put("errorMessage", errorMessage);
            m.put("cancelRequested", cancelRequested);
            if (createdAt != null) {
                m.put("createdAt", createdAt.toString());
            }
            if (lastUpdatedAt != null) {
                m.put("lastUpdatedAt", lastUpdatedAt.toString());
            }
            if (deliveredAt != null) {
                m.put("deliveredAt", deliveredAt.toString());
            }
            return m;
        }
    }
}
