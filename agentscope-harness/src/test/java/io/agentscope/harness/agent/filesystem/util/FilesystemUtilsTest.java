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
package io.agentscope.harness.agent.filesystem.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemUtilsTest {

    @TempDir Path workspace;

    @Test
    void isSandboxBacked_failsClosedAtDepthLimit() {
        AbstractFilesystem filesystem = new LocalFilesystem(workspace);
        for (int depth = 0; depth < 7; depth++) {
            filesystem = new OverlayFilesystem(filesystem, new LocalFilesystem(workspace));
        }
        assertFalse(FilesystemUtils.isSandboxBacked(filesystem));

        filesystem = new OverlayFilesystem(filesystem, new LocalFilesystem(workspace));
        assertTrue(FilesystemUtils.isSandboxBacked(filesystem));
    }
}
