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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ToolResultEvictionMiddlewareTest {

    @TempDir Path workspace;

    @Test
    void remoteFilesystemWritesArtifactAndReplacesCallScopedState() {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem filesystem =
                new RemoteFilesystemSpec(store)
                        .isolationScope(IsolationScope.SESSION)
                        .toFilesystem(workspace, "eviction-agent", rc -> List.of("session-1"));
        ToolResultEvictionMiddleware middleware =
                new ToolResultEvictionMiddleware(
                        filesystem,
                        ToolResultEvictionConfig.builder()
                                .maxResultChars(32)
                                .previewChars(8)
                                .build());
        String fullOutput = "large-output-" + "x".repeat(128);
        ToolResultBlock result =
                ToolResultBlock.text(fullOutput).withIdAndName("call-1", "execute");
        Msg toolMessage = Msg.builder().role(MsgRole.TOOL).content(result).build();
        AgentState state = AgentState.builder().sessionId("session-1").build();
        state.contextMutable().add(toolMessage);
        RuntimeContext context =
                RuntimeContext.builder().sessionId("session-1").agentState(state).build();
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("eviction-agent");
        assertSame(state, context.getAgentState());

        middleware
                .onReasoning(
                        agent,
                        context,
                        new ReasoningInput(List.of(toolMessage), List.of(), null),
                        ignored -> Flux.empty())
                .collectList()
                .block();
        assertSame(state, context.getAgentState());
        assertNotSame(toolMessage, state.contextMutable().get(0));

        String persisted =
                filesystem
                        .read(context, "/large_tool_results/eviction-agent/call-1", 0, 0)
                        .fileData()
                        .content();
        assertEquals(fullOutput, persisted);
        String bounded =
                state
                        .contextMutable()
                        .get(0)
                        .getContentBlocks(ToolResultBlock.class)
                        .get(0)
                        .getOutput()
                        .stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .findFirst()
                        .orElseThrow();
        assertTrue(bounded.contains("Tool output was too large"), bounded);
        assertFalse(bounded.contains("x".repeat(32)));
    }

    @Test
    void harnessTurnEvictsBeforeNextModelCallAndPersistentSave() {
        InMemoryStore fileStore = new InMemoryStore();
        InMemoryAgentStateStore backingStateStore = new InMemoryAgentStateStore();
        io.agentscope.core.state.AgentStateStore stateStore =
                mock(
                        io.agentscope.core.state.AgentStateStore.class,
                        delegatesTo(backingStateStore));
        String fullOutput = "large-output-" + "x".repeat(128);
        AtomicReference<String> secondModelInput = new AtomicReference<>();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new LargeOutputTool(fullOutput));
        RuntimeContext context =
                RuntimeContext.builder().userId("user-1").sessionId("session-1").build();

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("eviction-agent")
                        .workspace(workspace)
                        .model(new EvictionModel(secondModelInput))
                        .toolkit(toolkit)
                        .stateStore(stateStore)
                        .filesystem(
                                new RemoteFilesystemSpec(fileStore)
                                        .isolationScope(IsolationScope.SESSION))
                        .toolResultEviction(
                                ToolResultEvictionConfig.builder()
                                        .maxResultChars(32)
                                        .previewChars(8)
                                        .build())
                        .build()) {
            Msg reply =
                    agent.call(
                                    List.of(
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .content(
                                                            TextBlock.builder()
                                                                    .text("run tool")
                                                                    .build())
                                                    .build()),
                                    context)
                            .block();
            assertEquals("done", reply.getTextContent());

            String modelInput = secondModelInput.get();
            assertTrue(modelInput.contains("Tool output was too large"));
            assertFalse(modelInput.contains("x".repeat(32)));
            assertEquals(
                    fullOutput,
                    agent.getWorkspaceManager()
                            .readManagedWorkspaceFileUtf8(
                                    context,
                                    "large_tool_results/eviction-agent/large-output-call"));
            AgentState persisted =
                    stateStore
                            .get("user-1", "session-1", "agent_state", AgentState.class)
                            .orElseThrow();
            String persistedText = toolResultText(persisted);
            assertTrue(persistedText.contains("Tool output was too large"));
            assertFalse(persistedText.contains("x".repeat(32)));
        }
    }

    private static String toolResultText(AgentState state) {
        return state.getContext().stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .flatMap(result -> result.getOutput().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }

    private static final class LargeOutputTool extends ToolBase {
        private final String output;

        private LargeOutputTool(String output) {
            super(
                    "large_output_probe",
                    "Returns a deterministic oversized output.",
                    Map.of("type", "object", "properties", Map.of()),
                    false,
                    true,
                    false,
                    null,
                    false,
                    false);
            this.output = output;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text(output));
        }
    }

    private static final class EvictionModel implements Model {
        private final AtomicReference<String> secondInput;

        private EvictionModel(AtomicReference<String> secondInput) {
            this.secondInput = secondInput;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            boolean hasToolResult =
                    messages.stream()
                            .flatMap(message -> message.getContent().stream())
                            .anyMatch(ToolResultBlock.class::isInstance);
            if (hasToolResult) {
                secondInput.set(
                        messages.stream()
                                .flatMap(message -> message.getContent().stream())
                                .filter(ToolResultBlock.class::isInstance)
                                .map(ToolResultBlock.class::cast)
                                .flatMap(result -> result.getOutput().stream())
                                .filter(TextBlock.class::isInstance)
                                .map(TextBlock.class::cast)
                                .map(TextBlock::getText)
                                .reduce("", String::concat));
                return Flux.just(
                        new ChatResponse(
                                "final",
                                List.<ContentBlock>of(TextBlock.builder().text("done").build()),
                                null,
                                Map.of(),
                                "stop"));
            }
            return Flux.just(
                    new ChatResponse(
                            "tool-request",
                            List.<ContentBlock>of(
                                    ToolUseBlock.builder()
                                            .id("large-output-call")
                                            .name("large_output_probe")
                                            .input(Map.of())
                                            .build()),
                            null,
                            Map.of(),
                            "tool_calls"));
        }

        @Override
        public String getModelName() {
            return "tool-result-eviction-test";
        }
    }
}
