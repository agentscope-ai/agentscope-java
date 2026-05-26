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
package io.agentscope.harness.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessAgentToolsConfigTest {

    @TempDir Path workspace;

    @Test
    void toolsJsonDeny_removesBuiltInTool() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(
                workspace.resolve(WorkspaceConstants.TOOLS_JSON),
                "{ \"deny\": [\"memory_search\"] }");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        List<String> toolNames = toolNames(agent);
        assertFalse(toolNames.contains("memory_search"));
        // Other built-ins still present
        assertTrue(toolNames.contains("read_file"));
    }

    @Test
    void toolsJsonAllow_keepsOnlyListedTools() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(
                workspace.resolve(WorkspaceConstants.TOOLS_JSON),
                "{ \"allow\": [\"read_file\", \"list_files\"] }");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        List<String> toolNames = toolNames(agent);
        assertTrue(toolNames.contains("read_file"));
        assertTrue(toolNames.contains("list_files"));
        assertFalse(toolNames.contains("memory_search"));
        assertFalse(toolNames.contains("memory_get"));
        assertFalse(toolNames.contains("session_search"));
    }

    @Test
    void disableToolsConfig_skipsFileEntirely() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(
                workspace.resolve(WorkspaceConstants.TOOLS_JSON),
                "{ \"deny\": [\"memory_search\"] }");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .disableToolsConfig()
                        .build();

        // memory_search should still be present because the file was not consulted.
        assertTrue(toolNames(agent).contains("memory_search"));
    }

    @Test
    void programmaticToolsConfig_overridesFile() throws Exception {
        Files.createDirectories(workspace);
        // File would deny read_file, but the programmatic override denies memory_search instead.
        Files.writeString(
                workspace.resolve(WorkspaceConstants.TOOLS_JSON), "{ \"deny\": [\"read_file\"] }");

        ToolsConfig override = new ToolsConfig();
        override.setDeny(List.of("memory_search"));

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .toolsConfig(override)
                        .build();

        List<String> toolNames = toolNames(agent);
        assertTrue(toolNames.contains("read_file"), "file deny must be ignored when override set");
        assertFalse(toolNames.contains("memory_search"), "override deny must apply");
    }

    @Test
    void malformedToolsJson_leavesToolkitIntact() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve(WorkspaceConstants.TOOLS_JSON), "{not json");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        // Loader returned empty for malformed JSON; default tools remain available.
        List<String> toolNames = toolNames(agent);
        assertTrue(toolNames.contains("memory_search"));
        assertTrue(toolNames.contains("read_file"));
    }

    private static List<String> toolNames(HarnessAgent agent) {
        return agent.getDelegate().getToolkit().getToolSchemas().stream()
                .map(ToolSchema::getName)
                .toList();
    }

    private static Model stubModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text("ok").build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }
}
