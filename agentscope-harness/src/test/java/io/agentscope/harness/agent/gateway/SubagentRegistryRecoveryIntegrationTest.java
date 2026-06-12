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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.TestCleanupSupport;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage of the distributed exposed-subagent recovery chain introduced for the
 * {@code distributedStore}-backed {@link SubagentRegistry}.
 *
 * <p>Two independent {@link HarnessGateway} instances stand in for two nodes. They share nothing but
 * the storage primitives a {@code DistributedStore} would expose: one {@link BaseStore} (registry
 * persistence) and one {@link AgentStateStore} (subagent conversation state). The test exercises the
 * full path:
 *
 * <pre>
 *   node A: expose → persist record + state   ⟶   shared store
 *   node B: cache miss → registry.find → materialize → load state by sessionId → continue
 * </pre>
 *
 * <p>State continuity is proven by a {@link MockModel} that echoes how many {@code "ping"} turns it
 * sees in its context: node B observing {@code turns=2} can only happen if it resolved the handle
 * from the shared registry <em>and</em> loaded node A's prior turn from the shared state store.
 */
class SubagentRegistryRecoveryIntegrationTest {

    @TempDir Path workspace;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    private static final String SUB_SESSION = "exposed-echo-session";

    @AfterEach
    void cleanup() {
        try {
            TestCleanupSupport.closeAll(closeables);
        } finally {
            TestCleanupSupport.deleteRecursivelyWithRetry(workspace);
        }
    }

    private HarnessAgent track(HarnessAgent agent) {
        return TestCleanupSupport.track(closeables, agent);
    }

    /** A subagent whose reply reports the number of user turns it can see in context. */
    private HarnessAgent buildEchoSubagent(AgentStateStore sharedState) {
        Function<List<Msg>, List<ChatResponse>> generator =
                msgs -> {
                    long pings =
                            msgs.stream()
                                    .map(Msg::getTextContent)
                                    .filter(t -> t != null && t.contains("ping"))
                                    .count();
                    return List.of(
                            new ChatResponse(
                                    "r-" + pings,
                                    List.of(TextBlock.builder().text("turns=" + pings).build()),
                                    null,
                                    Map.of(),
                                    "stop"));
                };
        return track(
                HarnessAgent.builder()
                        .name("echo")
                        .model(new MockModel(generator))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(sharedState)
                        .build());
    }

    private static Msg ping() {
        return Msg.builder().role(MsgRole.USER).textContent("ping").build();
    }

    private static String text(Msg m) {
        return m != null ? m.getTextContent() : null;
    }

    @Test
    void exposedSubagentResolvesAndContinuesOnAnotherNode() {
        // Shared "distributed store" primitives.
        BaseStore sharedBase = new InMemoryStore();
        AgentStateStore sharedState = new InMemoryAgentStateStore();
        Supplier<HarnessAgent> subFactory = () -> buildEchoSubagent(sharedState);

        // ---- Node A: expose a live subagent and run the first turn. ----
        HarnessGateway nodeA = HarnessGateway.create();
        nodeA.setSubagentRegistry(new StoreBackedSubagentRegistry(sharedBase));
        nodeA.setSubagentMaterializer((agentId, rc) -> Optional.of(subFactory.get()));

        HarnessAgent liveSub = subFactory.get();
        String subagentId = nodeA.exposeSubagent("echo", SUB_SESSION, liveSub, null);
        assertNotNull(subagentId);

        Msg firstReply = nodeA.runSubagent(subagentId, List.of(ping())).block();
        assertEquals("turns=1", text(firstReply), "node A sees exactly its own turn");

        // ---- Node B: a fresh gateway with an EMPTY live cache, sharing only the store. ----
        HarnessGateway nodeB = HarnessGateway.create();
        nodeB.setSubagentRegistry(new StoreBackedSubagentRegistry(sharedBase));
        nodeB.setSubagentMaterializer((agentId, rc) -> Optional.of(subFactory.get()));

        Msg secondReply = nodeB.runSubagent(subagentId, List.of(ping())).block();
        // turns=2 proves: (1) the subagentId was resolved from the shared registry on a node that
        // never exposed it, (2) the agent was re-materialized, and (3) node A's prior turn was
        // loaded from the shared state store by sessionId.
        assertEquals("turns=2", text(secondReply), "node B recovered and continued the session");
    }

    @Test
    void unknownSubagentWithoutSharedRegistryIsUnresolvable() {
        BaseStore sharedBase = new InMemoryStore();
        AgentStateStore sharedState = new InMemoryAgentStateStore();
        Supplier<HarnessAgent> subFactory = () -> buildEchoSubagent(sharedState);

        HarnessGateway nodeA = HarnessGateway.create();
        nodeA.setSubagentRegistry(new StoreBackedSubagentRegistry(sharedBase));
        String subagentId = nodeA.exposeSubagent("echo", SUB_SESSION, subFactory.get(), null);

        // A node whose registry is backed by a DIFFERENT (empty) store cannot see the handle, even
        // with a materializer present — proving the registry is what carries cross-node identity.
        HarnessGateway isolatedNode = HarnessGateway.create();
        isolatedNode.setSubagentRegistry(new StoreBackedSubagentRegistry(new InMemoryStore()));
        isolatedNode.setSubagentMaterializer((agentId, rc) -> Optional.of(subFactory.get()));

        assertThrows(
                IllegalArgumentException.class,
                () -> isolatedNode.runSubagent(subagentId, List.of(ping())).block());
    }

    @Test
    void storeBackedRegistryPersistsAcrossInstancesAndHonoursTtl() {
        BaseStore sharedBase = new InMemoryStore();
        SubagentRegistry writer = new StoreBackedSubagentRegistry(sharedBase);
        SubagentRegistry reader = new StoreBackedSubagentRegistry(sharedBase);

        SubagentRecord record =
                new SubagentRecord(
                        "sub-abc", "echo", SUB_SESSION, "user-1", "parent-1", Instant.now(), null);
        writer.register(record);

        // A separate registry instance over the same store resolves the record (cross-node).
        Optional<SubagentRecord> found = reader.find("sub-abc");
        assertTrue(found.isPresent());
        assertEquals("echo", found.get().agentId());
        assertEquals(SUB_SESSION, found.get().sessionId());
        assertEquals("user-1", found.get().userId());

        // Revocation is visible across instances.
        writer.revoke("sub-abc");
        assertTrue(reader.find("sub-abc").isEmpty());

        // Expired records are treated as absent and evicted lazily on find.
        writer.register(
                new SubagentRecord(
                        "sub-expired",
                        "echo",
                        SUB_SESSION,
                        null,
                        null,
                        Instant.now().minusSeconds(120),
                        Instant.now().minusSeconds(60)));
        assertTrue(reader.find("sub-expired").isEmpty(), "expired record must not resolve");
    }
}
