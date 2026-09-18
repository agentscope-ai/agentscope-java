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
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
