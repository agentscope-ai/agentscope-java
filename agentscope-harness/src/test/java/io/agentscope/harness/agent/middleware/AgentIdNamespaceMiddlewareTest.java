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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class AgentIdNamespaceMiddlewareTest {

    private static final String LOGICAL_AGENT_ID = "support-agent-001";
    private static final String DISPLAY_NAME = "Support";
    private static final RuntimeContext RC =
            RuntimeContext.builder().sessionId("session-1").build();

    @TempDir Path workspace;

    @Test
    void memoryFlushOffloadUsesLogicalAgentIdInsteadOfDisplayName() throws Exception {
        AgentState state = AgentState.builder().addMessage(message("m1", "hello")).build();
        Agent agent = mock(Agent.class);
        when(agent.getAgentId()).thenReturn(LOGICAL_AGENT_ID);
        when(agent.getName()).thenReturn(DISPLAY_NAME);
        when(agent.getAgentState()).thenReturn(state);

        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace)) {
            MemoryFlushMiddleware middleware =
                    new MemoryFlushMiddleware(
                            workspaceManager,
                            null,
                            MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                            MemoryConfig.FlushTrigger.never());

            middleware
                    .onAgent(agent, RC, new AgentInput(List.of()), input -> Flux.empty())
                    .blockLast();
        }

        assertTrue(Files.exists(logicalSessionFile()));
        assertFalse(Files.exists(displayNameSessionFile()));
    }

    @Test
    void compactionOffloadUsesLogicalAgentIdInsteadOfDisplayName() throws Exception {
        RecordingModel model = new RecordingModel();
        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerMessages(3)
                        .triggerTokens(Integer.MAX_VALUE)
                        .keepMessages(1)
                        .keepTokens(0)
                        .flushBeforeCompact(false)
                        .offloadBeforeCompact(true)
                        .build();

        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace);
                ReActAgent agent =
                        ReActAgent.builder()
                                .agentId(LOGICAL_AGENT_ID)
                                .name(DISPLAY_NAME)
                                .model(model)
                                .build()) {
            CompactionMiddleware middleware =
                    new CompactionMiddleware(workspaceManager, model, config);
            ReasoningInput input =
                    new ReasoningInput(
                            List.of(
                                    message("m1", "one"),
                                    message("m2", "two"),
                                    message("m3", "three")),
                            List.of(),
                            null);

            middleware
                    .onReasoning(agent, RC, input, ignored -> Flux.<AgentEvent>empty())
                    .blockLast();
        }

        assertTrue(Files.exists(logicalSessionFile()));
        assertFalse(Files.exists(displayNameSessionFile()));
    }

    private Path logicalSessionFile() {
        return workspace.resolve("agents/" + LOGICAL_AGENT_ID + "/sessions/session-1.jsonl");
    }

    private Path displayNameSessionFile() {
        return workspace.resolve("agents/" + DISPLAY_NAME + "/sessions/session-1.jsonl");
    }

    private static Msg message(String id, String text) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static final class RecordingModel implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .id("summary-1")
                            .content(List.of(TextBlock.builder().text("summary").build()))
                            .build());
        }

        @Override
        public String getModelName() {
            return "recording-model";
        }
    }
}
