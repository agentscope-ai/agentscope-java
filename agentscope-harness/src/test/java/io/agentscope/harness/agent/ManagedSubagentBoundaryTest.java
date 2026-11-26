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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

class ManagedSubagentBoundaryTest {
    @TempDir Path workspace;

    @Test
    void declarationFiltersAutoRegisteredToolsAsWellAsInheritedTools() {
        var parent =
                HarnessAgent.builder()
                        .model(Mockito.mock(Model.class))
                        .workspace(workspace)
                        .filesystem(new LocalFilesystemSpec());
        var declaration =
                SubagentDeclaration.builder()
                        .name("reader")
                        .description("read only")
                        .inlineAgentsBody("read only")
                        .tools(List.of("read_file"))
                        .build();
        var child =
                (HarnessAgent)
                        HarnessAgentBuilderSupport.buildDeclaredFactory(
                                        parent, declaration, workspace, null)
                                .create(
                                        RuntimeContext.builder()
                                                .userId("alice")
                                                .sessionId("session")
                                                .build());
        Assertions.assertEquals(java.util.Set.of("read_file"), child.getToolkit().getToolNames());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothFactoriesInheritDisabledWebToolsWithoutChangingOtherCapabilities(boolean declared) {
        var parent =
                HarnessAgent.builder()
                        .model(Mockito.mock(Model.class))
                        .workspace(workspace)
                        .filesystem(new LocalFilesystemSpec())
                        .disableDefaultWorkspaceSkills();
        var declaration =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("worker")
                        .inlineAgentsBody("work")
                        .build();
        var enabledFactory =
                declared
                        ? HarnessAgentBuilderSupport.buildDeclaredFactory(
                                parent, declaration, workspace, null)
                        : HarnessAgentBuilderSupport.buildGeneralPurposeFactory(
                                parent, workspace, null);
        parent.disableWebTools();
        var disabledFactory =
                declared
                        ? HarnessAgentBuilderSupport.buildDeclaredFactory(
                                parent, declaration, workspace, null)
                        : HarnessAgentBuilderSupport.buildGeneralPurposeFactory(
                                parent, workspace, null);
        try (var enabled = (HarnessAgent) enabledFactory.create(RuntimeContext.empty());
                var disabled = (HarnessAgent) disabledFactory.create(RuntimeContext.empty())) {
            var enabledNames = enabled.getToolkit().getToolNames();
            var disabledNames = disabled.getToolkit().getToolNames();
            Assertions.assertTrue(enabledNames.containsAll(Set.of("web_fetch", "web_search")));
            var expected = new HashSet<>(enabledNames);
            expected.removeAll(Set.of("web_fetch", "web_search"));
            Assertions.assertEquals(expected, disabledNames);
            Assertions.assertTrue(disabledNames.containsAll(Set.of("read_file", "execute")));
        }
    }

    @Test
    void explicitChildWebAllowCannotExpandParentDisable() {
        var parent =
                HarnessAgent.builder()
                        .model(Mockito.mock(Model.class))
                        .workspace(workspace)
                        .filesystem(new LocalFilesystemSpec())
                        .disableWebTools();
        var declaration =
                SubagentDeclaration.builder()
                        .name("reader")
                        .description("reader")
                        .inlineAgentsBody("read")
                        .tools(List.of("web_fetch", "web_search", "read_file"))
                        .build();
        var factory =
                HarnessAgentBuilderSupport.buildDeclaredFactory(
                        parent, declaration, workspace, null);
        try (var child = (HarnessAgent) factory.create(RuntimeContext.empty())) {
            Assertions.assertEquals(Set.of("read_file"), child.getToolkit().getToolNames());
        }
    }

    @Test
    void childCannotReenableParentDeniedTool() {
        var cfg = new ToolsConfig();
        cfg.setDeny(List.of("execute"));
        var parent =
                HarnessAgent.builder()
                        .model(Mockito.mock(Model.class))
                        .workspace(workspace)
                        .toolsConfig(cfg)
                        .filesystem(new LocalFilesystemSpec());
        var declaration =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("worker")
                        .inlineAgentsBody("work")
                        .tools(List.of("execute"))
                        .build();
        var child =
                (HarnessAgent)
                        HarnessAgentBuilderSupport.buildDeclaredFactory(
                                        parent, declaration, workspace, null)
                                .create(RuntimeContext.empty());
        Assertions.assertTrue(child.getToolkit().getToolNames().isEmpty());
    }
}
