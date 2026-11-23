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
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.harness.agent.sandbox.layout.FileEntry;
import io.agentscope.harness.agent.sandbox.layout.LocalDirEntry;
import io.agentscope.harness.agent.sandbox.layout.LocalFileEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSpecApplierTest {

    @TempDir Path tempDir;

    @Test
    void localFileEntry_inheritsTheExecutableBitOfItsSource() throws Exception {
        Path source = tempDir.resolve("source/run.sh");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "#!/bin/sh\necho hi\n");
        boolean posix = supportsPosix(source);
        if (posix) {
            Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root);
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().put("bin/run.sh", new LocalFileEntry(source.toString()));

        new WorkspaceSpecApplier(root).applyWorkspaceSpec(spec, false);

        Path applied = root.resolve("bin/run.sh");
        // The bytes travel on every filesystem; only the permission assertion needs POSIX.
        assertEquals("#!/bin/sh\necho hi\n", Files.readString(applied));
        assumeTrue(posix, "POSIX permissions are required for the permission assertion");
        // Files.copy without COPY_ATTRIBUTES would leave this at 0644 and the script would
        // fail with "Permission denied" when executed from the workspace.
        assertEquals(
                PosixFilePermissions.fromString("rwxr-xr-x"),
                Files.getPosixFilePermissions(applied));
        assertTrue(Files.isExecutable(applied));
    }

    @Test
    void localDirEntry_keepsTheModeOfEveryCopiedFile() throws Exception {
        Path source = tempDir.resolve("skills/demo");
        Files.createDirectories(source.resolve("bin"));
        Files.writeString(source.resolve("bin/run.sh"), "#!/bin/sh\n");
        Files.writeString(source.resolve("SKILL.md"), "# demo\n");
        boolean posix = supportsPosix(source);
        if (posix) {
            Files.setPosixFilePermissions(
                    source.resolve("bin/run.sh"), PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(
                    source.resolve("SKILL.md"), PosixFilePermissions.fromString("rw-r--r--"));
        }

        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root);
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().put("skills/demo", new LocalDirEntry(source.toString()));

        new WorkspaceSpecApplier(root).applyWorkspaceSpec(spec, false);

        Path copiedScript = root.resolve("skills/demo/bin/run.sh");
        Path copiedDoc = root.resolve("skills/demo/SKILL.md");
        assertEquals("#!/bin/sh\n", Files.readString(copiedScript));
        assertEquals("# demo\n", Files.readString(copiedDoc));
        assumeTrue(posix, "POSIX permissions are required for the permission assertions");
        // The tree is copied file by file: the executable bit travels with the script, the
        // data file must not pick it up.
        assertEquals(
                PosixFilePermissions.fromString("rwxr-xr-x"),
                Files.getPosixFilePermissions(copiedScript));
        assertFalse(Files.isExecutable(copiedDoc));
    }

    @Test
    void fileEntryContentKeepsThePlatformDefaultPermissions() throws Exception {
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root);
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().put("notes.txt", new FileEntry("inline content"));

        new WorkspaceSpecApplier(root).applyWorkspaceSpec(spec, false);

        Path applied = root.resolve("notes.txt");
        assertEquals("inline content", Files.readString(applied));
        assumeTrue(supportsPosix(root), "POSIX permissions are required for this assertion");
        // Inline content has no host source to inherit from; it must stay a plain data file.
        assertFalse(Files.isExecutable(applied));
    }

    private static boolean supportsPosix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }
}
