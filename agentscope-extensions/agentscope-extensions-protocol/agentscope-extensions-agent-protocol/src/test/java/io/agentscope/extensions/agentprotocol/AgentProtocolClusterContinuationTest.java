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
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.agent.ExecutionIdentity;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * End-to-end cluster scenario, with two main-agent instances and two protocol-server instances.
 *
 * <p>A conversation starts on main instance 1, which delegates to a remote child; the child runs on
 * subagent instance 1. The user then sends another command without changing the conversation, and
 * that turn lands on main instance 2. Instance 2 has no in-memory knowledge of the child, so it must
 * rebuild it from shared state and continue the session instance 1 opened — the child then sees both
 * turns in one conversation while running on subagent instance 2.
 *
 * <p>The two sides share exactly what a distributed deployment would share: the main side shares its
 * workspace task records (the two {@link WorkspaceTaskRepository} instances point at one directory)
 * and one {@link AgentState}; the server side shares its {@code ProtocolTaskRepository}, run registry
 * and context store. Communication is real HTTP through {@code AgentProtocolTransport}, so the
 * client-side contract is exercised too.
 */
class AgentProtocolClusterContinuationTest {

    private static final String MAIN_AGENT = "main-agent";
    private static final String WORKER = "worker";
    private static final String LABEL = "reviewer";
    private static final String PARENT_SESSION = "session-u1";

    @TempDir Path root;

    /** Every child invocation, formatted {@code <instance>@<sessionId>:<input>}. */
    private final List<String> childCalls = new CopyOnWriteArrayList<>();

    private ClusterServer server;
    private WorkspaceProtocolTaskRepository protocolRepository;
    private WorkspaceTaskRepository mainRepository1;
    private WorkspaceTaskRepository mainRepository2;
    private DefaultAgentManager mainManager;
    private AgentState sharedState;

    @BeforeEach
    void setUp() throws Exception {
        Path protocolDir = Files.createDirectories(root.resolve("protocol"));
        Path mainWorkspace = Files.createDirectories(root.resolve("main"));

        HarnessAgent child1 = recordingChild("child-1");
        HarnessAgent child2 = recordingChild("child-2");

        protocolRepository = new WorkspaceProtocolTaskRepository(protocolDir);
        AgentProtocolTaskEventBus eventBus = new AgentProtocolTaskEventBus();
        ProtocolTaskRunRegistry runRegistry = BaseStoreProtocolTaskRunRegistry.inMemory();
        ProtocolTaskContextStore contextStore = BaseStoreProtocolTaskContextStore.inMemory();
        AgentProtocolProperties properties = new AgentProtocolProperties();

        server =
                new ClusterServer(
                        List.of(
                                new AgentProtocolTaskStore(
                                        request -> child1,
                                        protocolRepository,
                                        eventBus,
                                        properties,
                                        List.of(),
                                        runRegistry,
                                        contextStore),
                                new AgentProtocolTaskStore(
                                        request -> child2,
                                        protocolRepository,
                                        eventBus,
                                        properties,
                                        List.of(),
                                        runRegistry,
                                        contextStore)));

        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(
                                new SubagentEntry(
                                        WORKER,
                                        "remote worker",
                                        rc -> child1,
                                        SubagentDeclaration.builder()
                                                .name(WORKER)
                                                .description("remote worker")
                                                .url(server.baseUrl())
                                                .remoteStreaming(false)
                                                .build())),
                        null);

        // One directory, two repositories: the shared record store a distributed TaskRepository
        // would provide, without the file-per-session duplication.
        mainRepository1 =
                new WorkspaceTaskRepository(new WorkspaceManager(mainWorkspace), MAIN_AGENT);
        mainRepository2 =
                new WorkspaceTaskRepository(new WorkspaceManager(mainWorkspace), MAIN_AGENT);

        // One AgentState stands in for a shared AgentStateStore: instance 2 never sees instance 1's
        // in-memory spawn map, only what was persisted here.
        sharedState = AgentState.builder().build();
        mainManager = manager;
    }

    @AfterEach
    void tearDown() {
        if (mainRepository1 != null) {
            mainRepository1.shutdown();
        }
        if (mainRepository2 != null) {
            mainRepository2.shutdown();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void aFollowUpLandingOnAnotherMainInstanceContinuesTheChildSession() {
        AgentSpawnTool main1 = mainTool(mainRepository1);
        AgentSpawnTool main2 = mainTool(mainRepository2);

        RuntimeContext firstTurn = turnOn("main-agent-1");
        RuntimeContext secondTurn = turnOn("main-agent-2");

        String spawn =
                text(
                        main1.agentSpawn(
                                        firstTurn,
                                        sharedState,
                                        WORKER,
                                        "first question",
                                        true,
                                        LABEL,
                                        10,
                                        null)
                                .block(),
                        ToolResultState.SUCCESS);
        assertTrue(spawn.contains("agent_key: "), spawn);
        assertTrue(spawn.contains("status: ok"), spawn);

        String send =
                text(
                        main2.agentSend(secondTurn, sharedState, null, LABEL, "second question", 10)
                                .block(),
                        ToolResultState.SUCCESS);

        // The result names the handle the caller already holds; the session stays an internal
        // detail of the child conversation. Instance 2 addressed the child by label only, and the
        // session it continued is asserted on the wire below rather than echoed in this report.
        assertFalse(send.contains("session:"), "send must not echo the child session: " + send);
        assertTrue(send.contains("status: ok"), send);

        assertEquals(2, childCalls.size(), childCalls.toString());
        String firstCall = childCalls.get(0);
        String secondCall = childCalls.get(1);
        assertTrue(firstCall.startsWith("child-1@"), firstCall);
        assertTrue(secondCall.startsWith("child-2@"), secondCall);
        assertEquals(sessionOf(firstCall), sessionOf(secondCall), "one session for both turns");

        List<SubmitRecord> submits = server.submits();
        String firstTaskId = submits.get(0).taskId();
        String secondTaskId = submits.get(1).taskId();
        assertNotEquals(firstTaskId, secondTaskId, "each turn is its own task");
        Object firstSession = submits.get(0).context().get("session_id");
        Object secondSession = submits.get(1).context().get("session_id");
        assertTrue(firstSession != null, "the spawn must name the child session: " + submits);
        assertEquals(firstSession, secondSession, "a follow-up repeats the same session");
        assertEquals(
                sessionOf(firstCall), firstSession, "the wire session is where the child runs");
        assertEquals("second question", submits.get(1).input());

        // Both tasks stay addressable on their own, but resolve to one agent session.
        TaskRecord first = protocolRepository.find(firstTaskId).orElseThrow();
        TaskRecord second = protocolRepository.find(secondTaskId).orElseThrow();
        assertEquals(firstSession, first.getSubSessionId());
        assertEquals(first.getSubSessionId(), second.getSubSessionId());
        assertEquals(TaskStatus.COMPLETED, first.getStatus());
        assertEquals(TaskStatus.COMPLETED, second.getStatus());
    }

    @Test
    void aBackgroundSpawnIsAlsoContinuedByAnotherInstance() throws Exception {
        AgentSpawnTool main1 = mainTool(mainRepository1);
        AgentSpawnTool main2 = mainTool(mainRepository2);

        String spawn =
                text(
                        main1.agentSpawn(
                                        turnOn("main-agent-1"),
                                        sharedState,
                                        WORKER,
                                        "background question",
                                        true,
                                        LABEL,
                                        0,
                                        null)
                                .block(),
                        ToolResultState.SUCCESS);
        String taskId = awaitFirstSubmit().taskId();
        assertTrue(spawn.contains("task_id: " + taskId), spawn);

        awaitTerminal(taskId);

        String send =
                text(
                        main2.agentSend(
                                        turnOn("main-agent-2"),
                                        sharedState,
                                        null,
                                        LABEL,
                                        "follow-up after background",
                                        10)
                                .block(),
                        ToolResultState.SUCCESS);

        // Continuity lives on the wire, not in the delivery report: the report names only the
        // handle the caller holds, so assert that the follow-up submitted the same session and
        // that it matches the conversation both child turns ran in.
        assertFalse(send.contains("session:"), "send must not echo the child session: " + send);
        List<SubmitRecord> submits = server.submits();
        assertEquals(2, submits.size(), submits.toString());
        Object firstSession = submits.get(0).context().get("session_id");
        Object secondSession = submits.get(1).context().get("session_id");
        assertNotNull(firstSession, "the spawn must name the child session: " + submits);
        assertEquals(firstSession, secondSession, "a follow-up repeats the same session");
        assertEquals(2, childCalls.size(), childCalls.toString());
        assertEquals(sessionOf(childCalls.get(0)), sessionOf(childCalls.get(1)));
        assertEquals(
                sessionOf(childCalls.get(0)),
                firstSession,
                "the wire session is where the child runs");
    }

    private AgentSpawnTool mainTool(WorkspaceTaskRepository repository) {
        return new AgentSpawnTool(mainManager, repository, 0);
    }

    private static RuntimeContext turnOn(String mainAgentId) {
        // No agent loop drives these turns: install the identity the running agent would publish.
        return RuntimeContext.builder()
                .sessionId(PARENT_SESSION)
                .userId("u-1")
                .put(
                        ExecutionIdentity.CONTEXT_KEY,
                        new ExecutionIdentity(
                                mainAgentId, PARENT_SESSION, "main", null, "run-" + mainAgentId))
                .build();
    }

    private HarnessAgent recordingChild(String instance) {
        HarnessAgent child = mock(HarnessAgent.class);
        when(child.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            Msg msg = invocation.getArgument(0);
                            RuntimeContext rc = invocation.getArgument(1);
                            childCalls.add(
                                    instance
                                            + "@"
                                            + rc.getSessionId()
                                            + ":"
                                            + msg.getTextContent());
                            return Flux.just(
                                    new AgentResultEvent(reply("reply to " + msg.getTextContent())),
                                    new AgentEndEvent(null));
                        });
        return child;
    }

    private static Msg reply(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }

    private static String text(ToolResultBlock block, ToolResultState expected) {
        assertNotNull(block);
        assertEquals(expected, block.getState());
        return ((TextBlock) block.getOutput().get(0)).getText();
    }

    private static String sessionOf(String call) {
        int at = call.indexOf('@');
        int colon = call.indexOf(':', at);
        return call.substring(at + 1, colon);
    }

    private SubmitRecord awaitFirstSubmit() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!server.submits().isEmpty()) {
                return server.submits().get(0);
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("no submission reached the cluster");
    }

    private void awaitTerminal(String taskId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> snapshot = server.snapshot(taskId);
            Object status = snapshot.get("status");
            if ("success".equals(status) || "error".equals(status) || "cancelled".equals(status)) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("task did not finish: " + server.snapshot(taskId));
    }

    /** One recorded {@code POST /tasks} payload. */
    private record SubmitRecord(
            String taskId,
            String agentId,
            String input,
            Map<String, Object> context,
            int instance) {}

    /**
     * Two protocol-server instances behind one HTTP endpoint, as a load balancer would present them.
     *
     * <p>Submissions are dispatched round-robin, so consecutive turns land on different instances;
     * reads go back to the instance that accepted the task. Both instances share the task repository,
     * run registry and context store, which is what lets the second turn resolve the first turn's
     * session.
     */
    private static final class ClusterServer implements AutoCloseable {

        private final ObjectMapper json = new ObjectMapper();
        private final HttpServer http;
        private final ExecutorService executor;
        private final List<AgentProtocolTaskStore> instances;
        private final Map<String, AgentProtocolTaskStore> ownerByTask = new ConcurrentHashMap<>();
        private final List<SubmitRecord> submits = new CopyOnWriteArrayList<>();
        private final AtomicInteger next = new AtomicInteger();

        ClusterServer(List<AgentProtocolTaskStore> instances) throws IOException {
            this.instances = instances;
            this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.executor = Executors.newCachedThreadPool();
            this.http.setExecutor(executor);
            this.http.createContext("/tasks", this::handle);
            this.http.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + http.getAddress().getPort();
        }

        List<SubmitRecord> submits() {
            return List.copyOf(submits);
        }

        Map<String, Object> snapshot(String taskId) {
            return ownerByTask.getOrDefault(taskId, instances.get(0)).snapshot(taskId);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if ("/tasks".equals(path) && "POST".equals(method)) {
                    submit(exchange);
                    return;
                }
                if (path.startsWith("/tasks/")) {
                    String rest = path.substring("/tasks/".length());
                    int slash = rest.indexOf('/');
                    String taskId = slash < 0 ? rest : rest.substring(0, slash);
                    String action = slash < 0 ? "" : rest.substring(slash + 1);
                    if ("GET".equals(method) && action.isEmpty()) {
                        send(exchange, 200, snapshot(taskId));
                        return;
                    }
                    if ("GET".equals(method) && "wait".equals(action)) {
                        String query = exchange.getRequestURI().getQuery();
                        long seconds =
                                query != null && query.contains("timeout_seconds=")
                                        ? Long.parseLong(
                                                query.substring(
                                                        query.indexOf("timeout_seconds=")
                                                                + "timeout_seconds=".length()))
                                        : 60L;
                        AgentProtocolTaskStore store =
                                ownerByTask.getOrDefault(taskId, instances.get(0));
                        send(exchange, 200, store.waitFor(taskId, seconds * 1000L));
                        return;
                    }
                    if ("POST".equals(method) && "cancel".equals(action)) {
                        ownerByTask.getOrDefault(taskId, instances.get(0)).cancel(taskId);
                        send(exchange, 200, Map.of());
                        return;
                    }
                }
                send(exchange, 404, Map.of("error", "not found"));
            } catch (IllegalArgumentException e) {
                send(exchange, 400, Map.of("error", String.valueOf(e.getMessage())));
            } catch (IllegalStateException e) {
                send(exchange, 409, Map.of("error", String.valueOf(e.getMessage())));
            } catch (Exception e) {
                send(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
            }
        }

        @SuppressWarnings("unchecked")
        private void submit(HttpExchange exchange) throws IOException {
            JsonNode body = json.readTree(exchange.getRequestBody());
            String taskId = body.path("task_id").asText();
            String agentId = body.path("agent_id").asText();
            String input = body.path("input").asText("");
            Map<String, Object> context =
                    body.hasNonNull("context")
                            ? json.convertValue(body.get("context"), Map.class)
                            : Map.of();

            int index = Math.floorMod(next.getAndIncrement(), instances.size());
            AgentProtocolTaskStore store = instances.get(index);
            ownerByTask.put(taskId, store);
            submits.add(new SubmitRecord(taskId, agentId, input, context, index));
            store.submit(taskId, agentId, input, context);
            send(exchange, 200, Map.of("task_id", taskId, "status", "pending"));
        }

        private void send(HttpExchange exchange, int code, Object body) throws IOException {
            byte[] bytes = json.writeValueAsBytes(body);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override
        public void close() {
            http.stop(0);
            executor.shutdownNow();
        }
    }
}
