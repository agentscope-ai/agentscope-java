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

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MemorySearchToolTest {
    private final WorkspaceManager workspace = mock(WorkspaceManager.class);
    private final MemorySearchTool tool = new MemorySearchTool(workspace);
    private final RuntimeContext context = RuntimeContext.empty();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingQueryIsAnError(String query) {
        assertEquals(
                "Error: No query provided",
                assertText(tool.memorySearch(context, query), ToolResultState.ERROR));
        verifyNoInteractions(workspace);
    }

    @Test
    void noMatchesIsASuccessfulSearch() {
        when(workspace.listMemoryFilePaths(context)).thenReturn(List.of("MEMORY.md"));
        when(workspace.readManagedWorkspaceFileUtf8(context, "MEMORY.md"))
                .thenReturn("Nothing relevant");
        assertEquals(
                "No matching memories found for: timeout",
                assertText(tool.memorySearch(context, "timeout"), ToolResultState.SUCCESS));
    }

    @Test
    void errorLookingMemoryIsData() {
        when(workspace.listMemoryFilePaths(context))
                .thenReturn(List.of("MEMORY.md", "memory/empty.md"));
        when(workspace.readManagedWorkspaceFileUtf8(context, "MEMORY.md"))
                .thenReturn("[ERROR] timeout\nError: timeout");
        String text = assertText(tool.memorySearch(context, "TIMEOUT"), ToolResultState.SUCCESS);
        assertTrue(text.contains("Found 2 matches:"));
        assertTrue(text.contains("MEMORY.md#1: [ERROR] timeout"));
        assertTrue(text.contains("MEMORY.md#2: Error: timeout"));
    }

    @Test
    void nullContextUsesEmptyContext() {
        when(workspace.listMemoryFilePaths(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        assertText(tool.memorySearch(null, "anything"), ToolResultState.SUCCESS);
    }
}
