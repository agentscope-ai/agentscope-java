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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
                        .block(TIMEOUT);
        String second =
                tool.agentSpawn(ctx, null, "note-taker", "What is the code word?", null, 30, null)
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
