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
package io.agentscope.harness.agent;

import static io.agentscope.core.message.ToolResultState.SUCCESS;
import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

/**
 * Covers {@link HarnessAgent.Builder#subagentFactory(SubagentDeclaration,
 * io.agentscope.harness.agent.subagent.SubagentFactory)}: a custom subagent instance combined with
 * declaration options such as {@code persistSession} (issue #3440).
 */
@HarnessQuiescence
class SubagentFactoryDeclarationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir Path workspace;

    private final List<HarnessAgent> createdAgents = new ArrayList<>();

    @AfterEach
    void closeAgents() {
        createdAgents.forEach(HarnessAgent::close);
        createdAgents.clear();
    }

    @Test
    void declaredFactory_registersDeclarationWithCustomFactory() {
        Agent child = mock(Agent.class);
        AtomicReference<RuntimeContext> seenParentRc = new AtomicReference<>();
        SubagentDeclaration declaration =
                SubagentDeclaration.builder()
                        .name("note-taker")
                        .description("Keeps notes across spawns")
                        .persistSession(true)
                        .build();
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .model(new MockModel("unused"))
                        .workspace(workspace)
                        .subagentFactory(
                                declaration,
                                parentRc -> {
                                    seenParentRc.set(parentRc);
                                    return child;
                                });

        RuntimeContext parentRc = RuntimeContext.builder().userId("u").sessionId("s").build();
        for (List<SubagentEntry> entries :
                List.of(
                        builder.buildSubagentEntries(workspace),
                        HarnessAgentBuilderSupport.buildStaticSubagentEntries(
                                builder, workspace, null))) {
            SubagentEntry entry =
                    entries.stream()
                            .filter(e -> "note-taker".equals(e.name()))
                            .findFirst()
                            .orElseThrow();
            assertEquals("Keeps notes across spawns", entry.description());
            assertSame(declaration, entry.declaration());

            seenParentRc.set(null);
            assertSame(child, entry.factory().create(parentRc));
            assertSame(parentRc, seenParentRc.get(), "factory must receive the parent context");
        }
    }

    @Test
    void declaredFactory_persistSessionReusesCustomHarnessAgent() {
        List<List<Msg>> childModelInputs = new CopyOnWriteArrayList<>();
        MockModel childModel = new MockModel(recordingReply(childModelInputs, "noted"));
        AtomicInteger privateMiddlewareCalls = new AtomicInteger();
        AtomicInteger factoryCalls = new AtomicInteger();
        MiddlewareBase privateMiddleware =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onAgent(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentInput input,
                            Function<AgentInput, Flux<AgentEvent>> next) {
                        privateMiddlewareCalls.incrementAndGet();
                        return next.apply(input);
                    }
                };
        SubagentDeclaration declaration =
                SubagentDeclaration.builder()
                        .name("note-taker")
                        .description("Keeps notes across spawns")
                        .persistSession(true)
                        .build();
        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(new MockModel("unused"))
                        .workspace(workspace)
                        .subagentFactory(
                                declaration,
                                parentRc -> {
                                    factoryCalls.incrementAndGet();
                                    HarnessAgent child =
                                            HarnessAgent.builder()
                                                    .name("note-taker")
                                                    .model(childModel)
                                                    .workspace(workspace)
                                                    .stateStore(new InMemoryAgentStateStore())
                                                    .middleware(privateMiddleware)
                                                    .build();
                                    createdAgents.add(child);
                                    return child;
                                })
                        .buildSubagentEntries(workspace);
        AgentSpawnTool tool = new AgentSpawnTool(new DefaultAgentManager(entries, null), null, 0);
        RuntimeContext ctx =
                RuntimeContext.builder().userId("user").sessionId("parent-session").build();

        String first =
                tool.agentSpawn(
                                ctx,
                                null,
                                "note-taker",
                                "Remember the code word: heron.",
                                null,
                                30,
                                null)
                        .map(result -> assertText(result, SUCCESS))
                        .block(TIMEOUT);
        AgentState nextParentState = AgentState.builder().build();
        String second =
                tool.agentSpawn(
                                ctx,
                                nextParentState,
                                "note-taker",
                                "What is the code word?",
                                null,
                                30,
                                null)
                        .map(result -> assertText(result, SUCCESS))
                        .block(TIMEOUT);

        assertTrue(first.contains("status: ok"), first);
        assertTrue(second.contains("status: ok"), second);
        assertEquals(
                lineValue(first, "agent_key: "),
                lineValue(second, "agent_key: "),
                "persistSession must reuse the deterministic key");
        assertTrue(
                childModelInputs.stream()
                        .anyMatch(
                                messages ->
                                        containsText(messages, "code word: heron")
                                                && containsText(
                                                        messages, "What is the code word?")),
                "the second spawn must continue the first spawn's conversation");
        assertEquals(2, privateMiddlewareCalls.get(), "custom middleware runs on both spawns");
        assertEquals(
                1, factoryCalls.get(), "persistent reuse must not construct a discarded child");
        assertTrue(
                nextParentState
                        .getToolContext()
                        .getSpawnRegistry()
                        .containsKey(lineValue(second, "agent_key: ")),
                "a reused child must be registered before returning to the current parent call");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "notes"})
    void declaredFactory_persistSessionIsolatesUsers(String label) {
        AtomicInteger factoryCalls = new AtomicInteger();
        SubagentDeclaration declaration =
                SubagentDeclaration.builder()
                        .name("note-taker")
                        .description("Keeps notes")
                        .persistSession(true)
                        .build();
        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(new MockModel("unused"))
                        .workspace(workspace)
                        .subagentFactory(
                                declaration,
                                parentRc -> {
                                    factoryCalls.incrementAndGet();
                                    String owner = parentRc.getUserId();
                                    HarnessAgent child =
                                            HarnessAgent.builder()
                                                    .name("note-taker")
                                                    .model(new MockModel("child bound to " + owner))
                                                    .workspace(workspace.resolve(owner))
                                                    .stateStore(new InMemoryAgentStateStore())
                                                    .build();
                                    createdAgents.add(child);
                                    return child;
                                })
                        .buildSubagentEntries(workspace);
        AgentSpawnTool tool = new AgentSpawnTool(new DefaultAgentManager(entries, null), null, 0);
        RuntimeContext alice = RuntimeContext.builder().userId("alice").sessionId("s1").build();
        RuntimeContext bob = RuntimeContext.builder().userId("bob").sessionId("s1").build();

        String aliceFirst =
                tool.agentSpawn(alice, null, "note-taker", "first", label, 30, null)
                        .map(result -> assertText(result, SUCCESS))
                        .block(TIMEOUT);
        String bobFirst =
                tool.agentSpawn(bob, null, "note-taker", "first", label, 30, null)
                        .map(result -> assertText(result, SUCCESS))
                        .block(TIMEOUT);
        String aliceAgain =
                tool.agentSpawn(alice, null, "note-taker", "again", label, 30, null)
                        .map(result -> assertText(result, SUCCESS))
                        .block(TIMEOUT);
        String bobAgain =
                tool.agentSpawn(bob, null, "note-taker", "again", label, 30, null)
                        .map(result -> assertText(result, SUCCESS))
                        .block(TIMEOUT);

        assertTrue(aliceFirst.contains("child bound to alice"), aliceFirst);
        assertTrue(aliceAgain.contains("child bound to alice"), aliceAgain);
        assertTrue(bobFirst.contains("child bound to bob"), bobFirst);
        assertTrue(bobAgain.contains("child bound to bob"), bobAgain);
        assertNotEquals(lineValue(aliceFirst, "agent_key: "), lineValue(bobFirst, "agent_key: "));
        assertNotEquals(lineValue(aliceFirst, "session_id: "), lineValue(bobFirst, "session_id: "));
        assertEquals(lineValue(aliceFirst, "agent_key: "), lineValue(aliceAgain, "agent_key: "));
        assertEquals(lineValue(bobFirst, "agent_key: "), lineValue(bobAgain, "agent_key: "));
        assertEquals(2, factoryCalls.get(), "one child must be constructed per user");
        if (!label.isBlank()) {
            String aliceSend =
                    tool.agentSend(alice, null, null, label, "follow-up", 30)
                            .map(result -> assertText(result, SUCCESS))
                            .block(TIMEOUT);
            String bobSend =
                    tool.agentSend(bob, null, null, label, "follow-up", 30)
                            .map(result -> assertText(result, SUCCESS))
                            .block(TIMEOUT);
            assertTrue(aliceSend.contains("child bound to alice"), aliceSend);
            assertTrue(bobSend.contains("child bound to bob"), bobSend);
        }
    }

    @Test
    void declaredFactory_concurrentPersistentSpawnsCreateOneChild() throws Exception {
        AtomicInteger factoryCalls = new AtomicInteger();
        SubagentDeclaration declaration =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("Worker")
                        .persistSession(true)
                        .build();
        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(new MockModel("unused"))
                        .workspace(workspace)
                        .subagentFactory(
                                declaration,
                                rc -> {
                                    factoryCalls.incrementAndGet();
                                    return mock(Agent.class);
                                })
                        .buildSubagentEntries(workspace);
        AgentSpawnTool tool = new AgentSpawnTool(new DefaultAgentManager(entries, null), null, 0);
        RuntimeContext ctx = RuntimeContext.builder().userId("user").sessionId("s1").build();
        int callers = 8;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    assertTrue(start.await(10, TimeUnit.SECONDS));
                                    return tool.agentSpawn(
                                                    ctx, null, "worker", null, "notes", 30, null)
                                            .map(result -> assertText(result, SUCCESS))
                                            .block(TIMEOUT);
                                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            String expectedKey = null;
            for (Future<String> result : results) {
                String response = result.get(30, TimeUnit.SECONDS);
                assertTrue(response.contains("status: accepted"), response);
                String key = lineValue(response, "agent_key: ");
                if (expectedKey == null) {
                    expectedKey = key;
                }
                assertEquals(expectedKey, key);
            }
            assertEquals(1, factoryCalls.get(), "concurrent cache misses must construct one child");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void declaredFactory_rejectsRemoteDeclaration() {
        SubagentDeclaration remote =
                SubagentDeclaration.builder()
                        .name("remote-worker")
                        .description("Runs elsewhere")
                        .url("http://localhost:8080")
                        .build();
        HarnessAgent.Builder builder = HarnessAgent.builder();

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> builder.subagentFactory(remote, rc -> mock(Agent.class)));
        assertTrue(error.getMessage().contains("remote-worker"), error.getMessage());
    }

    private static Function<List<Msg>, List<ChatResponse>> recordingReply(
            List<List<Msg>> inputs, String text) {
        return messages -> {
            inputs.add(List.copyOf(messages));
            return List.of(
                    new ChatResponse(
                            "child-reply",
                            List.of(TextBlock.builder().text(text).build()),
                            null,
                            Map.of(),
                            "stop"));
        };
    }

    private static boolean containsText(List<Msg> messages, String text) {
        return messages.stream()
                .map(Msg::getTextContent)
                .anyMatch(content -> content != null && content.contains(text));
    }

    private static String lineValue(String result, String prefix) {
        return result.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .findFirst()
                .orElseThrow();
    }
}
