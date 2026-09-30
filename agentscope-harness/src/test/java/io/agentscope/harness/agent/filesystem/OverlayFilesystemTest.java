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
package io.agentscope.harness.agent.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverlayFilesystemTest {

    @Test
    void getWorkspaceRoot_shellAware_delegatesToUpper(
            @TempDir Path workspace, @TempDir Path project) {
        PathPolicy policy = PathPolicy.of(project, workspace);
        LocalFilesystemWithShell upper =
                new LocalFilesystemWithShell(
                        workspace,
                        LocalFsMode.ROOTED,
                        policy,
                        120,
                        100_000,
                        null,
                        false,
                        null,
                        project);
        LocalFilesystem lower = new LocalFilesystem(workspace, true, 10, null);
        AbstractSandboxFilesystem overlay =
                (AbstractSandboxFilesystem) OverlayFilesystem.of(upper, lower);

        assertEquals(workspace.toAbsolutePath().normalize().toString(), overlay.getWorkspaceRoot());
    }
}
