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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.tool.PlanModeTools;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Verifies that {@link HarnessAgent.Builder#enablePlanMode(boolean)} automatically injects
 * ALLOW rules for plan-control tools into the {@link PermissionEngine}, so they are not
 * prompted ASK under {@link PermissionMode#DEFAULT}.
 *
 * <p>Regression test for: PlanModeMiddleware whitelist and PermissionEngine not coordinated.
 */
class HarnessAgentPlanModePermissionTest {

    @TempDir Path workspace;

    private static Model stubModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        ChatResponse chunk =
                new ChatResponse(
                        "id",
                        List.of(TextBlock.builder().text("ok").build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }

    @Test
    void enablePlanMode_planEnterGetsAllowRule() throws Exception {
        java.nio.file.Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(new InMemoryAgentStateStore())
                        .enablePlanMode(true)
                        .build();

        PermissionContextState perm = agent.getDelegate().getPermissionContext();
        List<PermissionRule> rules = perm.getAllowRules().get(PlanModeTools.PLAN_ENTER);
        assertEquals(
                1,
                rules == null ? 0 : rules.size(),
                "plan_enter should have exactly one ALLOW rule");
        if (rules != null && !rules.isEmpty()) {
            assertEquals(PermissionBehavior.ALLOW, rules.get(0).behavior());
        }
    }

    @Test
    void enablePlanMode_planWriteGetsAllowRule() throws Exception {
        java.nio.file.Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(new InMemoryAgentStateStore())
                        .enablePlanMode(true)
                        .build();

        PermissionContextState perm = agent.getDelegate().getPermissionContext();
        List<PermissionRule> rules = perm.getAllowRules().get(PlanModeTools.PLAN_WRITE);
        assertEquals(
                1,
                rules == null ? 0 : rules.size(),
                "plan_write should have exactly one ALLOW rule");
        if (rules != null && !rules.isEmpty()) {
            assertEquals(PermissionBehavior.ALLOW, rules.get(0).behavior());
        }
    }

    @Test
    void enablePlanMode_todoWriteGetsAllowRule() throws Exception {
        java.nio.file.Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(new InMemoryAgentStateStore())
                        .enablePlanMode(true)
                        .build();

        PermissionContextState perm = agent.getDelegate().getPermissionContext();
        List<PermissionRule> rules = perm.getAllowRules().get("todo_write");
        assertEquals(
                1,
                rules == null ? 0 : rules.size(),
                "todo_write should have exactly one ALLOW rule");
        if (rules != null && !rules.isEmpty()) {
            assertEquals(PermissionBehavior.ALLOW, rules.get(0).behavior());
        }
    }

    @Test
    void enablePlanMode_planExitHasNoAllowRule() throws Exception {
        java.nio.file.Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(new InMemoryAgentStateStore())
                        .enablePlanMode(true)
                        .build();

        PermissionContextState perm = agent.getDelegate().getPermissionContext();
        List<PermissionRule> rules = perm.getAllowRules().get(PlanModeTools.PLAN_EXIT);
        assertEquals(
                0,
                rules == null ? 0 : rules.size(),
                "plan_exit must not have an ALLOW rule — it relies on default ASK (HITL)");
    }

    @Test
    void enablePlanMode_userAllowRulesArePreserved() throws Exception {
        java.nio.file.Files.createDirectories(workspace);
        PermissionRule userRule =
                new PermissionRule("my_tool", null, PermissionBehavior.ALLOW, "user");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(new InMemoryAgentStateStore())
                        .enablePlanMode(true)
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.DEFAULT)
                                        .addAllowRule("my_tool", userRule)
                                        .build())
                        .build();

        PermissionContextState perm = agent.getDelegate().getPermissionContext();
        List<PermissionRule> rules = perm.getAllowRules().get("my_tool");
        assertEquals(1, rules == null ? 0 : rules.size(), "user-configured rule must be preserved");
        assertEquals(PermissionBehavior.ALLOW, rules.get(0).behavior());
    }

    @Test
    void disabledPlanMode_noAutoAllowRulesInjected() throws Exception {
        java.nio.file.Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(new InMemoryAgentStateStore())
                        .build();

        PermissionContextState perm = agent.getDelegate().getPermissionContext();
        assertEquals(
                0,
                perm.getAllowRules().size(),
                "no allow rules should be injected when plan mode is disabled");
    }

    @Test
    void enablePlanMode_userDenyAndAskRulesArePreserved() throws Exception {
        java.nio.file.Files.createDirectories(workspace);
        PermissionRule denyRule =
                new PermissionRule("dangerous", null, PermissionBehavior.DENY, "user");
        PermissionRule askRule = new PermissionRule("risky", null, PermissionBehavior.ASK, "user");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(new InMemoryAgentStateStore())
                        .enablePlanMode(true)
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.DEFAULT)
                                        .addDenyRule("dangerous", denyRule)
                                        .addAskRule("risky", askRule)
                                        .build())
                        .build();

        PermissionContextState perm = agent.getDelegate().getPermissionContext();
        List<PermissionRule> denyRules = perm.getDenyRules().get("dangerous");
        List<PermissionRule> askRules = perm.getAskRules().get("risky");
        assertEquals(
                1, denyRules == null ? 0 : denyRules.size(), "user deny rule must be preserved");
        assertEquals(1, askRules == null ? 0 : askRules.size(), "user ask rule must be preserved");
    }

    @Test
    void enablePlanMode_engineAllowsPlanWriteAndAsksPlanExit() throws Exception {
        java.nio.file.Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(new InMemoryAgentStateStore())
                        .enablePlanMode(true)
                        .build();

        // Drive the real plan tools through the injected context + engine, proving the issue-1910
        // symptom is fixed at the permission layer (plan_write auto-allowed, plan_exit HITL).
        PermissionEngine engine = new PermissionEngine(agent.getDelegate().getPermissionContext());
        Toolkit tk = agent.getToolkit();
        ToolBase planWrite = (ToolBase) tk.getTool(PlanModeTools.PLAN_WRITE);
        ToolBase planExit = (ToolBase) tk.getTool(PlanModeTools.PLAN_EXIT);

        PermissionDecision writeDecision = engine.checkPermission(planWrite, Map.of()).block();
        assertEquals(
                PermissionBehavior.ALLOW,
                writeDecision.getBehavior(),
                "plan_write must be auto-allowed (no ASK) under DEFAULT with plan mode enabled");
        PermissionDecision exitDecision = engine.checkPermission(planExit, Map.of()).block();
        assertEquals(
                PermissionBehavior.ASK,
                exitDecision.getBehavior(),
                "plan_exit must remain ASK (HITL) even with plan mode enabled");
    }
}
