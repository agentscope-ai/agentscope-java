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
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.gateway.SubagentGatewayBridge;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSpawnToolPermissionTest {

    @TempDir Path workspace;

    @Test
    void inheritsCompleteParentPermissionContextForReactChild() {
        PermissionContextState childPermissions = childPermissions();
        AtomicReference<Agent> childRef = new AtomicReference<>();
        AgentSpawnTool tool =
                tool(childPermissions, declaration(true), childRef, new AtomicInteger(), false);
        AgentState parentState = parentState(parentPermissions("parent-v1"));
        RuntimeContext parentContext = parentContext("user-a", "parent-a");

        tool.agentSpawn(parentContext, parentState, "worker", null, "review", null, null).block();

        ReActAgent child = (ReActAgent) childRef.get();
        String childSessionId = childSessionId("parent-a", "review");
        PermissionContextState inherited =
                child.getAgentState("user-a", childSessionId).getPermissionContext();
        assertInheritedContext(inherited, "parent-v1");
    }

    @Test
    void inheritsCompleteParentPermissionContextForHarnessChild() throws Exception {
        PermissionContextState childPermissions = childPermissions();
        AtomicReference<Agent> childRef = new AtomicReference<>();
        AgentSpawnTool tool =
                tool(childPermissions, declaration(true), childRef, new AtomicInteger(), true);
        AgentState parentState = parentState(parentPermissions("parent-v1"));
        RuntimeContext parentContext = parentContext("user-a", "parent-a");

        tool.agentSpawn(parentContext, parentState, "worker", null, "review", null, null).block();

        try (HarnessAgent child = (HarnessAgent) childRef.get()) {
            String childSessionId = childSessionId("parent-a", "review");
            PermissionContextState inherited =
                    child.getDelegate()
                            .getAgentState("user-a", childSessionId)
                            .getPermissionContext();
            assertInheritedContext(inherited, "parent-v1");
        }
    }

    @Test
    void inheritanceCanBeDisabledPerDeclaration() {
        PermissionContextState childPermissions = childPermissions();
        AtomicReference<Agent> childRef = new AtomicReference<>();
        AgentSpawnTool tool =
                tool(childPermissions, declaration(false), childRef, new AtomicInteger(), false);

        tool.agentSpawn(
                        parentContext("user-a", "parent-a"),
                        parentState(parentPermissions("parent-v1")),
                        "worker",
                        null,
                        "review",
                        null,
                        null)
                .block();

        ReActAgent child = (ReActAgent) childRef.get();
        PermissionContextState actual =
                child.getAgentState("user-a", childSessionId("parent-a", "review"))
                        .getPermissionContext();
        assertEquals(childPermissions, actual);
        assertFalse(actual.getDenyRules().containsKey("Bash"));
    }

    @Test
    void persistentReuseReappliesCurrentParentPermissionContext() {
        AtomicReference<Agent> childRef = new AtomicReference<>();
        AtomicInteger factoryCalls = new AtomicInteger();
        AgentSpawnTool tool =
                tool(childPermissions(), declaration(true), childRef, factoryCalls, false);
        RuntimeContext parentContext = parentContext("user-a", "parent-a");
        AgentState parentState = parentState(parentPermissions("parent-v1"));

        tool.agentSpawn(parentContext, parentState, "worker", null, "review", null, null).block();
        parentState.setPermissionContext(parentPermissions("parent-v2"));
        tool.agentSpawn(parentContext, parentState, "worker", null, "review", null, null).block();

        ReActAgent child = (ReActAgent) childRef.get();
        PermissionContextState actual =
                child.getAgentState("user-a", childSessionId("parent-a", "review"))
                        .getPermissionContext();
        assertTrue(
                actual.getAskRules().get("Write").stream()
                        .anyMatch(rule -> "parent-v2".equals(rule.source())));
        assertFalse(
                actual.getAllowRules().get("Read").stream()
                        .anyMatch(rule -> "parent-v1".equals(rule.source())),
                "persistent reuse must revoke rules removed from the parent context");
        assertEquals(
                1, factoryCalls.get(), "persistent reuse must not materialize a throwaway child");
    }

    @Test
    void exposedChildCarriesOwningUserAndParentSessionToGateway() {
        AtomicReference<Agent> childRef = new AtomicReference<>();
        AgentSpawnTool tool =
                tool(childPermissions(), declaration(true), childRef, new AtomicInteger(), false);
        AtomicReference<List<String>> lineage = new AtomicReference<>();
        tool.setGatewayBridge(
                (agentId, sessionId, agent, replyTo, userId, parentSessionId) -> {
                    lineage.set(List.of(agentId, sessionId, userId, parentSessionId));
                    return new SubagentGatewayBridge.ExposeResult("sub-visible");
                });

        tool.agentSpawn(
                        parentContext("user-a", "parent-a"),
                        parentState(parentPermissions("parent-v1")),
                        "worker",
                        null,
                        "review",
                        null,
                        true)
                .block();

        assertEquals(
                List.of("worker", childSessionId("parent-a", "review"), "user-a", "parent-a"),
                lineage.get());
    }

    private AgentSpawnTool tool(
            PermissionContextState childPermissions,
            SubagentDeclaration declaration,
            AtomicReference<Agent> childRef,
            AtomicInteger factoryCalls,
            boolean harnessChild) {
        SubagentFactory factory =
                rc -> {
                    factoryCalls.incrementAndGet();
                    Agent child;
                    if (harnessChild) {
                        child =
                                HarnessAgent.builder()
                                        .name("worker")
                                        .model(new MockModel(messages -> List.of()))
                                        .workspace(workspace)
                                        .abstractFilesystem(new LocalFilesystem(workspace))
                                        .permissionContext(childPermissions)
                                        .disableMemoryTools()
                                        .disableMemoryHooks()
                                        .disableWorkspaceContext()
                                        .build();
                    } else {
                        child =
                                ReActAgent.builder()
                                        .name("worker")
                                        .model(new MockModel(messages -> List.of()))
                                        .permissionContext(childPermissions)
                                        .build();
                    }
                    childRef.set(child);
                    return child;
                };
        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(
                                new SubagentEntry(
                                        "worker", "permission worker", factory, declaration)),
                        null);
        return new AgentSpawnTool(manager, null, 0);
    }

    private static SubagentDeclaration declaration(boolean inherit) {
        return SubagentDeclaration.builder()
                .name("worker")
                .description("permission worker")
                .inlineAgentsBody("Review permissions")
                .persistSession(true)
                .inheritParentPermissions(inherit)
                .build();
    }

    private static RuntimeContext parentContext(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    private static AgentState parentState(PermissionContextState permissions) {
        return AgentState.builder().sessionId("parent-a").permissionContext(permissions).build();
    }

    private static PermissionContextState childPermissions() {
        return PermissionContextState.builder()
                .mode(PermissionMode.ACCEPT_EDITS)
                .addWorkingDirectory(
                        "child", new AdditionalWorkingDirectory("/child", "child-settings"))
                .addAllowRule(
                        "Read",
                        new PermissionRule("Read", "child/**", PermissionBehavior.ALLOW, "child"))
                .build();
    }

    private static PermissionContextState parentPermissions(String source) {
        return PermissionContextState.builder()
                .mode(PermissionMode.DONT_ASK)
                .addWorkingDirectory(
                        "parent", new AdditionalWorkingDirectory("/parent", "parent-settings"))
                .addAllowRule(
                        "Read",
                        new PermissionRule("Read", "parent/**", PermissionBehavior.ALLOW, source))
                .addDenyRule(
                        "Bash",
                        new PermissionRule("Bash", "rm -rf", PermissionBehavior.DENY, source))
                .addAskRule(
                        "Write",
                        new PermissionRule("Write", "/etc/**", PermissionBehavior.ASK, source))
                .build();
    }

    private static String childSessionId(String parentSessionId, String label) {
        return "sub-" + AgentSpawnTool.deterministicHash(parentSessionId, "worker", label);
    }

    private static void assertInheritedContext(
            PermissionContextState inherited, String parentSource) {
        assertEquals(PermissionMode.ACCEPT_EDITS, inherited.getMode());
        assertTrue(inherited.getWorkingDirectories().containsKey("child"));
        assertTrue(inherited.getWorkingDirectories().containsKey("parent"));
        assertTrue(
                inherited.getAllowRules().get("Read").stream()
                        .anyMatch(rule -> parentSource.equals(rule.source())));
        assertTrue(
                inherited.getDenyRules().get("Bash").stream()
                        .anyMatch(rule -> parentSource.equals(rule.source())));
        assertTrue(
                inherited.getAskRules().get("Write").stream()
                        .anyMatch(rule -> parentSource.equals(rule.source())));
    }
}
