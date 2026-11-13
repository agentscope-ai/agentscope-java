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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentExecutionHost;
import io.agentscope.core.agent.AgentExecutionPosition;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.journal.InvocationRecorder;
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
import java.util.EnumSet;
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

/** Child invocations have their own execution identity and preserve explicit parent linkage. */
@HarnessQuiescence
class SubagentInvocationIdentityTest {

    @TempDir Path workspace;
    @TempDir Path stateHome;

    // The in-process parent→subagent round trip settles in well under a second on an
    // unloaded runner (0.4s measured on ubuntu CI), but on windows runners under -T1C
    // contention the blocking read has been starved just past the former 10s budget
    // three times (#4091, #4025, #4142 — elapsed 10.04-10.84s). 30s widens only the
    // failure deadline; the happy path returns as soon as the turn completes.
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

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
        private final Set<String> parents = ConcurrentHashMap.newKeySet();
        private final Set<String> parentSteps = ConcurrentHashMap.newKeySet();

        @Override
        public Set<ExtensionPoint> activePoints() {
            return EnumSet.of(ExtensionPoint.ON_SYSTEM_PROMPT);
        }

        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
            observed.add(ctx.getRunId());
            assertNull(ctx.getTurnId());
            if (ctx.get(InvocationRecorder.PARENT_KEY) instanceof Map<?, ?> link) {
                assertNull(ctx.get(AgentExecutionHost.class));
                assertNull(ctx.get(AgentExecutionPosition.class));
                assertFalse(link.containsKey("parentTurnId"));
                parents.add((String) link.get("parentRunId"));
                parentSteps.add((String) link.get("parentStepId"));
            }
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
    void childHasDistinctRunAndParentLink() {
        RunIdRecorder parentRecorder = new RunIdRecorder();
        RunIdRecorder childRecorder = new RunIdRecorder();
        parent = buildParent(parentModelSpawning("recorder"), parentRecorder, childRecorder);

        RuntimeContext ctx = RuntimeContext.builder().sessionId("sess-chain-auto").build();
        Msg reply = runParentCall(ctx);
        assertNotNull(reply);

        assertEquals(1, parentRecorder.observed.size(), "parent must observe its own runId");
        String parentRunId = parentRecorder.observed.iterator().next();
        assertNull(ctx.getRunId(), "the reusable request context does not own an invocation");
        assertNotNull(parentRunId);

        assertEquals(1, childRecorder.observed.size(), "child must observe exactly one runId");
        String childRunId = childRecorder.observed.iterator().next();
        assertNotNull(childRunId);
        assertFalse(childRunId.isBlank());
        assertNotEquals(parentRunId, childRunId);
        assertEquals(Set.of(parentRunId), childRecorder.parents);
        assertEquals(1, childRecorder.parentSteps.size());
        assertEquals(
                parent.getDelegate().threadJournal(ctx).readAfter(0, 1000).stream()
                        .filter(event -> "step/start".equals(event.type()))
                        .findFirst()
                        .orElseThrow()
                        .data()
                        .get("stepId"),
                childRecorder.parentSteps.iterator().next());
    }

    @Test
    void explicitParentRunIdIsNotReusedByChild() {
        RunIdRecorder parentRecorder = new RunIdRecorder();
        RunIdRecorder childRecorder = new RunIdRecorder();
        parent = buildParent(parentModelSpawning("recorder"), parentRecorder, childRecorder);

        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("sess-chain-explicit")
                        .runId("parent-invocation")
                        .put(AgentExecutionPosition.class, new AgentExecutionPosition(3, 2))
                        .put(
                                AgentExecutionHost.class,
                                (context, result, next) -> {
                                    assertEquals("parent-invocation", context.getRunId());
                                    return next.get();
                                })
                        .build();
        Msg reply = runParentCall(ctx);
        assertNotNull(reply);

        assertEquals(1, childRecorder.observed.size());
        assertNotEquals("parent-invocation", childRecorder.observed.iterator().next());
        assertEquals(Set.of("parent-invocation"), childRecorder.parents);
        assertEquals(1, parentRecorder.observed.size());
        assertEquals("parent-invocation", parentRecorder.observed.iterator().next());
    }
}
