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

import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

        Path dest = tempDir.resolve("dest");
        Files.createDirectories(dest);
        WorkspaceArchiveExtractor.extractTarArchive(dest, new ByteArrayInputStream(p1.tarBytes()));
        assertEquals("v1", Files.readString(dest.resolve("AGENTS.md")));
        assertEquals("skill", Files.readString(dest.resolve("skills/java/SKILL.md")));
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
}
