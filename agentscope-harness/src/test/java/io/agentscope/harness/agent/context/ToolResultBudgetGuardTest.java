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
package io.agentscope.harness.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolResultBudgetGuardTest {
    @TempDir Path workspace;

    @Test
    void excludedReadFileCannotBypassRequestBudget() {
        verifyFits("read_file", 600_000, true);
    }

    @Test
    void arbitraryToolBelowEvictionThresholdCannotExceedSmallWindow() {
        verifyFits("third_party_snapshot", 60_000, true);
    }

    @Test
    void disabledOffloadingStillBoundsModelVisibleOutput() {
        verifyFits("third_party_snapshot", 600_000, false);
    }

    private void verifyFits(String name, int chars, boolean offload) {
        verifyFits(
                name,
                chars,
                offload
                        ? new ToolResultEvictionMiddleware(
                                new LocalFilesystem(workspace), ToolResultEvictionConfig.defaults())
                        : null);
    }

    @Test
    void storageFailureDoesNotPoisonTheNextRequest() {
        AbstractFilesystem denied = mock(AbstractFilesystem.class);
        when(denied.write(any(), anyString(), anyString())).thenReturn(WriteResult.fail("denied"));
        when(denied.read(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(ReadResult.fail("denied"));
        verifyFits(
                "read_file",
                600_000,
                new ToolResultEvictionMiddleware(denied, ToolResultEvictionConfig.defaults()));
    }

    private void verifyFits(String name, int chars, ToolResultEvictionMiddleware eviction) {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("test");
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("small");
        when(model.getContextWindowSize()).thenReturn(4096);
        var use = ToolUseBlock.builder().id("call").name(name).input(Map.of()).build();
        var result =
                new ToolResultBlock(
                        "call",
                        name,
                        List.of(TextBlock.builder().text("x".repeat(chars)).build()),
                        Map.of("evidence", "preserve"));
        List<Msg> history =
                List.of(
                        Msg.builder().role(MsgRole.USER).textContent("Inspect it").build(),
                        Msg.builder().role(MsgRole.ASSISTANT).content(List.of(use)).build(),
                        Msg.builder().role(MsgRole.TOOL).content(List.of(result)).build());
        AgentState state = AgentState.builder().build();
        state.contextMutable().addAll(history);
        var rc = RuntimeContext.builder().agentState(state).build();
        var builder = new HarnessContextBuilder(ContextPolicy.defaults(), null, eviction);
        var prepared =
                builder.prepare(
                                agent,
                                rc,
                                new ModelCallInput(history, List.of(), null, model),
                                "first",
                                ModelRequestPreparer.Purpose.REASONING)
                        .block();
        assertTrue(ContextTokenEstimator.approximate().estimate(prepared).tokens() < 3600);
        HarnessContextBuilder.validateToolPairs(prepared.messages());
        var bounded = (ToolResultBlock) state.getContext().get(2).getContent().get(0);
        assertEquals("call", bounded.getId());
        assertEquals("preserve", bounded.getMetadata().get("evidence"));
        assertEquals(result.getState(), bounded.getState());
        assertTrue(((TextBlock) bounded.getOutput().get(0)).getText().contains("budget"));
        // A subsequent request uses the repaired canonical state, not the original oversized
        // result.
        var next =
                builder.prepare(
                                agent,
                                rc,
                                new ModelCallInput(state.getContext(), List.of(), null, model),
                                "next",
                                ModelRequestPreparer.Purpose.REASONING)
                        .block();
        assertTrue(ContextTokenEstimator.approximate().estimate(next).tokens() < 3600);
    }
}
