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
package io.agentscope.core.tool.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileToolSymlinkTest {
    @TempDir Path tempDir;

    @Test
    void readFileToolRejectsSymlinkOutsideBaseDir() throws IOException {
        Path outside = createOutsideSymlink();

        ToolResultBlock result =
                new ReadFileTool(tempDir.resolve("workspace").toString())
                        .viewTextFile("link.txt", null)
                        .block(Duration.ofSeconds(5));

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(((TextBlock) result.getOutput().get(0)).getText().contains("Access denied"));
        assertEquals("secret", Files.readString(outside));
    }

    @Test
    void writeFileToolRejectsSymlinkOutsideBaseDir() throws IOException {
        Path outside = createOutsideSymlink();

        ToolResultBlock result =
                new WriteFileTool(tempDir.resolve("workspace").toString())
                        .writeTextFile("link.txt", "changed", null)
                        .block(Duration.ofSeconds(5));

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(((TextBlock) result.getOutput().get(0)).getText().contains("Access denied"));
        assertEquals("secret", Files.readString(outside));
    }

    private Path createOutsideSymlink() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "secret");
        try {
            Files.createSymbolicLink(workspace.resolve("link.txt"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("Symbolic links are unavailable: " + e.getMessage());
        }
        return outside;
    }
}
