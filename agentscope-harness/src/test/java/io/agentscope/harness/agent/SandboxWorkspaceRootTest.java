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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxWorkspaceRootTest {
    private RuntimeContext context(String root) {
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot(root);
        SandboxState state = mock(SandboxState.class);
        when(state.getWorkspaceSpec()).thenReturn(spec);
        Sandbox sandbox = mock(Sandbox.class);
        when(sandbox.getState()).thenReturn(state);
        return RuntimeContext.builder()
                .put(SandboxAcquireResult.class, SandboxAcquireResult.selfManaged(sandbox))
                .build();
    }

    @Test
    void prefixesFollowEachCallAndPreserveNonMatchingPaths() {
        var normalizer = WorkspacePathNormalizer.forSandbox("/workspace");
        var first = context("/home/user///");
        var second = context("/restored/session");
        assertEquals("a.txt", normalizer.normalize("/home/user/a.txt", first));
        assertEquals("a.txt", normalizer.normalize("/restored/session/a.txt", second));
        assertEquals("/home/user/a.txt", normalizer.normalize("/home/user/a.txt", second));
        assertEquals("/home/user2/a.txt", normalizer.normalize("/home/user2/a.txt", first));
        assertEquals("/workspace/a.txt", normalizer.normalize("/workspace/a.txt", first));
        assertEquals("a.txt", normalizer.normalize("a.txt", first));
        assertEquals(".", normalizer.normalize("/home/user", first));
        assertEquals("a.txt", normalizer.normalize("/workspace/a.txt", RuntimeContext.empty()));
        assertEquals("a.txt", normalizer.normalize("/a.txt", context("/")));
        assertEquals(".", normalizer.normalize("/", context("/")));
    }

    @Test
    void releasedAcquisitionCannotFallBackToAnotherRoot() {
        var context = context("/home/user");
        context.get(SandboxAcquireResult.class).beginRelease();
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () ->
                        WorkspacePathNormalizer.forSandbox("/workspace")
                                .normalize("/home/user/a", context));
    }

    @Test
    void registeredFileToolUsesCallSpecificRoot(@TempDir Path workspace) throws Exception {
        AbstractSandboxFilesystem fs = mock(AbstractSandboxFilesystem.class);
        when(fs.read(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(ReadResult.success(new FileData("example", "utf-8")));
        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("path-test")
                        .model(mock(Model.class))
                        .workspace(workspace)
                        .abstractFilesystem(fs)
                        .build()) {
            var tool = agent.getDelegate().getToolkit().getTool("read_file");
            var first = context("/home/user");
            var second = context("/restored/session");
            tool.callAsync(
                            ToolCallParam.builder()
                                    .runtimeContext(first)
                                    .input(Map.of("path", "/home/user/first.txt"))
                                    .build())
                    .block(Duration.ofSeconds(5));
            tool.callAsync(
                            ToolCallParam.builder()
                                    .runtimeContext(second)
                                    .input(Map.of("path", "/restored/session/second.txt"))
                                    .build())
                    .block(Duration.ofSeconds(5));
            verify(fs).read(same(first), eq("first.txt"), eq(0), eq(0));
            verify(fs).read(same(second), eq("second.txt"), eq(0), eq(0));
        }
    }
}
