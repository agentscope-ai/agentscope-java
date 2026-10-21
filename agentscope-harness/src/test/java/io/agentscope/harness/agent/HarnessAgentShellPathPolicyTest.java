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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager;
import io.agentscope.harness.agent.skill.runtime.ShellPathPolicy;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Direct coverage of {@link HarnessAgent.Builder#selectShellPathPolicy}. {@code
 * OverlayFilesystem.of} with a sandbox upper is a shell-aware overlay that previously fell
 * through to {@link ShellPathPolicy#noShell()} and now selects {@link
 * ShellPathPolicy#sandbox(String)}.
 */
class HarnessAgentShellPathPolicyTest {

    @TempDir Path workspace;

    @Test
    void overlayWithSandboxUpper_selectsSandboxShellPolicy() {
        AbstractFilesystem filesystem =
                OverlayFilesystem.of(
                        mock(SandboxBackedFilesystem.class), new LocalFilesystem(workspace));

        ShellPathPolicy policy =
                HarnessAgent.Builder.selectShellPathPolicy(
                        false, filesystem, workspace, sandboxContext("/opt/sandbox/workspace"));

        assertEquals(ShellPathPolicy.Mode.SANDBOX, policy.mode());
        assertEquals(
                "/opt/sandbox/workspace/skills/demo",
                policy.resolve("demo", new MarketplaceStager.StageResult.WorkspaceNative()));
    }

    @Test
    void routedSandboxPrimary_selectsSandboxShellPolicy() {
        AbstractFilesystem filesystem =
                new RoutedSandboxFilesystem(mock(SandboxBackedFilesystem.class), Map.of());

        ShellPathPolicy policy =
                HarnessAgent.Builder.selectShellPathPolicy(false, filesystem, workspace, null);

        assertEquals(ShellPathPolicy.Mode.SANDBOX, policy.mode());
        assertEquals(
                "/workspace/skills/demo",
                policy.resolve("demo", new MarketplaceStager.StageResult.WorkspaceNative()));
    }

    @Test
    void localShellOverlay_selectsLocalShellPolicy() {
        AbstractFilesystem filesystem =
                OverlayFilesystem.of(
                        new LocalFilesystemWithShell(workspace), new LocalFilesystem(workspace));

        ShellPathPolicy policy =
                HarnessAgent.Builder.selectShellPathPolicy(false, filesystem, workspace, null);

        assertEquals(ShellPathPolicy.Mode.LOCAL_WITH_SHELL, policy.mode());
        String expected = workspace.resolve("skills").resolve("demo").toAbsolutePath().toString();
        if (expected.indexOf(' ') >= 0) {
            expected = expected.replace(" ", "\\ ");
        }
        assertEquals(
                expected,
                policy.resolve("demo", new MarketplaceStager.StageResult.WorkspaceNative()));
    }

    @Test
    void disabledShell_selectsNoShellEvenForSandboxOverlay() {
        AbstractFilesystem filesystem =
                OverlayFilesystem.of(
                        mock(SandboxBackedFilesystem.class), new LocalFilesystem(workspace));

        ShellPathPolicy policy =
                HarnessAgent.Builder.selectShellPathPolicy(
                        true, filesystem, workspace, sandboxContext("/opt/sandbox/workspace"));

        assertEquals(ShellPathPolicy.Mode.NO_SHELL, policy.mode());
        assertNull(policy.resolve("demo", new MarketplaceStager.StageResult.WorkspaceNative()));
    }

    private static SandboxContext sandboxContext(String workspaceRoot) {
        SandboxClientOptions options =
                new SandboxClientOptions() {
                    @Override
                    public String getType() {
                        return "test";
                    }

                    @Override
                    public SandboxClient<? extends SandboxClientOptions> createClient() {
                        return null;
                    }

                    @Override
                    public String getWorkspaceRoot() {
                        return workspaceRoot;
                    }
                };
        return SandboxContext.builder().clientOptions(options).build();
    }
}
