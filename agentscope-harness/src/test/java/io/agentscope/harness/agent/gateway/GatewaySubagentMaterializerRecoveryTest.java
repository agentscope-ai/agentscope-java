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
package io.agentscope.harness.agent.gateway;

import static io.agentscope.core.message.ToolResultState.SUCCESS;
import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware;
import io.agentscope.harness.agent.middleware.SubagentsMiddleware;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Exercises production recovery wiring without starting an agent turn or invoking a model. */
class GatewaySubagentMaterializerRecoveryTest {

    private enum GatewayPath {
        INTERNAL,
        BOOTSTRAP
    }

    @TempDir Path workspace;

    private final Model model = mock(Model.class);
    private final BaseStore baseStore = new InMemoryStore();
    private final DistributedStore store =
            DistributedStore.builder()
                    .baseStore(baseStore)
                    .agentStateStore(new InMemoryAgentStateStore())
                    .build();
    private final List<HarnessAgent> createdAgents = new ArrayList<>();

    @AfterEach
    void closeAgentsAndVerifyNoModelRun() {
        createdAgents.forEach(HarnessAgent::close);
        verify(model, never()).stream(anyList(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(GatewayPath.class)
    void spawnedExposurePersistsTenantIdentityAcrossColdGatewayRecovery(GatewayPath path)
            throws Exception {
        RemoteFilesystem filesystem =
                new RemoteFilesystem(
                        baseStore,
                        rc ->
                                List.of(
                                        "users",
                                        rc.getUserId() != null ? rc.getUserId() : "anonymous"));
        HarnessAgent owner = own(builder("dynamic").abstractFilesystem(filesystem).build());
        HarnessGateway live = gateway(path, owner);
        RuntimeContext alice = parentContext("alice");
        RuntimeContext bob = parentContext("bob");
        assertTrue(
                filesystem
                        .write(
                                alice,
                                "/subagents/worker.md",
                                "---\ndescription: Alice worker\n---\nAlice instructions")
                        .isSuccess());
        assertTrue(
                filesystem
                        .write(
                                bob,
                                "/subagents/worker.md",
                                "---\ndescription: Bob worker\n---\nBob instructions")
                        .isSuccess());

        // Exercise the registered agent_spawn and production bridge, not hand-made records.
        SubagentRecord a = spawnExposure(owner, alice);
        SubagentRecord b = spawnExposure(owner, bob);
        assertEquals("alice", a.userId());
        assertEquals("bob", b.userId());
        HarnessAgent liveA = assertInstanceOf(HarnessAgent.class, recover(live, a.subagentId()));
        HarnessAgent liveB = assertInstanceOf(HarnessAgent.class, recover(live, b.subagentId()));
        assertEquals("Alice worker", liveA.getDescription());
        assertEquals("Bob worker", liveB.getDescription());

        // A new owner and gateway have neither the live agents nor their declaration snapshots.
        HarnessAgent restarted = own(builder("dynamic").abstractFilesystem(filesystem).build());
        var baseline = restarted.getSubagentAgentManager();
        HarnessGateway cold = gateway(path, restarted);
        assertFalse(baseline.hasAgent("worker"));
        assertTrue(
                filesystem
                        .edit(
                                alice,
                                "/subagents/worker.md",
                                "Alice worker",
                                "Alice worker v2",
                                false)
                        .isSuccess());
        HarnessAgent coldA = assertInstanceOf(HarnessAgent.class, recover(cold, a.subagentId()));
        HarnessAgent coldB = assertInstanceOf(HarnessAgent.class, recover(cold, b.subagentId()));
        assertEquals("Alice worker v2", coldA.getDescription());
        assertEquals("Bob worker", coldB.getDescription());
        assertTrue(coldA.getDelegate().getSysPrompt().contains("Alice instructions"));
        assertFalse(coldA.getDelegate().getSysPrompt().contains("Bob instructions"));
        assertTrue(coldB.getDelegate().getSysPrompt().contains("Bob instructions"));
        assertFalse(coldB.getDelegate().getSysPrompt().contains("Alice instructions"));
        assertEquals("worker@" + a.sessionId() + "#alice", coldA.getDefaultSessionId());
        assertEquals("worker@" + b.sessionId() + "#bob", coldB.getDefaultSessionId());
        assertSame(coldA, recover(cold, a.subagentId()));
        assertSame(coldB, recover(cold, b.subagentId()));
        assertSame(baseline, restarted.getSubagentAgentManager());
        assertFalse(baseline.hasAgent("worker"));
    }

    @ParameterizedTest
    @EnumSource(GatewayPath.class)
    void anonymousSpawnExposureRetainsAnonymousColdRecovery(GatewayPath path) throws Exception {
        Agent child = mock(Agent.class);
        List<RuntimeContext> seenContexts = new ArrayList<>();
        HarnessAgent owner =
                own(
                        builder("anonymous")
                                .disableDynamicSubagents()
                                .subagentFactory(
                                        SubagentDeclaration.builder()
                                                .name("worker")
                                                .description("Worker")
                                                .build(),
                                        rc -> {
                                            seenContexts.add(rc);
                                            return child;
                                        })
                                .build());
        HarnessGateway live = gateway(path, owner);
        RuntimeContext anonymous = parentContext(null);
        SubagentRecord record = spawnExposure(owner, anonymous);
        assertNull(record.userId());
        assertSame(child, recover(live, record.subagentId()));
        assertEquals(1, seenContexts.size());

        HarnessGateway cold =
                GatewayBootstrap.builder()
                        .agent("owner", owner)
                        .distributedStore(store)
                        .build()
                        .gateway();
        assertSame(child, recover(cold, record.subagentId()));
        assertEquals(2, seenContexts.size());
        assertNull(seenContexts.get(1).getUserId());
        assertEquals(record.sessionId(), seenContexts.get(1).getSessionId());
        String legacyHandle = live.exposeSubagent("worker", "legacy-child", child, null);
        assertNull(
                new StoreBackedSubagentRegistry(baseStore)
                        .find(legacyHandle)
                        .orElseThrow()
                        .userId());
        verify(child, never()).call(anyList());
    }

    @ParameterizedTest
    @EnumSource(GatewayPath.class)
    void dynamicRegistryRecoveryReloadsSameNameDeclarationsPerUser(GatewayPath path)
            throws Exception {
        RemoteFilesystem filesystem =
                new RemoteFilesystem(
                        baseStore,
                        rc ->
                                List.of(
                                        "users",
                                        rc.getUserId() != null ? rc.getUserId() : "anonymous"));
        HarnessAgent owner = own(builder("dynamic").abstractFilesystem(filesystem).build());
        var baseline = owner.getSubagentAgentManager();
        assertFalse(baseline.hasAgent("worker"));
        HarnessGateway gateway = gateway(path, owner);

        // Declarations arrive after gateway initialization; only the registry carries identity.
        RuntimeContext alice = context("alice", "child-a");
        RuntimeContext bob = context("bob", "child-b");
        assertTrue(
                filesystem
                        .write(
                                alice,
                                "/subagents/worker.md",
                                "---\ndescription: Alice worker\n---\nAlice instructions")
                        .isSuccess());
        assertTrue(
                filesystem
                        .write(
                                bob,
                                "/subagents/worker.md",
                                "---\ndescription: Bob worker\n---\nBob instructions")
                        .isSuccess());
        register("a1", "worker", alice);
        register("b1", "worker", bob);
        register("a2", "worker", alice);
        register("b2", "worker", bob);

        HarnessAgent a1 = assertInstanceOf(HarnessAgent.class, recover(gateway, "a1"));
        HarnessAgent b1 = assertInstanceOf(HarnessAgent.class, recover(gateway, "b1"));
        assertEquals("Alice worker", a1.getDescription());
        assertEquals("Bob worker", b1.getDescription());
        assertTrue(a1.getDelegate().getSysPrompt().contains("Alice instructions"));
        assertFalse(a1.getDelegate().getSysPrompt().contains("Bob instructions"));
        assertTrue(b1.getDelegate().getSysPrompt().contains("Bob instructions"));
        assertFalse(b1.getDelegate().getSysPrompt().contains("Alice instructions"));
        assertEquals("worker@child-a#alice", a1.getDefaultSessionId());
        assertEquals("worker@child-b#bob", b1.getDefaultSessionId());

        assertTrue(
                filesystem
                        .edit(
                                alice,
                                "/subagents/worker.md",
                                "Alice worker",
                                "Alice worker v2",
                                false)
                        .isSuccess());
        HarnessAgent a2 = assertInstanceOf(HarnessAgent.class, recover(gateway, "a2"));
        HarnessAgent b2 = assertInstanceOf(HarnessAgent.class, recover(gateway, "b2"));
        assertEquals("Alice worker v2", a2.getDescription());
        assertEquals("Bob worker", b2.getDescription());
        assertSame(a1, recover(gateway, "a1"));
        assertSame(b1, recover(gateway, "b1"));
        assertEquals("Alice worker", a1.getDescription(), "cached agents keep their snapshot");
        assertSame(baseline, owner.getSubagentAgentManager());
        assertFalse(baseline.hasAgent("worker"), "recovery must not mutate the shared baseline");

        assertTrue(
                filesystem
                        .write(
                                alice,
                                "/subagents/primary.md",
                                "---\ndescription: Primary only\nmode: primary\n---\nPrimary")
                        .isSuccess());
        register("primary", "primary", alice);
        register("unknown", "unknown", alice);
        register("other-user", "worker", context("charlie", "child-c"));
        assertNull(recover(gateway, "primary"));
        assertNull(recover(gateway, "unknown"));
        assertNull(recover(gateway, "other-user"), "another user's catalog must not leak");
    }

    @ParameterizedTest
    @EnumSource(GatewayPath.class)
    void staticRegistryRecoveryPreservesFactoryContext(GatewayPath path) throws Exception {
        Agent child = mock(Agent.class);
        List<RuntimeContext> seenContexts = new ArrayList<>();
        HarnessAgent owner =
                own(
                        builder("static")
                                .disableDynamicSubagents()
                                .subagentFactory(
                                        SubagentDeclaration.builder()
                                                .name("worker")
                                                .description("Static worker")
                                                .build(),
                                        rc -> {
                                            seenContexts.add(rc);
                                            return child;
                                        })
                                .subagentFactory(
                                        SubagentDeclaration.builder()
                                                .name("primary")
                                                .description("Primary-only worker")
                                                .mode(SubagentDeclaration.Mode.PRIMARY)
                                                .build(),
                                        rc -> {
                                            throw new AssertionError(
                                                    "PRIMARY-only factory must not be invoked");
                                        })
                                .build());
        HarnessGateway gateway = gateway(path, owner);
        RuntimeContext rc = context("alice", "static-child");
        register("static", "worker", rc);
        register("primary", "primary", rc);
        register("unknown", "unknown", rc);

        assertSame(child, recover(gateway, "static"));
        assertSame(child, recover(gateway, "static"));
        assertEquals(1, seenContexts.size(), "live-cache hits do not re-materialize");
        assertEquals("alice", seenContexts.get(0).getUserId());
        assertEquals("static-child", seenContexts.get(0).getSessionId());
        assertNull(recover(gateway, "primary"));
        assertNull(recover(gateway, "unknown"));
    }

    @Test
    void bootstrapRegistryRecoveryPreservesOwnerOrderAndFallsThrough() throws Exception {
        Agent firstChild = mock(Agent.class);
        Agent secondChild = mock(Agent.class);
        List<String> created = new ArrayList<>();
        HarnessAgent first =
                own(
                        builder("first")
                                .disableDynamicSubagents()
                                .subagentFactory(
                                        "shared",
                                        name -> {
                                            created.add("first");
                                            return firstChild;
                                        })
                                .build());
        HarnessAgent second =
                own(
                        builder("second")
                                .disableDynamicSubagents()
                                .subagentFactory(
                                        "shared",
                                        name -> {
                                            throw new AssertionError("first owner must win");
                                        })
                                .subagentFactory(
                                        "only-second",
                                        name -> {
                                            created.add("second");
                                            return secondChild;
                                        })
                                .build());
        HarnessGateway gateway =
                GatewayBootstrap.builder()
                        .agent("without-manager", own(builder("main").disableSubagents().build()))
                        .agent("first", first)
                        .agent("second", second)
                        .distributedStore(store)
                        .build()
                        .gateway();
        RuntimeContext rc = context("alice", "ordered-child");
        register("shared", "shared", rc);
        register("second", "only-second", rc);
        register("unknown", "unknown", rc);

        assertSame(firstChild, recover(gateway, "shared"));
        assertSame(secondChild, recover(gateway, "second"));
        assertNull(recover(gateway, "unknown"));
        assertEquals(List.of("first", "second"), created);
    }

    private HarnessAgent.Builder builder(String name) {
        return HarnessAgent.builder()
                .name(name)
                .model(model)
                .workspace(workspace.resolve(name))
                .distributedStore(store);
    }

    private HarnessAgent own(HarnessAgent agent) {
        createdAgents.add(agent);
        return agent;
    }

    private HarnessGateway gateway(GatewayPath path, HarnessAgent owner) {
        if (path == GatewayPath.INTERNAL) {
            return owner.gateway();
        }
        GatewayBootstrap bootstrap =
                GatewayBootstrap.builder()
                        .agent("without-manager", own(builder("main").disableSubagents().build()))
                        .agent(
                                "fallback",
                                own(builder("fallback").disableDynamicSubagents().build()))
                        .agent("owner", owner)
                        .distributedStore(store)
                        .build();
        // Bootstrap exposes a bridge for callers to wire into their registered spawn tool.
        for (var middleware : owner.getDelegate().getMiddlewares()) {
            if (middleware instanceof DynamicSubagentsMiddleware dynamic) {
                dynamic.setGatewayBridge(bootstrap.gatewayBridge());
            } else if (middleware instanceof SubagentsMiddleware subagents) {
                subagents.setGatewayBridge(bootstrap.gatewayBridge());
            }
        }
        return bootstrap.gateway();
    }

    private SubagentRecord spawnExposure(HarnessAgent owner, RuntimeContext rc) {
        rc.put(AgentSpawnTool.CTX_AGENT_MANAGER, owner.getSubagentAgentManager(rc));
        String response =
                assertText(
                        owner.getDelegate()
                                .getToolkit()
                                .getTool("agent_spawn")
                                .callAsync(
                                        ToolCallParam.builder()
                                                .runtimeContext(rc)
                                                .input(
                                                        Map.of(
                                                                "agent_id",
                                                                "worker",
                                                                "reusable",
                                                                true,
                                                                "expose_to_user",
                                                                true))
                                                .build())
                                .block(Duration.ofSeconds(10)),
                        SUCCESS);
        String handle = lineValue(response, "subagent_id: ");
        SubagentRecord record =
                new StoreBackedSubagentRegistry(baseStore).find(handle).orElseThrow();
        assertEquals("worker", record.agentId());
        assertEquals(lineValue(response, "session_id: "), record.sessionId());
        String key = lineValue(response, "agent_key: ");
        assertEquals(
                record.sessionId(),
                rc.getAgentState().getToolContext().getSpawnRegistry().get(key).sessionId());
        return record;
    }

    private static String lineValue(String response, String prefix) {
        return response.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .findFirst()
                .orElseThrow();
    }

    private static RuntimeContext parentContext(String userId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId("parent-session")
                .agentState(AgentState.builder().userId(userId).sessionId("parent-session").build())
                .build();
    }

    private static RuntimeContext context(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    private void register(String handle, String agentId, RuntimeContext rc) {
        // A separate registry instance stands in for the node that persisted the exposure recipe.
        new StoreBackedSubagentRegistry(baseStore)
                .register(
                        new SubagentRecord(
                                handle,
                                agentId,
                                rc.getSessionId(),
                                rc.getUserId(),
                                "parent-session",
                                Instant.now(),
                                null));
    }

    private Agent recover(HarnessGateway gateway, String handle) throws Exception {
        // Stop at registry resolution/materialization: no call(), stream() or model execution.
        Method resolve = HarnessGateway.class.getDeclaredMethod("resolveExposed", String.class);
        resolve.setAccessible(true);
        Object session = resolve.invoke(gateway, handle);
        if (session == null) {
            return null;
        }
        Method accessor = session.getClass().getDeclaredMethod("agent");
        accessor.setAccessible(true);
        Agent agent = (Agent) accessor.invoke(session);
        if (agent instanceof HarnessAgent ha && !createdAgents.contains(ha)) {
            own(ha);
        }
        return agent;
    }
}
