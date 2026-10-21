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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FileToolBoundaryTest {
    @TempDir Path temp;
    private Path base;
    private Path outside;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createDirectory(temp.resolve("base"));
        outside = Files.createDirectory(temp.resolve("outside"));
    }

    @Test
    void readRejectsDirectoryLinkOutsideBase() throws IOException {
        Files.writeString(outside.resolve("secret.txt"), "outside secret");
        directoryLink(base.resolve("escape"), outside);
        ToolResultBlock result =
                new ReadFileTool(base.toString()).viewTextFile("escape/secret.txt", null).block();
        assertTrue(text(result).startsWith("Error: Access denied:"));
        assertFalse(text(result).contains("outside secret"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void writeRejectsFileLinkOutsideBase() throws IOException {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "original");
        Files.createSymbolicLink(base.resolve("linked.txt"), secret);
        ToolResultBlock result =
                new WriteFileTool(base.toString())
                        .writeTextFile("linked.txt", "changed", null)
                        .block();
        assertTrue(text(result).startsWith("Error: Access denied:"));
        assertEquals("original", Files.readString(secret));
    }

    @Test
    void createRejectsMissingDescendantsOfOutsideLink() throws IOException {
        directoryLink(base.resolve("escape"), outside);
        ToolResultBlock result =
                new WriteFileTool(base.toString())
                        .writeTextFile("escape/new-dir/new.txt", "changed", null)
                        .block();
        assertTrue(text(result).startsWith("Error: Access denied:"));
        assertFalse(Files.exists(outside.resolve("new-dir")));
    }

    @Test
    void permitsLinksWhoseTargetRemainsInsideBase() throws IOException {
        Path inside = Files.createDirectory(base.resolve("inside"));
        Files.writeString(inside.resolve("file.txt"), "inside");
        directoryLink(base.resolve("linked"), inside);
        ToolResultBlock result =
                new ReadFileTool(base.toString()).viewTextFile("linked/file.txt", null).block();
        assertTrue(text(result).contains("1: inside"));
    }

    @Test
    void permitsLinkedBaseDirectory() throws IOException {
        Files.writeString(base.resolve("inside.txt"), "inside");
        Path alias = directoryLink(temp.resolve("alias"), base);
        ToolResultBlock result =
                new ReadFileTool(alias.toString()).viewTextFile("inside.txt", null).block();
        assertTrue(text(result).contains("1: inside"));
    }

    @Test
    void createsFilesWhenBaseDirectoryDoesNotExistYet() throws IOException {
        Path missingBase = base.resolve("new-base");
        ToolResultBlock result =
                new WriteFileTool(missingBase.toString())
                        .writeTextFile("nested/new.txt", "created", null)
                        .block();
        assertFalse(text(result).startsWith("Error:"));
        assertEquals("created", Files.readString(missingBase.resolve("nested/new.txt")));
    }

    @Test
    void writeRejectsDirectoryLinkOutsideBase() throws IOException {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "original");
        directoryLink(base.resolve("escape"), outside);
        ToolResultBlock result =
                new WriteFileTool(base.toString())
                        .writeTextFile("escape/secret.txt", "changed", null)
                        .block();
        assertTrue(text(result).startsWith("Error: Access denied:"));
        assertEquals("original", Files.readString(secret));
    }

    private Path directoryLink(Path link, Path target) throws IOException {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            return Files.createSymbolicLink(link, target);
        }
        // Junctions exercise the same real-path boundary without Windows symlink privileges.
        Process process =
                new ProcessBuilder(
                                "cmd.exe",
                                "/d",
                                "/c",
                                "mklink",
                                "/J",
                                link.toString(),
                                target.toString())
                        .redirectErrorStream(true)
                        .start();
        byte[] output = process.getInputStream().readAllBytes();
        try {
            assertEquals(
                    0,
                    process.waitFor(),
                    new String(output, java.nio.charset.StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while creating test junction", e);
        }
        return link;
    }

    private String text(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
