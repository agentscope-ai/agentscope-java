/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Smoke tests for package-visible remote helpers on {@link AgentSpawnTool}. */
class AgentSpawnToolRemoteHelpersTest {

    @Test
    void buildRemoteSourcePath_joinsParentSessionAndAgentId() {
        assertEquals(
                "parent-sess/worker",
                AgentSpawnTool.buildRemoteSourcePath("parent-sess", "worker"));
    }

    @Test
    void buildRemoteSourcePath_defaultsBlankParts() {
        assertEquals("main/remote", AgentSpawnTool.buildRemoteSourcePath(null, null));
        assertEquals("main/remote", AgentSpawnTool.buildRemoteSourcePath("  ", ""));
        assertEquals("sess/remote", AgentSpawnTool.buildRemoteSourcePath("sess", null));
        assertEquals("main/agent", AgentSpawnTool.buildRemoteSourcePath(null, "agent"));
    }

    @Test
    void tagRemoteForwardedEvent_setsSourceAndTaskIdMetadata() {
        TextBlockDeltaEvent event = new TextBlockDeltaEvent(null, "b1", "hello");
        event.withMetadataEntry("keep", "me");

        AgentEvent tagged =
                AgentSpawnTool.tagRemoteForwardedEvent(event, "parent/worker", "task_abc");

        assertEquals("parent/worker", tagged.getSource());
        assertEquals("task_abc", tagged.getMetadata().get(AgentEvent.METADATA_TASK_ID));
        assertEquals("me", tagged.getMetadata().get("keep"));
    }

    @Test
    void collectParentDenyRules_returnsEmptyWhenInheritDisabled() {
        AgentState parent =
                AgentState.builder()
                        .userId("u")
                        .sessionId("s")
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.BYPASS)
                                        .addDenyRule(
                                                "bash",
                                                new PermissionRule(
                                                        "bash",
                                                        null,
                                                        PermissionBehavior.DENY,
                                                        "parent"))
                                        .build())
                        .build();
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("d")
                        .inheritParentPermissions(false)
                        .build();

        List<Map<String, String>> rules =
                AgentSpawnTool.collectParentDenyRules(parent, Optional.of(decl));
        assertTrue(rules.isEmpty());
    }

    @Test
    void collectParentDenyRules_flattensDenyRulesWhenInheritEnabled() {
        PermissionRule deny =
                new PermissionRule("bash", "rm*", PermissionBehavior.DENY, "parent-policy");
        AgentState parent =
                AgentState.builder()
                        .userId("u")
                        .sessionId("s")
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.BYPASS)
                                        .addDenyRule("bash", deny)
                                        .build())
                        .build();

        List<Map<String, String>> rules =
                AgentSpawnTool.collectParentDenyRules(parent, Optional.empty());

        assertEquals(1, rules.size());
        assertEquals("bash", rules.get(0).get("tool_name"));
        assertEquals("rm*", rules.get(0).get("rule_content"));
        assertEquals(PermissionBehavior.DENY.name(), rules.get(0).get("behavior"));
        assertEquals("parent-policy", rules.get(0).get("source"));
    }
}
