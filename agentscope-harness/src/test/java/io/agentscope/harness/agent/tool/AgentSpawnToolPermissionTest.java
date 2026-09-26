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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSpawnToolPermissionTest {

    private static final String USER_ID = "user-a";
    private static final String PARENT_SESSION_ID = "parent-a";

    @TempDir Path workspace;

    private final List<AutoCloseable> createdAgents = new ArrayList<>();

    @AfterEach
    void closeAgents() throws Exception {
        for (AutoCloseable agent : createdAgents) {
            agent.close();
        }
        createdAgents.clear();
    }

    @Test
    void harnessChildReceivesParentDenyInActualExecutionSlot() {
        PermissionRule parentDeny = denyRule("blocked_probe", "parent-policy");
        AtomicReference<Agent> childRef = new AtomicReference<>();
        AgentSpawnTool tool =
                tool(
                        declaration(true, false),
                        ignored -> {
                            HarnessAgent child =
                                    HarnessAgent.builder()
                                            .name("worker")
                                            .model(new MockModel("done"))
                                            .workspace(workspace)
                                            .stateStore(new InMemoryAgentStateStore())
                                            .disableMemoryTools()
                                            .disableMemoryHooks()
                                            .disableWorkspaceContext()
                                            .build();
                            createdAgents.add(child);
                            childRef.set(child);
                            return child;
                        });

        String spawnResult =
                tool.agentSpawn(
                                parentContext(),
                                parentState(
                                        PermissionContextState.builder()
                                                .mode(PermissionMode.BYPASS)
                                                .addDenyRule("blocked_probe", parentDeny)
                                                .build()),
                                "worker",
                                null,
                                null,
                                null,
                                null)
                        .block();

        String childSessionId = firstLineValue(spawnResult, "session_id: ");
        HarnessAgent child = (HarnessAgent) childRef.get();
        PermissionContextState actual =
                child.getDelegate().getAgentState(USER_ID, childSessionId).getPermissionContext();

        assertEquals(PermissionMode.BYPASS, actual.getMode());
        assertEquals(List.of(parentDeny), actual.getDenyRules().get("blocked_probe"));

        PermissionEngine engine = new PermissionEngine(actual);
        assertEquals(
                PermissionBehavior.ALLOW,
                engine.checkPermission(new ProbeTool("identity_probe"), Map.of())
                        .block()
                        .getBehavior(),
                "a formerly-trivial child must keep allowing unmatched PASSTHROUGH tools");
        assertEquals(
                PermissionBehavior.DENY,
                engine.checkPermission(new ProbeTool("blocked_probe"), Map.of())
                        .block()
                        .getBehavior(),
                "the inherited parent rule must deny the blocked tool");
    }

    @Test
    void directReactChildKeepsItsOwnPermissionContextAndAddsOnlyParentDeny() {
        PermissionRule childAllow =
                new PermissionRule(
                        "read_file", "docs/**", PermissionBehavior.ALLOW, "child-policy");
        PermissionRule childDeny = denyRule("delete_file", "child-policy");
        PermissionRule childAsk =
                new PermissionRule("write_file", "docs/**", PermissionBehavior.ASK, "child-policy");
        PermissionContextState childPermissions =
                PermissionContextState.builder()
                        .mode(PermissionMode.ACCEPT_EDITS)
                        .addWorkingDirectory(
                                "child", new AdditionalWorkingDirectory("/child", "child-policy"))
                        .addAllowRule("read_file", childAllow)
                        .addDenyRule("delete_file", childDeny)
                        .addAskRule("write_file", childAsk)
                        .build();
        PermissionRule parentDeny = denyRule("blocked_probe", "parent-policy");
        PermissionContextState parentPermissions =
                PermissionContextState.builder()
                        .mode(PermissionMode.DONT_ASK)
                        .addWorkingDirectory(
                                "parent",
                                new AdditionalWorkingDirectory("/parent", "parent-policy"))
                        .addAllowRule(
                                "parent_read",
                                new PermissionRule(
                                        "parent_read",
                                        null,
                                        PermissionBehavior.ALLOW,
                                        "parent-policy"))
                        .addDenyRule("blocked_probe", parentDeny)
                        .addAskRule(
                                "parent_write",
                                new PermissionRule(
                                        "parent_write",
                                        null,
                                        PermissionBehavior.ASK,
                                        "parent-policy"))
                        .build();
        AtomicReference<Agent> childRef = new AtomicReference<>();
        AgentSpawnTool tool =
                tool(
                        declaration(true, false),
                        ignored -> {
                            ReActAgent child =
                                    ReActAgent.builder()
                                            .name("worker")
                                            .model(new MockModel("done"))
                                            .permissionContext(childPermissions)
                                            .build();
                            createdAgents.add(child);
                            childRef.set(child);
                            return child;
                        });

        String spawnResult =
                tool.agentSpawn(
                                parentContext(),
                                parentState(parentPermissions),
                                "worker",
                                null,
                                null,
                                null,
                                null)
                        .block();

        String childSessionId = firstLineValue(spawnResult, "session_id: ");
        PermissionContextState actual =
                ((ReActAgent) childRef.get())
                        .getAgentState(USER_ID, childSessionId)
                        .getPermissionContext();

        assertEquals(PermissionMode.ACCEPT_EDITS, actual.getMode());
        assertEquals(
                Map.of("child", childPermissions.getWorkingDirectories().get("child")),
                actual.getWorkingDirectories());
        assertEquals(Map.of("read_file", List.of(childAllow)), actual.getAllowRules());
        assertEquals(
                Map.of(
                        "delete_file", List.of(childDeny),
                        "blocked_probe", List.of(parentDeny)),
                actual.getDenyRules());
        assertEquals(Map.of("write_file", List.of(childAsk)), actual.getAskRules());
    }

    @Test
    void declarationCanDisableParentDenyInheritance() {
        AtomicReference<Agent> childRef = new AtomicReference<>();
        AgentSpawnTool tool =
                tool(
                        declaration(false, false),
                        ignored -> {
                            ReActAgent child =
                                    ReActAgent.builder()
                                            .name("worker")
                                            .model(new MockModel("done"))
                                            .build();
                            createdAgents.add(child);
                            childRef.set(child);
                            return child;
                        });

        String spawnResult =
                tool.agentSpawn(
                                parentContext(),
                                parentState(
                                        PermissionContextState.builder()
                                                .addDenyRule(
                                                        "blocked_probe",
                                                        denyRule("blocked_probe", "parent-policy"))
                                                .build()),
                                "worker",
                                null,
                                null,
                                null,
                                null)
                        .block();

        String childSessionId = firstLineValue(spawnResult, "session_id: ");
        PermissionContextState actual =
                ((ReActAgent) childRef.get())
                        .getAgentState(USER_ID, childSessionId)
                        .getPermissionContext();
        assertTrue(actual.isTrivial());
        assertFalse(actual.getDenyRules().containsKey("blocked_probe"));
    }

    @Test
    void persistentReuseAndAgentSendAddCurrentParentDenyWithoutDuplicates() {
        List<HarnessAgent> children = new ArrayList<>();
        AgentSpawnTool tool =
                tool(
                        declaration(true, true),
                        ignored -> {
                            HarnessAgent child =
                                    HarnessAgent.builder()
                                            .name("worker")
                                            .model(new MockModel("done"))
                                            .workspace(workspace)
                                            .disableMemoryTools()
                                            .disableMemoryHooks()
                                            .disableWorkspaceContext()
                                            .build();
                            children.add(child);
                            createdAgents.add(child);
                            return child;
                        });
        AgentState parentState =
                parentState(
                        PermissionContextState.builder()
                                .addDenyRule("blocked_v1", denyRule("blocked_v1", "parent-v1"))
                                .build());

        String spawnResult =
                tool.agentSpawn(
                                parentContext(),
                                parentState,
                                "worker",
                                null,
                                "persistent-worker",
                                null,
                                null)
                        .block();
        String key = firstLineValue(spawnResult, "agent_key: ");
        String childSessionId = firstLineValue(spawnResult, "session_id: ");
        HarnessAgent persistentChild = children.get(0);

        parentState.setPermissionContext(
                PermissionContextState.builder()
                        .addDenyRule("blocked_v2", denyRule("blocked_v2", "parent-v2"))
                        .build());
        tool.agentSpawn(
                        parentContext(),
                        parentState,
                        "worker",
                        null,
                        "persistent-worker",
                        null,
                        null)
                .block();
        tool.agentSpawn(
                        parentContext(),
                        parentState,
                        "worker",
                        null,
                        "persistent-worker",
                        null,
                        null)
                .block();

        parentState.setPermissionContext(
                PermissionContextState.builder()
                        .addDenyRule("blocked_v3", denyRule("blocked_v3", "parent-v3"))
                        .build());
        tool.agentSend(parentContext(), parentState, key, null, "continue", null).block();

        PermissionContextState actual =
                persistentChild
                        .getDelegate()
                        .getAgentState(USER_ID, childSessionId)
                        .getPermissionContext();
        assertEquals(1, actual.getDenyRules().get("blocked_v1").size());
        assertEquals(1, actual.getDenyRules().get("blocked_v2").size());
        assertEquals(1, actual.getDenyRules().get("blocked_v3").size());
    }

    private AgentSpawnTool tool(SubagentDeclaration declaration, SubagentFactory factory) {
        SubagentEntry entry =
                new SubagentEntry("worker", "permission worker", factory, declaration);
        return new AgentSpawnTool(new DefaultAgentManager(List.of(entry), null), null, 0);
    }

    private static SubagentDeclaration declaration(boolean inherit, boolean persist) {
        return SubagentDeclaration.builder()
                .name("worker")
                .description("permission worker")
                .inlineAgentsBody("Review permissions")
                .persistSession(persist)
                .inheritParentPermissions(inherit)
                .build();
    }

    private static RuntimeContext parentContext() {
        return RuntimeContext.builder().userId(USER_ID).sessionId(PARENT_SESSION_ID).build();
    }

    private static AgentState parentState(PermissionContextState permissions) {
        return AgentState.builder()
                .userId(USER_ID)
                .sessionId(PARENT_SESSION_ID)
                .permissionContext(permissions)
                .build();
    }

    private static PermissionRule denyRule(String toolName, String source) {
        return new PermissionRule(toolName, null, PermissionBehavior.DENY, source);
    }

    private static String firstLineValue(String result, String prefix) {
        return result.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .findFirst()
                .orElseThrow();
    }

    private static final class ProbeTool extends ToolBase {
        private ProbeTool(String name) {
            super(
                    ToolBase.builder()
                            .name(name)
                            .description("permission probe")
                            .inputSchema(Map.of("type", "object", "properties", Map.of()))
                            .readOnly(true)
                            .concurrencySafe(true));
        }
    }
}
