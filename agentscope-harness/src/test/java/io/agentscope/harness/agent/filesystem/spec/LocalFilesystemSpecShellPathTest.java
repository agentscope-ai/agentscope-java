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
package io.agentscope.harness.agent.filesystem.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFilesystemSpecShellPathTest {

    @Test
    void shellPath_isUnsetByDefault() {
        assertNull(new LocalFilesystemSpec().getShellPath());
    }

    @Test
    void shellPath_treatsBlankAsUnset() {
        assertNull(new LocalFilesystemSpec().shellPath("   ").getShellPath());
        assertNull(new LocalFilesystemSpec().shellPath(null).getShellPath());
    }

    @Test
    void shellPath_trimsTheConfiguredValue() {
        assertEquals("bash", new LocalFilesystemSpec().shellPath(" bash ").getShellPath());
    }

    @Test
    void toFilesystem_rejectsShellThatCannotBeFound(@TempDir Path workspace) {
        LocalFilesystemSpec spec =
                new LocalFilesystemSpec().project(workspace).shellPath("no-such-shell-agentscope");

        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class, () -> spec.toFilesystem(workspace, null));

        assertTrue(
                thrown.getMessage().contains("no-such-shell-agentscope"),
                "message should name the executable, was: " + thrown.getMessage());
    }
}
