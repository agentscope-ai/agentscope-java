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
package io.agentscope.builder.web.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.SessionAgentBuildSpec;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.tool.WebTools;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarnessAgentBuildServiceCacheKeyTest {

    private static final SessionAgentBuildSpec SPEC =
            new SessionAgentBuildSpec(1, "env-1", null, null, null, null);

    private static ManagedSessionDto session(String sessionId, String externalKey) {
        return new ManagedSessionDto(
                sessionId,
                "owner-1",
                "bbb",
                "owner-1",
                1,
                "latest",
                null,
                "env-1",
                externalKey,
                null,
                null,
                null,
                "idle",
                null,
                0L,
                0L,
                null);
    }

    @Test
    void credentialRevisionInvalidatesMaterializationWithoutHashingSecrets() {
        var one =
                new io.agentscope.builder.control.SessionResolveResult(
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        List.of(Map.of("id", "credential", "revision", 1, "secret", "first")),
                        null,
                        null,
                        null);
        var rotated =
                new io.agentscope.builder.control.SessionResolveResult(
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        List.of(Map.of("id", "credential", "revision", 2, "secret", "second")),
                        null,
                        null,
                        null);
        assertThat(HarnessAgentBuildService.materializationFingerprint(one, SPEC))
                .isNotEqualTo(HarnessAgentBuildService.materializationFingerprint(rotated, SPEC));
        var noSecret =
                new io.agentscope.builder.control.SessionResolveResult(
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        List.of(Map.of("id", "credential", "revision", 1)),
                        null,
                        null,
                        null);
        assertThat(HarnessAgentBuildService.materializationFingerprint(one, SPEC))
                .isEqualTo(HarnessAgentBuildService.materializationFingerprint(noSecret, SPEC));
    }

    @Test
    void plainSessionsOfSameAgentDoNotShareResourceInstances() {
        assertThat(HarnessAgentBuildService.cacheKey(session("s1", null), SPEC))
                .isNotEqualTo(HarnessAgentBuildService.cacheKey(session("s2", null), SPEC));
    }

    @Test
    void sharedKnowledgeCannotBeUsedToPublishPrivateWork() {
        assertThat(HarnessAgentBuildService.sharedKnowledgeAccess(List.of("knowledge")))
                .containsEntry("knowledge", "read_only");
        assertThat(HarnessAgentBuildService.sharedKnowledgeAccess(null)).isEmpty();
    }

    @Test
    void sessionDirectoriesAreIsolatedEvenForPathLikeIdentifiers(@TempDir Path workspaceRoot) {
        var paths = new io.agentscope.builder.web.workspace.SharedWorkspacePaths(workspaceRoot);
        var first = paths.resolveSessionDataPath("owner", "../other");
        assertThat(first.startsWith(workspaceRoot.resolve("sessions"))).isTrue();
        assertThat(first).isEqualTo(paths.resolveSessionDataPath("owner", "../other"));
        assertThat(first).isNotEqualTo(paths.resolveSessionDataPath("owner", "session-b"));
        assertThat(first).isNotEqualTo(paths.resolveSessionDataPath("another-owner", "../other"));
    }

    @Test
    void taskAttemptsUseIsolatedRuntimeInstances() {
        Map<String, Object> workerContext =
                Map.of("attemptId", "attempt-worker", "dispatchGeneration", 1);
        Map<String, Object> leadContext =
                Map.of("attemptId", "attempt-lead", "dispatchGeneration", 1);
        Map<String, Object> retryContext =
                Map.of("attemptId", "attempt-worker", "dispatchGeneration", 2);
        String worker =
                HarnessAgentBuildService.cacheKey(
                        session("s1", "agent-task|f92cf745-82f5-45f3-a273-8ad96a87aa5a"),
                        SPEC,
                        workerContext);
        String lead =
                HarnessAgentBuildService.cacheKey(
                        session("s2", "agent-task|85254b55-1a6d-4e5c-9499-d913561930c2"),
                        SPEC,
                        leadContext);
        String retry =
                HarnessAgentBuildService.cacheKey(
                        session("s1", "agent-task|f92cf745-82f5-45f3-a273-8ad96a87aa5a"),
                        SPEC,
                        retryContext);
        String plain = HarnessAgentBuildService.cacheKey(session("s3", null), SPEC);

        assertThat(worker).isNotEqualTo(lead).isNotEqualTo(retry).isNotEqualTo(plain);
    }

    @Test
    void managedPromptIncludesProtocolButNeverCredentials() {
        Map<String, Object> executionContext =
                Map.of(
                        "taskContext",
                        Map.of(
                                "taskToken",
                                "super-secret-token",
                                "issue",
                                Map.of("title", "Fix managed task"),
                                "availableActions",
                                List.of("task.respond", "task.complete")));

        String prompt =
                HarnessAgentBuildService.appendManagedExecutionPrompt(
                        "base instructions", executionContext);

        assertThat(prompt)
                .contains(
                        "base instructions",
                        "Managed AgentTask protocol",
                        "Fix managed task",
                        "immediately calls task.complete and stops",
                        "must not wait for workers inside that turn")
                .doesNotContain("super-secret-token", "taskToken");
    }

    @Test
    void managedTaskInjectsFencedCollaborationMcpTools() {
        ToolsConfig source = new ToolsConfig();
        source.setAllow(List.of("shell_execute"));
        source.setDeny(List.of("task.complete", "unsafe"));
        Map<String, Object> executionContext =
                Map.of(
                        "taskContext",
                        Map.of(
                                "taskToken",
                                "fenced-token",
                                "availableActions",
                                List.of("task.respond", "task.complete")));

        ToolsConfig merged =
                HarnessAgentBuildService.withManagedCollaborationTools(
                        source, executionContext, "http://control/mcp/collaboration");

        McpServerConfig mcp = merged.getMcpServers().get("aistio-collaboration");
        assertThat(mcp.getTransport()).isEqualTo("http");
        assertThat(mcp.getUrl()).isEqualTo("http://control/mcp/collaboration");
        assertThat(mcp.getHeaders()).containsEntry("X-Agent-Task-Token", "fenced-token");
        assertThat(mcp.getEnableTools()).containsExactly("task.respond", "task.complete");
        assertThat(merged.getAllow()).contains("shell_execute", "task.respond", "task.complete");
        assertThat(merged.getDeny()).containsExactly("unsafe");
        assertThat(source.getDeny()).containsExactly("task.complete", "unsafe");
    }

    @Test
    void managedTaskAllowsOnlyItsTokenScopedCollaborationActions() {
        Map<String, Object> executionContext =
                Map.of(
                        "taskContext",
                        Map.of(
                                "availableActions",
                                List.of("task.start", "task.respond", "task.complete")));

        PermissionContextState permissions =
                HarnessAgentBuildService.managedTaskPermissionContext(executionContext);

        assertThat(permissions).isNotNull();
        assertThat(permissions.getMode()).isEqualTo(PermissionMode.BYPASS);
        assertThat(permissions.getAllowRules())
                .containsOnlyKeys("task.start", "task.respond", "task.complete");
        assertThat(permissions.getAllowRules().get("task.complete"))
                .allMatch(rule -> rule.behavior() == PermissionBehavior.ALLOW);
        assertThat(permissions.getDenyRules()).isEmpty();
        assertThat(permissions.getAskRules()).isEmpty();
    }

    @Test
    void managedTaskDoesNotCreateUnresumableCorePromptForReadOnlyBuiltin() {
        PermissionContextState permissions =
                HarnessAgentBuildService.managedTaskPermissionContext(
                        Map.of(
                                "taskContext",
                                Map.of("availableActions", List.of("task.complete"))));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WebTools.WebSearchTool());
        ToolBase webSearch = (ToolBase) toolkit.getTool("web_search");

        assertThat(
                        new PermissionEngine(permissions)
                                .checkPermission(webSearch, Map.of("query", "phone industry"))
                                .block()
                                .getBehavior())
                .isEqualTo(PermissionBehavior.ALLOW);
    }
}
