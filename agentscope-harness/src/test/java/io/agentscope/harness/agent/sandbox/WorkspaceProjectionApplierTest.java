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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceProjectionApplierTest {

    @TempDir Path tempDir;

    @Test
    void build_returnsNullWhenNoProjectionEntry() throws Exception {
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().clear();
        assertNull(WorkspaceProjectionApplier.build(spec));
    }

    @Test
    void compatConstructorKeepsThePreManifestThreeArgumentShape() {
        // Source and binary compatibility: the three-argument constructor predates the
        // recorded path list and must keep compiling and linking for existing callers.
        WorkspaceProjectionApplier.ProjectionPayload payload =
                new WorkspaceProjectionApplier.ProjectionPayload("h", new byte[0], 0);
        assertEquals("h", payload.hash());
        assertEquals(0, payload.fileCount());
        assertTrue(payload.paths().isEmpty());
    }

    @Test
    void build_producesDeterministicHashAndTar() throws Exception {
        Path source = tempDir.resolve("source");
        Files.createDirectories(source.resolve("skills/java"));
        Files.writeString(source.resolve("AGENTS.md"), "v1");
        Files.writeString(source.resolve("skills/java/SKILL.md"), "skill");
        Files.writeString(source.resolve("other.txt"), "ignored");

        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(source.toString());
        projection.setIncludeRoots(java.util.List.of("AGENTS.md", "skills"));

        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().put("__workspace_projection__", projection);

        WorkspaceProjectionApplier.ProjectionPayload p1 = WorkspaceProjectionApplier.build(spec);
        WorkspaceProjectionApplier.ProjectionPayload p2 = WorkspaceProjectionApplier.build(spec);
        assertNotNull(p1);
        assertNotNull(p2);
        assertEquals(p1.hash(), p2.hash());
        assertEquals(2, p1.fileCount());

        // The entry mode follows the source file instead of a constant: a plain file must not
        // arrive executable in the sandbox on any host, and where the filesystem carries POSIX
        // permissions the entry reproduces them.
        assertEquals(
                supportsPosix(source)
                        ? TarFileModes.toMode(
                                Files.getPosixFilePermissions(source.resolve("AGENTS.md")))
                        : TarFileModes.DEFAULT_FILE_MODE,
                firstEntryMode(p1) & 0777);

        Path dest = tempDir.resolve("dest");
        Files.createDirectories(dest);
        WorkspaceArchiveExtractor.extractTarArchive(dest, new ByteArrayInputStream(p1.tarBytes()));
        assertEquals("v1", Files.readString(dest.resolve("AGENTS.md")));
        assertEquals("skill", Files.readString(dest.resolve("skills/java/SKILL.md")));
    }

    @Test
    void build_narrowedCacheRootsProjectOnlyTheGrantedSubtree() throws Exception {
        // Grant-layer scenario: beta was staged under an earlier, wider grant and still sits
        // on disk; narrowing the .skills-cache include-root to alpha's explicit subtree must
        // keep beta out of the hydrated sandbox workspace.
        Path source = tempDir.resolve("ws");
        Files.createDirectories(source.resolve(".skills-cache/u1/market/alpha"));
        Files.createDirectories(source.resolve(".skills-cache/u1/market/beta"));
        Files.writeString(source.resolve(".skills-cache/u1/market/alpha/SKILL.md"), "alpha");
        Files.writeString(source.resolve(".skills-cache/u1/market/beta/SKILL.md"), "beta");
        Files.writeString(source.resolve("AGENTS.md"), "root");

        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(source.toString());
        projection.setIncludeRoots(java.util.List.of("AGENTS.md", ".skills-cache/u1/market/alpha"));

        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().put("__workspace_projection__", projection);

        WorkspaceProjectionApplier.ProjectionPayload payload =
                WorkspaceProjectionApplier.build(spec);
        assertNotNull(payload);
        assertEquals(
                2,
                payload.fileCount(),
                "only AGENTS.md and the granted alpha subtree project; beta residue stays out");

        Path dest = tempDir.resolve("dest");
        Files.createDirectories(dest);
        WorkspaceArchiveExtractor.extractTarArchive(
                dest, new ByteArrayInputStream(payload.tarBytes()));
        assertEquals(
                "alpha", Files.readString(dest.resolve(".skills-cache/u1/market/alpha/SKILL.md")));
        assertTrue(
                Files.notExists(dest.resolve(".skills-cache/u1/market/beta")),
                "the ungranted residue must not be hydrated into the sandbox workspace");
    }

    @Test
    void build_keepsTheExecutableBitOnProjectedFiles() throws Exception {
        // A projected shell script has to stay executable inside the sandbox: the backend
        // extracts the payload with tar, so the entry mode is what the file ends up with.
        Path source = tempDir.resolve("source-bin");
        Files.createDirectories(source.resolve("bin"));
        Path script = source.resolve("bin/run.sh");
        Files.writeString(script, "#!/bin/sh\necho hi\n");
        assumeTrue(
                supportsPosix(source),
                "the executable bit can only be expressed on a POSIX filesystem");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));

        WorkspaceProjectionApplier.ProjectionPayload payload =
                WorkspaceProjectionApplier.build(projectionOf(source, "bin"));
        assertNotNull(payload);

        try (TarArchiveInputStream tar =
                new TarArchiveInputStream(new ByteArrayInputStream(payload.tarBytes()))) {
            TarArchiveEntry entry = tar.getNextEntry();
            assertNotNull(entry);
            assertEquals("bin/run.sh", entry.getName());
            assertEquals(
                    0755,
                    entry.getMode() & 0777,
                    "the payload must carry the source permissions, not the tar default");
        }

        Path dest = tempDir.resolve("dest-bin");
        Files.createDirectories(dest);
        WorkspaceArchiveExtractor.extractTarArchive(
                dest, new ByteArrayInputStream(payload.tarBytes()));
        assertTrue(
                Files.isExecutable(dest.resolve("bin/run.sh")),
                "the hydrated file must still be executable");
    }

    @Test
    void build_hashChangesWhenOnlyTheModeChanges() throws Exception {
        // The projection hash gates re-hydration. A chmod that leaves the bytes alone still
        // has to re-project, otherwise the sandbox keeps serving the stale mode.
        Path source = tempDir.resolve("source-chmod");
        Files.createDirectories(source);
        Path script = source.resolve("run.sh");
        Files.writeString(script, "#!/bin/sh\n");
        assumeTrue(
                supportsPosix(source),
                "the executable bit can only be expressed on a POSIX filesystem");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rw-r--r--"));

        WorkspaceProjectionApplier.ProjectionPayload before =
                WorkspaceProjectionApplier.build(projectionOf(source, "run.sh"));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        WorkspaceProjectionApplier.ProjectionPayload after =
                WorkspaceProjectionApplier.build(projectionOf(source, "run.sh"));

        assertNotNull(before);
        assertNotNull(after);
        assertNotEquals(before.hash(), after.hash());
    }

    @Test
    void build_hashChangesWhenProjectedContentChanges() throws Exception {
        Path source = tempDir.resolve("source2");
        Files.createDirectories(source);
        Files.writeString(source.resolve("AGENTS.md"), "v1");

        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(source.toString());
        projection.setIncludeRoots(java.util.List.of("AGENTS.md"));

        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().put("__workspace_projection__", projection);

        WorkspaceProjectionApplier.ProjectionPayload p1 = WorkspaceProjectionApplier.build(spec);
        Files.writeString(source.resolve("AGENTS.md"), "v2");
        WorkspaceProjectionApplier.ProjectionPayload p2 = WorkspaceProjectionApplier.build(spec);

        assertNotNull(p1);
        assertNotNull(p2);
        assertNotEquals(p1.hash(), p2.hash());
    }

    private static WorkspaceSpec projectionOf(Path source, String... includeRoots) {
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(source.toString());
        projection.setIncludeRoots(java.util.List.of(includeRoots));

        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().put("__workspace_projection__", projection);
        return spec;
    }

    private static boolean supportsPosix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static int firstEntryMode(WorkspaceProjectionApplier.ProjectionPayload payload)
            throws Exception {
        try (TarArchiveInputStream tar =
                new TarArchiveInputStream(new ByteArrayInputStream(payload.tarBytes()))) {
            return tar.getNextEntry().getMode();
        }
    }
}
