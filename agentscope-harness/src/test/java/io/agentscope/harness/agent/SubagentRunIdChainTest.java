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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Verifies that subagents spawned via {@code agent_spawn} inherit the parent call's runId through
 * the {@code RuntimeContext.builder(parentRc)} derivation chain: the runId observed inside the
 * child equals the one observed by the parent's middleware for the originating call, whether the
 * id was auto-generated or explicitly supplied as an orchestration-layer chain id.
 */
@HarnessQuiescence
class SubagentRunIdChainTest {

    @TempDir Path workspace;
    @TempDir Path stateHome;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private String previousStateHome;
    private HarnessAgent parent;

    @BeforeEach
    void overrideStateHome() {
        previousStateHome = System.getProperty("agentscope.state.home");
        System.setProperty("agentscope.state.home", stateHome.toString());
    }

    @AfterEach
    void tearDown() {
        try {
            if (parent != null) {
                parent.close();
            }
        } finally {
            if (previousStateHome != null) {
                System.setProperty("agentscope.state.home", previousStateHome);
            } else {
                System.clearProperty("agentscope.state.home");
            }
        }
    }

    /** Records every runId observed on {@code ctx} across middleware callbacks. */
    private static final class RunIdRecorder implements MiddlewareBase {
        private final Set<String> observed = ConcurrentHashMap.newKeySet();

        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
            observed.add(ctx.getRunId());
            return Mono.just(prompt);
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static ChatResponse stopChunk(String id, String text) {
        return new ChatResponse(
                id, List.of(TextBlock.builder().text(text).build()), null, Map.of(), "stop");
    }

    private static ChatResponse toolCallChunk(String id, String toolName, Map<String, Object> in) {
        String contentJson = io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(in);
        ToolUseBlock tc =
                ToolUseBlock.builder()
                        .id("tc-" + id)
                        .name(toolName)
                        .input(in)
                        .content(contentJson)
                        .build();
        return new ChatResponse(id, List.of(tc), null, Map.of(), "tool_use");
    }

    private static Model parentModelSpawning(String childId) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                toolCallChunk(
                                        "p1",
                                        "agent_spawn",
                                        Map.of(
                                                "agent_id",
                                                childId,
                                                "task",
                                                "record your runId",
                                                "timeout_seconds",
                                                60))))
                .thenReturn(Flux.just(stopChunk("p2", "parent done")));
        return model;
    }

    private static Model childModelReplying() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-child");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(Flux.just(stopChunk("c1", "child done")));
        return model;
    }

    private HarnessAgent buildParent(
            Model parentModel, RunIdRecorder parentRecorder, RunIdRecorder childRecorder) {
        return HarnessAgent.builder()
                .name("parent")
                .model(parentModel)
                .workspace(workspace)
                .abstractFilesystem(new LocalFilesystem(workspace))
                .middlewares(List.of(parentRecorder))
                .subagentFactory(
                        "recorder",
                        "Records its runId",
                        name ->
                                ReActAgent.builder()
                                        .name(name)
                                        .model(childModelReplying())
                                        .middlewares(List.of(childRecorder))
                                        .build())
                .build();
    }

    private Msg runParentCall(RuntimeContext ctx) {
        return parent.call(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent("spawn the recorder")
                                        .build()),
                        ctx)
                .block(TIMEOUT);
    }

    // -----------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------

    @Test
    void subagentInheritsAutoGeneratedParentRunId() {
        RunIdRecorder parentRecorder = new RunIdRecorder();
        RunIdRecorder childRecorder = new RunIdRecorder();
        parent = buildParent(parentModelSpawning("recorder"), parentRecorder, childRecorder);

        RuntimeContext ctx = RuntimeContext.builder().sessionId("sess-chain-auto").build();
        Msg reply = runParentCall(ctx);
        assertNotNull(reply);

        assertEquals(1, parentRecorder.observed.size(), "parent must observe its own runId");
        String parentRunId = parentRecorder.observed.iterator().next();
        assertEquals(ctx.getRunId(), parentRunId, "parent middleware sees the ctx runId");

        assertEquals(1, childRecorder.observed.size(), "child must observe exactly one runId");
        String childRunId = childRecorder.observed.iterator().next();
        assertNotNull(childRunId);
        assertFalse(childRunId.isBlank());
        assertEquals(
                parentRunId,
                childRunId,
                "subagent derived context must inherit the parent call's runId");
    }

    @Test
    void subagentInheritsExplicitlySuppliedChainRunId() {
        RunIdRecorder parentRecorder = new RunIdRecorder();
        RunIdRecorder childRecorder = new RunIdRecorder();
        parent = buildParent(parentModelSpawning("recorder"), parentRecorder, childRecorder);

        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("sess-chain-explicit")
                        .runId("chain-2026-09-25-001")
                        .build();
        Msg reply = runParentCall(ctx);
        assertNotNull(reply);

        assertEquals(1, childRecorder.observed.size());
        assertEquals(
                "chain-2026-09-25-001",
                childRecorder.observed.iterator().next(),
                "explicit chain id must propagate to the subagent unchanged");
        assertEquals(1, parentRecorder.observed.size());
        assertEquals("chain-2026-09-25-001", parentRecorder.observed.iterator().next());
    }
}
