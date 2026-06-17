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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolDangerousPathConstantsTest {

    @Test
    void dangerousFilesListIsFrozen() {
        assertEquals(22, ToolDangerousPathConstants.DEFAULT_DANGEROUS_FILES.size());
        assertTrue(ToolDangerousPathConstants.DEFAULT_DANGEROUS_FILES.contains(".gitconfig"));
        assertTrue(ToolDangerousPathConstants.DEFAULT_DANGEROUS_FILES.contains(".bashrc"));
        assertTrue(
                ToolDangerousPathConstants.DEFAULT_DANGEROUS_FILES.contains(
                        ".ssh/authorized_keys"));
        assertTrue(ToolDangerousPathConstants.DEFAULT_DANGEROUS_FILES.contains(".pypirc"));
        assertTrue(ToolDangerousPathConstants.DEFAULT_DANGEROUS_FILES.contains(".env"));
        assertTrue(ToolDangerousPathConstants.DEFAULT_DANGEROUS_FILES.contains(".env.production"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ToolDangerousPathConstants.DEFAULT_DANGEROUS_FILES.add("evil"));
    }

    @Test
    void dangerousDirectoriesListIsFrozen() {
        assertEquals(4, ToolDangerousPathConstants.DEFAULT_DANGEROUS_DIRECTORIES.size());
        assertTrue(ToolDangerousPathConstants.DEFAULT_DANGEROUS_DIRECTORIES.contains(".git"));
        assertTrue(ToolDangerousPathConstants.DEFAULT_DANGEROUS_DIRECTORIES.contains(".ssh"));
        assertTrue(ToolDangerousPathConstants.DEFAULT_DANGEROUS_DIRECTORIES.contains(".vscode"));
        assertTrue(ToolDangerousPathConstants.DEFAULT_DANGEROUS_DIRECTORIES.contains(".idea"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ToolDangerousPathConstants.DEFAULT_DANGEROUS_DIRECTORIES.add("evil"));
    }

    @Test
    void dangerousCommandsListIsFrozen() {
        assertEquals(11, ToolDangerousPathConstants.DANGEROUS_COMMANDS.size());
        assertTrue(ToolDangerousPathConstants.DANGEROUS_COMMANDS.contains("rm -rf"));
        assertTrue(ToolDangerousPathConstants.DANGEROUS_COMMANDS.contains("> /dev/"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ToolDangerousPathConstants.DANGEROUS_COMMANDS.add("evil"));
    }
}
