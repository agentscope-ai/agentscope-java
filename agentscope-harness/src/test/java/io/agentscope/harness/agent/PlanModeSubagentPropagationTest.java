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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.middleware.PlanModeMiddleware;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** Regression coverage for plan-mode capabilities inherited by automatic subagent factories. */
class PlanModeSubagentPropagationTest {

    private static final String PLAN_DIR = "review-plans";

    @TempDir Path workspace;

    @Test
    void declaredSubagent_inheritsPlanModeManagerMiddlewareAndConfiguration() throws Exception {
        SubagentDeclaration declaration =
                SubagentDeclaration.builder()
                        .name("reviewer")
                        .description("Reviews code")
                        .inlineAgentsBody("Review without modifying files.")
                        .workspaceMode(WorkspaceMode.SHARED)
                        .build();

        SubagentEntry entry =
                HarnessAgent.builder()
                        .model(new MockModel("unused"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(new InMemoryAgentStateStore())
                        .enablePlanMode()
                        .planFileDirectory(PLAN_DIR)
                        .allowShellInPlanMode()
                        .subagent(declaration)
                        .buildSubagentEntries(workspace)
                        .stream()
                        .filter(e -> declaration.getName().equals(e.name()))
                        .findFirst()
                        .orElseThrow();

        assertPlanModeEnforced(entry);
    }

    @Test
    void generalPurposeSubagent_inheritsPlanModeManagerMiddlewareAndConfiguration()
            throws Exception {
        SubagentEntry entry =
                HarnessAgent.builder()
                        .model(new MockModel("unused"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(new InMemoryAgentStateStore())
                        .enablePlanMode()
                        .planFileDirectory(PLAN_DIR)
                        .allowShellInPlanMode()
                        .buildSubagentEntries(workspace)
                        .stream()
                        .filter(e -> "general-purpose".equals(e.name()))
                        .findFirst()
                        .orElseThrow();

        assertPlanModeEnforced(entry);
    }

    private void assertPlanModeEnforced(SubagentEntry entry) throws Exception {
        RuntimeContext context =
                RuntimeContext.builder().userId("user").sessionId("child-session").build();

        try (HarnessAgent child = (HarnessAgent) entry.factory().create(context)) {
            List<String> toolNames =
                    child.getToolkit().getToolSchemas().stream().map(ToolSchema::getName).toList();
            assertTrue(toolNames.contains("plan_enter"));
            assertTrue(toolNames.contains("plan_write"));
            assertTrue(toolNames.contains("plan_exit"));

            PlanModeMiddleware middleware =
                    child.getDelegate().getMiddlewares().stream()
                            .filter(PlanModeMiddleware.class::isInstance)
                            .map(PlanModeMiddleware.class::cast)
                            .findFirst()
                            .orElseThrow();

            child.enterPlanMode(context);
            var childState =
                    child.getDelegate().getAgentState(context.getUserId(), context.getSessionId());
            assertTrue(childState.getPlanModeContext().isPlanActive());
            assertEquals(
                    PLAN_DIR + "/PLAN.md", childState.getPlanModeContext().getCurrentPlanFile());

            context.setAgentState(childState);
            String prompt = middleware.onSystemPrompt(child, context, "base prompt").block();
            assertTrue(prompt.contains("PLAN MODE is active"));
            assertTrue(prompt.contains(PLAN_DIR + "/PLAN.md"));
            assertTrue(prompt.contains("execute"), "allowShellInPlanMode must be inherited");

            Map<String, Object> input =
                    Map.of("path", "must-not-be-written.txt", "content", "must-not-be-written");
            ToolUseBlock writeCall =
                    ToolUseBlock.builder()
                            .id("write-call")
                            .name("write_file")
                            .input(input)
                            .content(JsonUtils.getJsonCodec().toJson(input))
                            .build();
            AtomicBoolean coreActingInvoked = new AtomicBoolean();
            var events =
                    middleware
                            .onActing(
                                    child,
                                    context,
                                    new ActingInput(List.of(writeCall)),
                                    ignored -> {
                                        coreActingInvoked.set(true);
                                        return Flux.empty();
                                    })
                            .collectList()
                            .block();

            assertFalse(
                    coreActingInvoked.get(),
                    "a denied child write must not reach core tool execution");
            assertEquals(3, events.size(), "denial emits start, delta, and end events");
            assertFalse(
                    Files.exists(workspace.resolve("must-not-be-written.txt")),
                    "PlanModeMiddleware must deny the child write before filesystem execution");
        }
    }
}
