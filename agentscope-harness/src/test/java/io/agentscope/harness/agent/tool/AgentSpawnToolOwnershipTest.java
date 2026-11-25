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
package io.agentscope.harness.agent.tool;

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.harness.agent.gateway.SubagentGatewayBridge;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentExecution;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

class AgentSpawnToolOwnershipTest {
    private final AtomicInteger creations = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private Runnable beforeCreate = () -> {};
    private final DefaultAgentManager manager =
            new DefaultAgentManager(
                    List.of(
                            new SubagentEntry(
                                    "worker",
                                    "worker",
                                    rc -> {
                                        creations.incrementAndGet();
                                        beforeCreate.run();
                                        var child = mock(Agent.class);
                                        when(child.call(anyList()))
                                                .thenAnswer(
                                                        invocation -> {
                                                            calls.incrementAndGet();
                                                            return Mono.just(
                                                                    new AssistantMessage(
                                                                            "child result"));
                                                        });
                                        return child;
                                    },
                                    SubagentDeclaration.builder()
                                            .name("worker")
                                            .description("worker")
                                            .persistSession(true)
                                            .build())),
                    null);

    private AgentSpawnTool tool() {
        return new AgentSpawnTool(manager, mock(TaskRepository.class), 0);
    }

    private static RuntimeContext context(String user, String parent) {
        return RuntimeContext.builder()
                .userId(user)
                .sessionId(parent)
                .agentState(AgentState.builder().userId(user).sessionId(parent).build())
                .build();
    }

    private static String spawn(AgentSpawnTool tool, RuntimeContext rc) {
        return assertText(
                        tool.agentSpawn(rc, rc.getAgentState(), "worker", null, "work", 5, null)
                                .block(),
                        ToolResultState.SUCCESS)
                .lines()
                .findFirst()
                .orElseThrow()
                .substring("agent_key: ".length());
    }

    private static String send(
            AgentSpawnTool tool, RuntimeContext rc, String key, ToolResultState state) {
        return assertText(
                tool.agentSend(rc, rc.getAgentState(), key, null, "continue", 5).block(), state);
    }

    @Test
    void legacyFourArgumentBridgeStillReceivesContextAwareSpawn() {
        var tool = tool();
        var owner = context("alice", "parent-a");
        var exposures = new AtomicInteger();
        SubagentGatewayBridge legacy =
                (agentId, sessionId, agent, replyTo) -> {
                    assertEquals("worker", agentId);
                    assertTrue(sessionId.startsWith("sub-"));
                    exposures.incrementAndGet();
                    return new SubagentGatewayBridge.ExposeResult("legacy-handle");
                };
        tool.setGatewayBridge(legacy);
        assertTrue(
                assertText(
                                tool.agentSpawn(owner, null, "worker", null, "work", 5, true)
                                        .block(),
                                ToolResultState.SUCCESS)
                        .contains("subagent_id: legacy-handle"));
        assertEquals(1, exposures.get());
        assertEquals(1, creations.get());
        assertEquals(0, calls.get());
    }

    @Test
    void concurrentFirstSpawnPublishesRegistryBeforeReuseAndCannotReviveInvalidatedEntry()
            throws Exception {
        var tool = tool();
        var owner = context("alice", "parent-a");
        var state = owner.getAgentState();
        var creationStarted = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        beforeCreate =
                () -> {
                    creationStarted.countDown();
                    try {
                        assertTrue(resume.await(30, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                };
        var executor = Executors.newFixedThreadPool(8);
        try {
            Future<String> first = executor.submit(() -> spawn(tool, owner));
            assertTrue(creationStarted.await(10, TimeUnit.SECONDS));
            assertTrue(state.getToolContext().getSpawnRegistry().isEmpty());
            assertTrue(cache(tool, "agentsByKey").isEmpty());

            List<Future<String>> reuses = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                reuses.add(
                        executor.submit(
                                () ->
                                        spawn(
                                                tool,
                                                RuntimeContext.builder(owner)
                                                        .agentState(state)
                                                        .build())));
            }
            resume.countDown();
            String key = first.get(10, TimeUnit.SECONDS);
            for (Future<String> reuse : reuses) {
                assertEquals(key, reuse.get(10, TimeUnit.SECONDS));
            }
            assertTrue(state.getToolContext().getSpawnRegistry().containsKey(key));
            assertTrue(cache(tool, "labelToKey").containsValue(key));
            assertEquals(1, creations.get(), "all eight successful spawns share one factory call");
            assertEquals(1, state.getToolContext().getSpawnRegistry().size());

            state.getToolContext().removeSpawnEntry(key);
            var before = snapshot(tool, state);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ToolResultBlock>> rejected = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                rejected.add(
                        executor.submit(
                                () -> {
                                    assertTrue(start.await(10, TimeUnit.SECONDS));
                                    return tool.agentSpawn(
                                                    RuntimeContext.builder(owner).build(),
                                                    state,
                                                    "worker",
                                                    null,
                                                    "work",
                                                    5,
                                                    null)
                                            .block();
                                }));
            }
            start.countDown();
            for (Future<ToolResultBlock> rejection : rejected) {
                assertEquals(
                        ToolResultBlock.error("Unknown agent_key."),
                        rejection.get(10, TimeUnit.SECONDS));
            }
            assertEquals(before, snapshot(tool, state));
            assertEquals(1, creations.get(), "concurrent invalidation must not create or revive");
            assertEquals(0, calls.get());
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @CsvSource({"bob,parent-a", "alice,parent-b"})
    void cachedKeysAndLabelsAreInvisibleToOtherOwners(String user, String parent) {
        var tool = tool();
        var owner = context("alice", "parent-a");
        String key = spawn(tool, owner);
        var other = context(user, parent);
        String unknown = send(tool, other, "agent:worker:unknown", ToolResultState.ERROR);
        assertEquals(unknown, send(tool, other, key, ToolResultState.ERROR));
        assertEquals(
                "No addressable subagents.",
                assertText(tool.agentList(other), ToolResultState.SUCCESS));
        assertEquals(
                "No addressable subagents.", assertText(tool.agentList(), ToolResultState.SUCCESS));
        assertFalse(assertText(tool.agentList(other), ToolResultState.SUCCESS).contains(key));
        assertEquals(
                "Error: Unknown label: work",
                assertText(
                        tool.agentSend(other, other.getAgentState(), null, "work", "continue", 5)
                                .block(),
                        ToolResultState.ERROR));

        // Even a mismatched state object cannot lend the other caller ownership of a cached key.
        other.getAgentState()
                .getToolContext()
                .putSpawnEntry(
                        key, owner.getAgentState().getToolContext().getSpawnRegistry().get(key));
        assertEquals(unknown, send(tool, other, key, ToolResultState.ERROR));
        assertEquals(
                "No addressable subagents.",
                assertText(tool.agentList(other), ToolResultState.SUCCESS));
        assertTrue(send(tool, owner, key, ToolResultState.SUCCESS).contains("child result"));
        assertTrue(assertText(tool.agentList(owner), ToolResultState.SUCCESS).contains(key));
        assertEquals(1, creations.get());
        assertEquals(1, calls.get());
    }

    @Test
    void cacheHitRequiresMatchingParentRegistryAndMissingStateFailsClosed() {
        var tool = tool();
        var owner = context("alice", "parent-a");
        String key = spawn(tool, owner);
        var entry = owner.getAgentState().getToolContext().getSpawnRegistry().get(key);
        var missing = context("alice", "parent-a");
        String unknown = send(tool, missing, "unknown", ToolResultState.ERROR);
        assertEquals(unknown, send(tool, missing, key, ToolResultState.ERROR));
        assertEquals(
                "No addressable subagents.",
                assertText(tool.agentList(missing), ToolResultState.SUCCESS));
        missing.setAgentState(null);
        assertEquals(unknown, send(tool, missing, key, ToolResultState.ERROR));
        owner.getAgentState()
                .getToolContext()
                .putSpawnEntry(
                        key,
                        new ToolContextState.SpawnEntry(
                                key, entry.agentId(), "wrong-child", entry.label(), entry.depth()));
        assertEquals(unknown, send(tool, owner, key, ToolResultState.ERROR));
        assertEquals(
                "No addressable subagents.",
                assertText(tool.agentList(owner), ToolResultState.SUCCESS));
        owner.getAgentState().getToolContext().putSpawnEntry(key, entry);
        assertTrue(send(tool, owner, key, ToolResultState.SUCCESS).contains("child result"));
        assertEquals(1, calls.get());
    }

    @Test
    void persistedRegistryRestoresCurrentOwnerAndDoesNotOverwriteAnotherOwnerCache() {
        var original = tool();
        var owner = context("alice", "parent-a");
        String key = spawn(original, owner);
        var restarted = tool();
        assertTrue(send(restarted, owner, key, ToolResultState.SUCCESS).contains("child result"));
        assertTrue(assertText(restarted.agentList(owner), ToolResultState.SUCCESS).contains(key));
        assertTrue(
                assertText(
                                restarted
                                        .agentSend(
                                                owner,
                                                owner.getAgentState(),
                                                null,
                                                "work",
                                                "again",
                                                5)
                                        .block(),
                                ToolResultState.SUCCESS)
                        .contains("child result"));
        var other = context("bob", "parent-b");
        other.getAgentState()
                .getToolContext()
                .putSpawnEntry(
                        key, owner.getAgentState().getToolContext().getSpawnRegistry().get(key));
        assertEquals(
                send(restarted, other, "unknown", ToolResultState.ERROR),
                send(restarted, other, key, ToolResultState.ERROR));
        assertTrue(send(restarted, owner, key, ToolResultState.SUCCESS).contains("child result"));
        assertEquals(2, creations.get(), "restore must not replace another owner's cached child");
        assertEquals(3, calls.get());
    }

    private record SpawnSnapshot(
            Map<?, ?> agents,
            Map<?, ?> labels,
            Map<String, ToolContextState.SpawnEntry> registry) {}

    private static Map<?, ?> cache(AgentSpawnTool tool, String fieldName)
            throws ReflectiveOperationException {
        var registries = AgentSpawnTool.class.getDeclaredField("registries");
        registries.setAccessible(true);
        var scopes = (Map<?, ?>) registries.get(tool);
        if (scopes.isEmpty()) {
            return Map.of();
        }
        assertEquals(1, scopes.size());
        var scope = scopes.values().iterator().next();
        var field = scope.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(scope);
    }

    private static SpawnSnapshot snapshot(AgentSpawnTool tool, AgentState state)
            throws ReflectiveOperationException {
        return new SpawnSnapshot(
                Map.copyOf(cache(tool, "agentsByKey")),
                Map.copyOf(cache(tool, "labelToKey")),
                state == null ? Map.of() : state.getToolContext().getSpawnRegistry());
    }

    @ParameterizedTest
    @CsvSource({"alice,parent-b", "bob,parent-a"})
    void initialSpawnRejectsForeignParentStateBeforeAnySideEffects(String user, String parent)
            throws Exception {
        var tool = tool();
        var owner = context("alice", "parent-a");
        var other = context(user, parent);
        var state = owner.getAgentState();
        var before = snapshot(tool, state);
        var otherBefore = snapshot(tool, other.getAgentState());
        assertEquals(
                ToolResultBlock.error("Unknown agent_key."),
                tool.agentSpawn(other, state, "worker", "initial", "work", 5, null).block());
        assertEquals(before, snapshot(tool, state));
        assertEquals(otherBefore, snapshot(tool, other.getAgentState()));
        assertEquals(
                ToolResultBlock.error("Unknown agent_key."),
                tool.agentSpawn(
                                RuntimeContext.builder(other).agentState(state).build(),
                                null,
                                "worker",
                                "initial",
                                "work",
                                5,
                                null)
                        .block());
        assertEquals(before, snapshot(tool, state));
        assertEquals(otherBefore, snapshot(tool, other.getAgentState()));
        assertEquals(0, creations.get(), "unauthorized initial spawn must not call the factory");
        assertEquals(0, calls.get());

        String key = spawn(tool, owner);
        assertTrue(assertText(tool.agentList(owner), ToolResultState.SUCCESS).contains(key));
        assertEquals(1, creations.get());
        var registry = state.getToolContext().getSpawnRegistry();
        var restarted = tool();
        assertTrue(send(restarted, owner, key, ToolResultState.SUCCESS).contains("child result"));
        assertTrue(assertText(restarted.agentList(owner), ToolResultState.SUCCESS).contains(key));
        assertEquals(registry, state.getToolContext().getSpawnRegistry());
        assertEquals(
                2, creations.get(), "the original owner must still be able to spawn and restore");
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @CsvSource(
            value = {"bob,parent-a", "alice,parent-b", "NULL,parent-a", "alice,NULL", "alice,''"},
            nullValues = "NULL")
    void coldRestoreRejectsForeignStateBeforeMaterializingOrCaching(String user, String parent)
            throws Exception {
        var owner = context("alice", "parent-a");
        String key = spawn(tool(), owner);
        var restarted = tool();
        var other = context(user, parent);
        var before = snapshot(restarted, owner.getAgentState());
        ToolResultBlock unknown =
                restarted
                        .agentSend(other, owner.getAgentState(), "unknown", null, "next", 5)
                        .block();
        assertText(unknown, ToolResultState.ERROR);
        assertEquals(
                unknown,
                restarted.agentSend(other, owner.getAgentState(), key, null, "next", 5).block());
        assertEquals(
                ToolResultBlock.error("Unknown label: work"),
                restarted.agentSend(other, owner.getAgentState(), null, "work", "next", 5).block());
        assertEquals(before, snapshot(restarted, owner.getAgentState()));
        assertTrue(other.getAgentState().getToolContext().getSpawnRegistry().isEmpty());
        assertEquals(1, creations.get(), "unauthorized restore must not call the factory");
        assertEquals(0, calls.get());

        assertTrue(send(restarted, owner, key, ToolResultState.SUCCESS).contains("child result"));
        assertTrue(assertText(restarted.agentList(owner), ToolResultState.SUCCESS).contains(key));
        assertEquals(before.registry(), owner.getAgentState().getToolContext().getSpawnRegistry());
        assertEquals(2, creations.get(), "the original owner must still be able to restore");
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @CsvSource({"bob,parent-a", "alice,parent-b"})
    void cachedAccessAlsoChecksSuppliedParentStateIdentity(String user, String parent)
            throws Exception {
        var tool = tool();
        var owner = context("alice", "parent-a");
        String key = spawn(tool, owner);
        var foreignState = context(user, parent).getAgentState();
        foreignState
                .getToolContext()
                .putSpawnEntry(
                        key, owner.getAgentState().getToolContext().getSpawnRegistry().get(key));
        var before = snapshot(tool, foreignState);
        ToolResultBlock unknown =
                tool.agentSend(owner, foreignState, "unknown", null, "next", 5).block();
        assertText(unknown, ToolResultState.ERROR);
        assertEquals(unknown, tool.agentSend(owner, foreignState, key, null, "next", 5).block());
        assertEquals(unknown, tool.agentRelease(owner, foreignState, key, null));
        assertEquals(
                unknown,
                tool.agentSpawn(owner, foreignState, "worker", "next", "work", 5, null).block());
        assertEquals(
                ToolResultBlock.error("Unknown label: work"),
                tool.agentSend(owner, foreignState, null, "work", "next", 5).block());
        assertEquals(
                "No addressable subagents.",
                assertText(
                        tool.agentList(
                                RuntimeContext.builder(owner).agentState(foreignState).build()),
                        ToolResultState.SUCCESS));
        assertEquals(before, snapshot(tool, foreignState));
        assertEquals(1, creations.get());
        assertEquals(0, calls.get());
        assertTrue(send(tool, owner, key, ToolResultState.SUCCESS).contains("child result"));
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "key", "agent", "session", "label", "depth"})
    void persistentReuseCannotRepairMissingOrMismatchedRegistry(String mismatch) throws Exception {
        var tool = tool();
        var owner = context("alice", "parent-a");
        String key = spawn(tool, owner);
        var state = owner.getAgentState();
        var entry = state.getToolContext().getSpawnRegistry().get(key);
        state.getToolContext().removeSpawnEntry(key);
        if (!mismatch.equals("missing")) {
            state.getToolContext()
                    .putSpawnEntry(
                            key,
                            new ToolContextState.SpawnEntry(
                                    mismatch.equals("key") ? "wrong-key" : entry.key(),
                                    mismatch.equals("agent") ? "wrong-agent" : entry.agentId(),
                                    mismatch.equals("session") ? "wrong-child" : entry.sessionId(),
                                    mismatch.equals("label") ? "wrong-label" : entry.label(),
                                    mismatch.equals("depth") ? entry.depth() + 1 : entry.depth()));
        }
        // Simulate an evicted label index too: a rejected reuse must not rebuild either index.
        cache(tool, "labelToKey").clear();
        var before = snapshot(tool, state);
        ToolResultBlock unknown = tool.agentSend(owner, state, "unknown", null, "next", 5).block();
        assertText(unknown, ToolResultState.ERROR);
        assertEquals(
                unknown, tool.agentSpawn(owner, state, "worker", "next", "work", 5, null).block());
        assertEquals(before, snapshot(tool, state));
        assertEquals(
                unknown, tool.agentSpawn(owner, state, "worker", null, "work", 5, null).block());
        assertEquals(unknown, tool.agentSend(owner, state, key, null, "next", 5).block());
        assertEquals(before, snapshot(tool, state));
        assertEquals(1, creations.get());
        assertEquals(0, calls.get(), "a stale cached child must not be executed");

        state.getToolContext().putSpawnEntry(key, entry);
        assertTrue(
                assertText(
                                tool.agentSpawn(owner, state, "worker", null, "work", 5, null)
                                        .block(),
                                ToolResultState.SUCCESS)
                        .contains("status: accepted (reused)"));
        assertEquals(0, calls.get());
        assertTrue(
                assertText(
                                tool.agentSpawn(owner, state, "worker", "next", "work", 5, null)
                                        .block(),
                                ToolResultState.SUCCESS)
                        .contains("child result"));
        assertEquals(Map.of(key, entry), state.getToolContext().getSpawnRegistry());
        assertEquals(1, creations.get(), "legitimate reuse must keep the existing child");
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"anon", "null", ""})
    void anonymousAndLiteralUserIdentitiesSupportRestoreAndReuseWithoutNormalization(String user)
            throws Exception {
        var owner = context(user, "parent-a");
        var original = tool();
        String key = spawn(original, owner);
        assertTrue(
                assertText(
                                original.agentSpawn(owner, null, "worker", "reuse", "work", 5, null)
                                        .block(),
                                ToolResultState.SUCCESS)
                        .contains("child result"));
        var restarted = tool();
        var other = context(user == null ? "anon" : null, "parent-a");
        var before = snapshot(restarted, owner.getAgentState());
        assertEquals(
                ToolResultBlock.error("Unknown agent_key."),
                restarted.agentSend(other, owner.getAgentState(), key, null, "next", 5).block());
        assertEquals(before, snapshot(restarted, owner.getAgentState()));
        assertEquals(1, creations.get());
        assertEquals(1, calls.get());
        assertTrue(send(restarted, owner, key, ToolResultState.SUCCESS).contains("child result"));
        assertEquals(2, creations.get());
        assertEquals(2, calls.get());
    }

    @Test
    void missingContextCannotBorrowStateIdentityButInitialDirectSpawnRemainsAvailable()
            throws Exception {
        var owner = context("alice", "parent-a");
        String key = spawn(tool(), owner);
        var restarted = tool();
        var before = snapshot(restarted, owner.getAgentState());
        assertEquals(
                ToolResultBlock.error("Unknown agent_key."),
                restarted.agentSend(null, owner.getAgentState(), key, null, "next", 5).block());
        assertEquals(before, snapshot(restarted, owner.getAgentState()));
        assertEquals(1, creations.get());
        assertEquals(0, calls.get());
        assertTrue(send(restarted, owner, key, ToolResultState.SUCCESS).contains("child result"));

        var anonymousState = AgentState.builder().build();
        var direct = tool();
        String directSpawn =
                assertText(
                        direct.agentSpawn(
                                        null, anonymousState, "worker", "initial", "work", 5, null)
                                .block(),
                        ToolResultState.SUCCESS);
        assertTrue(directSpawn.contains("child result"));
        String directKey =
                directSpawn.lines().findFirst().orElseThrow().substring("agent_key: ".length());
        var directBefore = snapshot(direct, anonymousState);
        var directRestarted = tool();
        var coldBefore = snapshot(directRestarted, anonymousState);
        for (RuntimeContext missing : new RuntimeContext[] {null, RuntimeContext.empty()}) {
            assertEquals(
                    ToolResultBlock.error("Unknown agent_key."),
                    direct.agentSend(missing, anonymousState, directKey, null, "next", 5).block());
            assertEquals(
                    ToolResultBlock.error("Unknown agent_key."),
                    direct.agentSpawn(missing, anonymousState, "worker", "next", "work", 5, null)
                            .block());
            assertEquals(directBefore, snapshot(direct, anonymousState));
            assertEquals(
                    ToolResultBlock.error("Unknown agent_key."),
                    directRestarted
                            .agentSend(missing, anonymousState, directKey, null, "next", 5)
                            .block());
            assertEquals(coldBefore, snapshot(directRestarted, anonymousState));
        }
        assertEquals(3, creations.get(), "missing identity must not materialize a continuation");
        assertEquals(2, calls.get());
    }

    @Test
    void hostOwnedChildUsesHostContractWithoutRegistryButStillChecksOwner() throws Exception {
        var hostCalls = new AtomicInteger();
        var host =
                new SubagentExecution() {
                    @Override
                    public String childSessionId(RuntimeContext parent) {
                        return "host-child";
                    }

                    @Override
                    public boolean ownsChildState() {
                        return true;
                    }

                    @Override
                    public Mono<Msg> invoke(
                            Agent agent,
                            RuntimeContext parent,
                            RuntimeContext child,
                            Supplier<Mono<Msg>> localInvocation) {
                        assertEquals("host-child", child.getSessionId());
                        hostCalls.incrementAndGet();
                        return Mono.just(new AssistantMessage("host result"));
                    }
                };
        var tool = tool();
        var owner =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("parent-a")
                        .put(SubagentExecution.class, host)
                        .build();
        String key = spawn(tool, owner);
        assertTrue(send(tool, owner, key, ToolResultState.SUCCESS).contains("host result"));
        assertTrue(
                assertText(
                                tool.agentSpawn(owner, null, "worker", null, "work", 5, null)
                                        .block(),
                                ToolResultState.SUCCESS)
                        .contains("status: accepted (reused)"));
        assertTrue(
                assertText(
                                tool.agentSpawn(owner, null, "worker", "next", "work", 5, null)
                                        .block(),
                                ToolResultState.SUCCESS)
                        .contains("host result"));
        assertTrue(assertText(tool.agentList(owner), ToolResultState.SUCCESS).contains(key));
        var before = snapshot(tool, null);
        for (RuntimeContext other :
                List.of(
                        RuntimeContext.builder(owner).userId("bob").build(),
                        RuntimeContext.builder(owner).sessionId("parent-b").build())) {
            ToolResultBlock unknown =
                    tool.agentSend(other, null, "unknown", null, "next", 5).block();
            assertText(unknown, ToolResultState.ERROR);
            assertEquals(unknown, tool.agentSend(other, null, key, null, "next", 5).block());
            assertEquals(
                    "No addressable subagents.",
                    assertText(tool.agentList(other), ToolResultState.SUCCESS));
            assertEquals(before, snapshot(tool, null));
        }
        assertEquals(1, creations.get());
        assertEquals(2, hostCalls.get());
        assertEquals(0, calls.get(), "host continuation must not invoke prototype state");
    }
}
