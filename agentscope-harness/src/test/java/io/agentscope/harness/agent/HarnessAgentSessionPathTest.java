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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessAgentSessionPathTest {

    private static final String AGENT_NAME = "display-name";
    private static final String AGENT_ID = "stable-agent-id";
    private static final String SESSION_ID = "session-1";

    @TempDir Path workspace;

    @Test
    void memoryOffloadUsesConfiguredAgentIdInsteadOfAgentName() {
        try (HarnessAgent agent =
                baseBuilder(successfulModel("ok"))
                        .memory(
                                MemoryConfig.builder()
                                        .flushTrigger(MemoryConfig.FlushTrigger.never())
                                        .build())
                        .build()) {
            assertNotNull(
                    agent.call(message("m1", MsgRole.USER, "hello"), runtimeContext()).block());
        }

        assertUsesConfiguredAgentId();
    }

    @Test
    void compactionUsesConfiguredAgentIdInsteadOfAgentName() {
        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerMessages(3)
                        .keepMessages(1)
                        .keepTokens(0)
                        .flushBeforeCompact(false)
                        .build();

        try (HarnessAgent agent =
                baseBuilder(successfulModel("summary"))
                        .disableMemoryHooks()
                        .compaction(config)
                        .build()) {
            assertNotNull(
                    agent.call(
                                    List.of(
                                            message("m1", MsgRole.USER, "first"),
                                            message("m2", MsgRole.ASSISTANT, "second"),
                                            message("m3", MsgRole.USER, "third")),
                                    runtimeContext())
                            .block());
        }

        assertUsesConfiguredAgentId();
    }

    @Test
    void memoryOffloadLegacyConstructorFallsBackToAgentName() {
        Msg msg = message("m1", MsgRole.USER, "hello");
        AgentState state = AgentState.builder().context(List.of(msg)).build();
        RuntimeContext rc =
                RuntimeContext.builder().sessionId(SESSION_ID).agentState(state).build();
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(AGENT_NAME);

        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace)) {
            MemoryFlushMiddleware middleware =
                    new MemoryFlushMiddleware(
                            workspaceManager,
                            successfulModel("unused"),
                            MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                            MemoryConfig.FlushTrigger.never(),
                            IsolationScope.USER);
            middleware
                    .onAgent(
                            agent,
                            rc,
                            new AgentInput(List.of(msg)),
                            ignored -> Flux.<AgentEvent>empty())
                    .blockLast();
        }

        assertUsesAgentName();
    }

    @Test
    void memoryConvenienceConstructorsRemainBackwardCompatible() {
        assertNotNull(new MemoryFlushMiddleware(null, null));
        assertNotNull(
                new MemoryFlushMiddleware(
                        null,
                        null,
                        MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                        MemoryConfig.FlushTrigger.never()));
    }

    @Test
    void compactionLegacyConstructorFallsBackToAgentName() {
        List<Msg> messages =
                List.of(
                        message("m1", MsgRole.USER, "first"),
                        message("m2", MsgRole.ASSISTANT, "second"));
        AgentState state = AgentState.builder().context(messages).build();
        RuntimeContext rc =
                RuntimeContext.builder().sessionId(SESSION_ID).agentState(state).build();
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getName()).thenReturn(AGENT_NAME);
        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerMessages(2)
                        .keepMessages(1)
                        .keepTokens(0)
                        .flushBeforeCompact(false)
                        .build();

        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace)) {
            CompactionMiddleware middleware =
                    new CompactionMiddleware(workspaceManager, successfulModel("summary"), config);
            middleware
                    .onReasoning(
                            agent,
                            rc,
                            new ReasoningInput(messages, List.of(), null),
                            ignored -> Flux.<AgentEvent>empty())
                    .blockLast();
        }

        assertUsesAgentName();
    }

    private HarnessAgent.Builder baseBuilder(Model model) {
        return HarnessAgent.builder()
                .name(AGENT_NAME)
                .agentId(AGENT_ID)
                .model(model)
                .workspace(workspace)
                .abstractFilesystem(new LocalFilesystem(workspace))
                .stateStore(new InMemoryAgentStateStore());
    }

    private RuntimeContext runtimeContext() {
        return RuntimeContext.builder().sessionId(SESSION_ID).build();
    }

    private void assertUsesConfiguredAgentId() {
        assertTrue(Files.exists(sessionPath(AGENT_ID)));
        assertFalse(Files.exists(sessionPath(AGENT_NAME)));
    }

    private void assertUsesAgentName() {
        assertTrue(Files.exists(sessionPath(AGENT_NAME)));
        assertFalse(Files.exists(sessionPath(AGENT_ID)));
    }

    private Path sessionPath(String agentId) {
        return workspace
                .resolve("agents")
                .resolve(agentId)
                .resolve("sessions")
                .resolve(SESSION_ID + ".jsonl");
    }

    private static Model successfulModel(String text) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(response(text)));
        return model;
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(
                "stub-id", List.of(TextBlock.builder().text(text).build()), null, Map.of(), "stop");
    }

    private static Msg message(String id, MsgRole role, String text) {
        return Msg.builder()
                .id(id)
                .role(role)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
