/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.gateway.SubagentGatewayBridge;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;

class AgentSpawnToolLifecycleTest {

    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(10);

    @Test
    @Timeout(30)
    void repeatedEphemeralSpawnAndReleaseLeavesNoLiveOrPersistedEntries() throws Exception {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        List<Agent> instances = new CopyOnWriteArrayList<>();
        DefaultAgentManager manager =
                manager(
                        rc -> {
                            Agent agent =
                                    closeableAgent(
                                            "worker-" + created.incrementAndGet(),
                                            closed,
                                            Mono.just(reply("done")));
                            instances.add(agent);
                            return agent;
                        },
                        false);
        AgentSpawnTool tool = new AgentSpawnTool(manager, new StartingTaskRepository(), 0);
        AgentState state = AgentState.builder().build();
        RuntimeContext context = context("owner", "parent");

        for (int i = 0; i < 256; i++) {
            String spawn =
                    tool.agentSpawn(context, state, "worker", null, "reusable-label", 1, null)
                            .block(BLOCK_TIMEOUT);
            String key = valueAfter(spawn, "agent_key: ");

            assertTrue(tool.agentRelease(context, state, key, null).contains("status: released"));
        }

        assertEquals(256, created.get());
        assertEquals(256, closed.get());
        assertEquals("No addressable subagents.", tool.agentList(context, state));
        assertTrue(state.getToolContext().getSpawnRegistry().isEmpty());
        for (Agent instance : instances) {
            verify((AutoCloseable) instance, times(1)).close();
        }
    }

    @Test
    void sendByKeyAndLabelWorksBeforeReleaseButCannotRestoreAfterRelease() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        DefaultAgentManager manager =
                manager(rc -> closeableAgent("worker", closed, Mono.just(reply("done"))), false);
        AgentState state = AgentState.builder().build();
        RuntimeContext context = context("owner", "parent");
        AgentSpawnTool tool = new AgentSpawnTool(manager, new StartingTaskRepository(), 0);

        String spawn =
                tool.agentSpawn(context, state, "worker", null, "slot", 1, null)
                        .block(BLOCK_TIMEOUT);
        String key = valueAfter(spawn, "agent_key: ");

        assertTrue(tool.agentSend(context, state, key, null, "by key", 1).block().contains("done"));
        assertTrue(
                tool.agentSend(context, state, null, "slot", "by label", 1)
                        .block()
                        .contains("done"));
        assertTrue(tool.agentRelease(context, state, null, "slot").contains("status: released"));
        assertTrue(
                tool.agentSend(context, state, key, null, "late", 1).block().contains("Unknown"));
        assertTrue(
                tool.agentSend(context, state, null, "slot", "late", 1)
                        .block()
                        .contains("Unknown"));

        AgentSpawnTool restoredTool = new AgentSpawnTool(manager, new StartingTaskRepository(), 0);
        assertTrue(
                restoredTool
                        .agentSend(context, state, key, null, "restore", 1)
                        .block()
                        .contains("Unknown"));
        assertTrue(state.getToolContext().getSpawnRegistry().isEmpty());
        assertEquals(1, closed.get());
    }

    @Test
    @Timeout(20)
    void releaseWhileStateRestoreIsCreatingCannotRepublishReleasedAgent() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch allowFactoryReturn = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        DefaultAgentManager manager =
                manager(
                        rc -> {
                            factoryEntered.countDown();
                            try {
                                if (!allowFactoryReturn.await(10, TimeUnit.SECONDS)) {
                                    throw new AssertionError("Timed out waiting to finish restore");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("Restore factory interrupted", e);
                            }
                            return closeableAgent(
                                    "restored-worker", closed, Mono.just(reply("unexpected")));
                        },
                        false);
        AgentSpawnTool tool = new AgentSpawnTool(manager, new StartingTaskRepository(), 0);
        AgentState state = AgentState.builder().build();
        RuntimeContext context = context("owner", "restore-race-parent");
        String key = "agent:worker:restore-race";
        state.getToolContext()
                .putSpawnEntry(
                        key,
                        new io.agentscope.core.state.ToolContextState.SpawnEntry(
                                key, "worker", "child-session", "restoring", 1));

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> send =
                    caller.submit(
                            () ->
                                    tool.agentSend(context, state, key, null, "continue", 30)
                                            .block(BLOCK_TIMEOUT));
            assertTrue(factoryEntered.await(10, TimeUnit.SECONDS));

            assertTrue(tool.agentRelease(context, state, key, null).contains("status: released"));
            allowFactoryReturn.countDown();

            assertTrue(send.get(10, TimeUnit.SECONDS).contains("Unknown agent_key"));
            assertEquals(1, closed.get());
            assertTrue(state.getToolContext().getSpawnRegistry().isEmpty());
            assertEquals("No addressable subagents.", tool.agentList(context, state));
        } finally {
            allowFactoryReturn.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    @Timeout(20)
    void candidateIsNotAddressableUntilGatewayExposureFinishes() throws Exception {
        CountDownLatch exposureEntered = new CountDownLatch(1);
        CountDownLatch allowExposure = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        DefaultAgentManager manager =
                manager(
                        rc -> closeableAgent("worker", closed, Mono.just(reply("continued"))),
                        false);
        SubagentGatewayBridge bridge = mock(SubagentGatewayBridge.class);
        when(bridge.expose(any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            exposureEntered.countDown();
                            if (!allowExposure.await(10, TimeUnit.SECONDS)) {
                                throw new AssertionError("Timed out waiting to finish exposure");
                            }
                            return new SubagentGatewayBridge.ExposeResult("public-handle");
                        });
        AgentSpawnTool tool = new AgentSpawnTool(manager, new StartingTaskRepository(), 0, bridge);
        AgentState state = AgentState.builder().build();
        RuntimeContext context = context("owner", "exposure-parent");

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> spawn =
                    caller.submit(
                            () ->
                                    tool.agentSpawn(
                                                    context,
                                                    state,
                                                    "worker",
                                                    null,
                                                    "initializing",
                                                    1,
                                                    true)
                                            .block(BLOCK_TIMEOUT));
            assertTrue(exposureEntered.await(10, TimeUnit.SECONDS));

            assertEquals("No addressable subagents.", tool.agentList(context, state));
            assertTrue(tool.agentRelease(context, state, null, "initializing").contains("Unknown"));

            allowExposure.countDown();
            assertTrue(spawn.get(10, TimeUnit.SECONDS).contains("status: accepted"));
            assertTrue(
                    tool.agentSend(context, state, null, "initializing", "continue", 1)
                            .block(BLOCK_TIMEOUT)
                            .contains("continued"));
            assertTrue(
                    tool.agentRelease(context, state, null, "initializing")
                            .contains("status: released"));
            verify(bridge, times(1)).revoke("public-handle");
            assertEquals(1, closed.get());
        } finally {
            allowExposure.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void listLabelAndReleaseAreIsolatedByOwnerScope() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        DefaultAgentManager manager =
                manager(rc -> closeableAgent("worker", closed, Mono.just(reply("done"))), false);
        AgentSpawnTool tool = new AgentSpawnTool(manager, new StartingTaskRepository(), 0);
        RuntimeContext firstContext = context("same-user", "first-parent");
        RuntimeContext secondContext = context("same-user", "second-parent");
        RuntimeContext thirdContext = context("other-user", "first-parent");
        AgentState firstState = AgentState.builder().build();
        AgentState secondState = AgentState.builder().build();
        AgentState thirdState = AgentState.builder().build();

        String first =
                tool.agentSpawn(firstContext, firstState, "worker", null, "shared", 1, null)
                        .block(BLOCK_TIMEOUT);
        String second =
                tool.agentSpawn(secondContext, secondState, "worker", null, "shared", 1, null)
                        .block(BLOCK_TIMEOUT);
        String third =
                tool.agentSpawn(thirdContext, thirdState, "worker", null, "shared", 1, null)
                        .block(BLOCK_TIMEOUT);
        String firstKey = valueAfter(first, "agent_key: ");
        String secondKey = valueAfter(second, "agent_key: ");
        String thirdKey = valueAfter(third, "agent_key: ");

        assertNotEquals(firstKey, secondKey);
        assertNotEquals(firstKey, thirdKey);
        assertTrue(tool.agentList(firstContext, firstState).contains(firstKey));
        assertFalse(tool.agentList(firstContext, firstState).contains(secondKey));
        assertFalse(tool.agentList(firstContext, firstState).contains(thirdKey));
        assertTrue(tool.agentList(secondContext, secondState).contains(secondKey));
        assertFalse(tool.agentList(secondContext, secondState).contains(firstKey));
        assertTrue(tool.agentList(thirdContext, thirdState).contains(thirdKey));
        assertFalse(tool.agentList(thirdContext, thirdState).contains(firstKey));
        assertTrue(
                tool.agentRelease(firstContext, firstState, secondKey, null).contains("Unknown"));
        assertTrue(tool.agentRelease(firstContext, firstState, thirdKey, null).contains("Unknown"));
        assertTrue(
                tool.agentRelease(firstContext, firstState, null, "shared")
                        .contains("status: released"));
        assertTrue(tool.agentList(secondContext, secondState).contains(secondKey));
        assertTrue(
                tool.agentRelease(secondContext, secondState, null, "shared")
                        .contains("status: released"));
        assertTrue(tool.agentList(thirdContext, thirdState).contains(thirdKey));
        assertTrue(
                tool.agentRelease(thirdContext, thirdState, null, "shared")
                        .contains("status: released"));
        assertEquals(3, closed.get());
    }

    @Test
    @Timeout(20)
    void runningSynchronousSpawnIsBusyUntilTerminal() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Msg> completion = new CompletableFuture<>();
        AtomicInteger closed = new AtomicInteger();
        DefaultAgentManager manager =
                manager(
                        rc ->
                                closeableAgent(
                                        "worker",
                                        closed,
                                        Mono.defer(
                                                () -> {
                                                    entered.countDown();
                                                    return Mono.fromFuture(completion);
                                                })),
                        false);
        AgentSpawnTool tool = new AgentSpawnTool(manager, new StartingTaskRepository(), 0);
        AgentState state = AgentState.builder().build();
        RuntimeContext context = context("owner", "sync-parent");
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> spawn =
                    caller.submit(
                            () ->
                                    tool.agentSpawn(
                                                    context,
                                                    state,
                                                    "worker",
                                                    "blocking task",
                                                    "sync",
                                                    30,
                                                    null)
                                            .block(BLOCK_TIMEOUT));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            String key = onlySpawnKey(state);

            assertTrue(tool.agentRelease(context, state, key, null).contains("busy"));
            assertEquals(0, closed.get());

            completion.complete(reply("done"));
            assertTrue(spawn.get(10, TimeUnit.SECONDS).contains("status: ok"));
            assertTrue(tool.agentRelease(context, state, key, null).contains("status: released"));
            assertEquals(1, closed.get());
        } finally {
            completion.complete(reply("cleanup"));
            caller.shutdownNow();
        }
    }

    @Test
    @Timeout(20)
    void timeoutPromotedSpawnRemainsBusyUntilOriginalBridgeIsTerminal() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Msg> completion = new CompletableFuture<>();
        AtomicInteger closed = new AtomicInteger();
        DefaultAgentManager manager =
                manager(
                        rc ->
                                closeableAgent(
                                        "worker",
                                        closed,
                                        Mono.defer(
                                                () -> {
                                                    entered.countDown();
                                                    return Mono.fromFuture(completion);
                                                })),
                        false);
        try (StartingTaskRepository repository = new StartingTaskRepository()) {
            AgentSpawnTool tool = new AgentSpawnTool(manager, repository, 0);
            AgentState state = AgentState.builder().build();
            RuntimeContext context = context("owner", "promoted-parent");

            String spawn =
                    tool.agentSpawn(context, state, "worker", "blocking task", "promoted", 1, null)
                            .block(Duration.ofSeconds(5));
            String key = valueAfter(spawn, "agent_key: ");

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(spawn.contains("status: timeout_promoted"));
            assertTrue(tool.agentRelease(context, state, key, null).contains("busy"));
            assertEquals(0, closed.get());

            completion.complete(reply("done"));
            repository.awaitTerminal();
            assertTrue(tool.agentRelease(context, state, key, null).contains("status: released"));
            assertEquals(1, closed.get());
        } finally {
            completion.complete(reply("cleanup"));
        }
    }

    @Test
    @Timeout(20)
    void runningAsynchronousSpawnIsBusyUntilTerminal() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Msg> completion = new CompletableFuture<>();
        AtomicInteger closed = new AtomicInteger();
        DefaultAgentManager manager =
                manager(
                        rc ->
                                closeableAgent(
                                        "worker",
                                        closed,
                                        Mono.defer(
                                                () -> {
                                                    entered.countDown();
                                                    return Mono.fromFuture(completion);
                                                })),
                        false);
        try (StartingTaskRepository repository = new StartingTaskRepository()) {
            AgentSpawnTool tool = new AgentSpawnTool(manager, repository, 0);
            AgentState state = AgentState.builder().build();
            RuntimeContext context = context("owner", "async-parent");

            String spawn =
                    tool.agentSpawn(context, state, "worker", "blocking task", "async", 0, null)
                            .block(BLOCK_TIMEOUT);
            String key = valueAfter(spawn, "agent_key: ");
            assertTrue(entered.await(10, TimeUnit.SECONDS));

            assertTrue(tool.agentRelease(context, state, key, null).contains("busy"));
            assertEquals(0, closed.get());

            completion.complete(reply("done"));
            repository.awaitTerminal();
            assertTrue(tool.agentRelease(context, state, key, null).contains("status: released"));
            assertEquals(1, closed.get());
        } finally {
            completion.complete(reply("cleanup"));
        }
    }

    @Test
    @Timeout(30)
    void concurrentDuplicateLabelHasOneWinnerAndClosesEveryLoser() throws Exception {
        int participants = 32;
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        DefaultAgentManager manager =
                manager(
                        rc ->
                                closeableAgent(
                                        "worker-" + created.incrementAndGet(),
                                        closed,
                                        Mono.just(reply("done"))),
                        false);
        AgentSpawnTool tool = new AgentSpawnTool(manager, new StartingTaskRepository(), 0);
        AgentState state = AgentState.builder().build();
        RuntimeContext context = context("owner", "label-parent");

        List<String> results =
                concurrently(
                        participants,
                        () ->
                                tool.agentSpawn(
                                                context,
                                                state,
                                                "worker",
                                                null,
                                                "duplicate",
                                                1,
                                                null)
                                        .block(BLOCK_TIMEOUT));

        List<String> accepted =
                results.stream().filter(result -> result.contains("agent_key: ")).toList();
        assertEquals(1, accepted.size());
        assertTrue(created.get() >= 1);
        assertEquals(1, created.get() - closed.get());
        assertEquals(1, state.getToolContext().getSpawnRegistry().size());
        String key = valueAfter(accepted.get(0), "agent_key: ");
        assertTrue(tool.agentList(context, state).contains("Addressable subagents (1):"));
        assertTrue(tool.agentRelease(context, state, key, null).contains("status: released"));
        assertEquals(created.get(), closed.get());
    }

    @Test
    @Timeout(30)
    void concurrentPersistentKeyHasOneLiveWinnerAndClosesEveryLoser() throws Exception {
        int participants = 32;
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        DefaultAgentManager manager =
                manager(
                        rc ->
                                closeableAgent(
                                        "worker-" + created.incrementAndGet(),
                                        closed,
                                        Mono.just(reply("done"))),
                        true);
        AgentSpawnTool tool = new AgentSpawnTool(manager, new StartingTaskRepository(), 0);
        AgentState state = AgentState.builder().build();
        RuntimeContext context = context("owner", "persistent-parent");

        List<String> results =
                concurrently(
                        participants,
                        () ->
                                tool.agentSpawn(
                                                context,
                                                state,
                                                "worker",
                                                null,
                                                "persistent",
                                                1,
                                                null)
                                        .block(BLOCK_TIMEOUT));

        List<String> keys =
                results.stream()
                        .map(result -> valueAfter(result, "agent_key: "))
                        .distinct()
                        .toList();
        assertEquals(1, keys.size());
        assertTrue(created.get() >= 1);
        assertEquals(1, created.get() - closed.get());
        assertEquals(1, state.getToolContext().getSpawnRegistry().size());
        assertTrue(tool.agentList(context, state).contains("Addressable subagents (1):"));
        assertTrue(
                tool.agentRelease(context, state, keys.get(0), null).contains("status: released"));
        assertEquals(created.get(), closed.get());
    }

    @Test
    @Timeout(20)
    void sendReleaseRaceNeverClosesAnAgentWithAnActiveSend() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Msg> sendCompletion = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        DefaultAgentManager manager =
                manager(
                        rc ->
                                closeableAgent(
                                        "worker",
                                        closed,
                                        Mono.defer(
                                                () -> {
                                                    if (calls.incrementAndGet() == 1) {
                                                        return Mono.just(reply("initial"));
                                                    }
                                                    entered.countDown();
                                                    return Mono.fromFuture(sendCompletion);
                                                })),
                        false);
        AgentSpawnTool tool = new AgentSpawnTool(manager, new StartingTaskRepository(), 0);
        AgentState state = AgentState.builder().build();
        RuntimeContext context = context("owner", "send-parent");
        String spawn =
                tool.agentSpawn(context, state, "worker", "initial", "racy", 30, null)
                        .block(BLOCK_TIMEOUT);
        String key = valueAfter(spawn, "agent_key: ");
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> send =
                    caller.submit(
                            () ->
                                    tool.agentSend(context, state, key, null, "follow-up", 30)
                                            .block(BLOCK_TIMEOUT));
            assertTrue(entered.await(10, TimeUnit.SECONDS));

            assertTrue(tool.agentRelease(context, state, key, null).contains("busy"));
            assertEquals(0, closed.get());

            sendCompletion.complete(reply("follow-up done"));
            assertTrue(send.get(10, TimeUnit.SECONDS).contains("follow-up done"));
            assertTrue(tool.agentRelease(context, state, key, null).contains("status: released"));
            assertEquals(1, closed.get());
        } finally {
            sendCompletion.complete(reply("cleanup"));
            caller.shutdownNow();
        }
    }

    @Test
    void releaseRevokesExposedGatewayHandleExactlyOnce() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        DefaultAgentManager manager =
                manager(rc -> closeableAgent("worker", closed, Mono.just(reply("done"))), false);
        SubagentGatewayBridge bridge = mock(SubagentGatewayBridge.class);
        when(bridge.expose(any(), any(), any(), any()))
                .thenReturn(new SubagentGatewayBridge.ExposeResult("public-handle"));
        AgentSpawnTool tool = new AgentSpawnTool(manager, new StartingTaskRepository(), 0, bridge);
        AgentState state = AgentState.builder().build();
        RuntimeContext context = context("owner", "gateway-parent");

        String spawn =
                tool.agentSpawn(context, state, "worker", null, null, 1, true).block(BLOCK_TIMEOUT);
        String key = valueAfter(spawn, "agent_key: ");
        assertTrue(tool.agentRelease(context, state, key, null).contains("status: released"));

        verify(bridge, times(1)).revoke("public-handle");
        assertEquals(1, closed.get());
    }

    private static DefaultAgentManager manager(
            io.agentscope.harness.agent.subagent.SubagentFactory factory, boolean persistent) {
        if (!persistent) {
            return new DefaultAgentManager(
                    List.of(new SubagentEntry("worker", "worker", factory)), null);
        }
        SubagentDeclaration declaration =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("worker")
                        .inlineAgentsBody("worker")
                        .persistSession(true)
                        .build();
        return new DefaultAgentManager(
                List.of(new SubagentEntry("worker", "worker", factory, declaration)), null);
    }

    private static Agent closeableAgent(String id, AtomicInteger closeCount, Mono<Msg> response) {
        Agent agent = mock(Agent.class, withSettings().extraInterfaces(AutoCloseable.class));
        when(agent.getAgentId()).thenReturn(id);
        when(agent.getName()).thenReturn(id);
        when(agent.call(anyList())).thenReturn(response);
        try {
            doAnswer(
                            invocation -> {
                                closeCount.incrementAndGet();
                                return null;
                            })
                    .when((AutoCloseable) agent)
                    .close();
        } catch (Exception impossibleForMockitoStub) {
            throw new AssertionError(impossibleForMockitoStub);
        }
        return agent;
    }

    private static Msg reply(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }

    private static RuntimeContext context(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    private static String onlySpawnKey(AgentState state) {
        assertEquals(1, state.getToolContext().getSpawnRegistry().size());
        return state.getToolContext().getSpawnRegistry().keySet().iterator().next();
    }

    private static String valueAfter(String text, String prefix) {
        return text.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing '" + prefix + "' in: " + text));
    }

    private static <T> List<T> concurrently(int participants, Callable<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < participants; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    assertTrue(ready.await(10, TimeUnit.SECONDS));
                                    assertTrue(start.await(10, TimeUnit.SECONDS));
                                    return action.call();
                                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>(participants);
            for (Future<T> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static final class StartingTaskRepository implements TaskRepository, AutoCloseable {

        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final Map<String, BackgroundTask> tasks = new ConcurrentHashMap<>();
        private final List<CompletableFuture<String>> futures = new CopyOnWriteArrayList<>();

        @Override
        public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
            return tasks.get(scoped(sessionId, taskId));
        }

        @Override
        public BackgroundTask putTask(
                RuntimeContext rc,
                String taskId,
                String subAgentId,
                String sessionId,
                TaskRunSpec spec) {
            CompletableFuture<String> future;
            if (spec instanceof TaskRunSpec.LocalTaskRunSpec local) {
                future = CompletableFuture.supplyAsync(local.execution(), executor);
            } else if (spec instanceof TaskRunSpec.AdoptedTaskRunSpec adopted) {
                future = adopted.future();
            } else {
                throw new AssertionError("Expected a local or adopted task, got: " + spec);
            }
            futures.add(future);
            BackgroundTask task = new BackgroundTask(taskId, subAgentId, future);
            tasks.put(scoped(sessionId, taskId), task);
            return task;
        }

        @Override
        public Collection<BackgroundTask> listTasks(
                RuntimeContext rc, String sessionId, TaskStatus filter) {
            String prefix = sessionId + "\u0000";
            return tasks.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .map(Map.Entry::getValue)
                    .filter(task -> filter == null || task.getTaskStatus() == filter)
                    .toList();
        }

        @Override
        public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
            BackgroundTask task = getTask(rc, sessionId, taskId);
            return task != null && task.cancel(true);
        }

        void awaitTerminal() throws Exception {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }

        private static String scoped(String sessionId, String taskId) {
            return sessionId + "\u0000" + taskId;
        }
    }
}
