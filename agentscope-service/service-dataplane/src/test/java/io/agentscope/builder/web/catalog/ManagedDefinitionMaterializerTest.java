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
package io.agentscope.builder.web.catalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedDefinitionMaterializerTest {
    @TempDir Path root;

    @Test
    void deletesRemovedDefinitionsWhilePreservingSessionOutputs() throws Exception {
        ManagedDefinitionMaterializer.materialize(
                root, Map.of("skills/a/SKILL.md", "old", "AGENTS.md", "v1"));
        Files.writeString(root.resolve("output.txt"), "keep");
        ManagedDefinitionMaterializer.materialize(root, Map.of("AGENTS.md", "v2"));
        Assertions.assertFalse(Files.exists(root.resolve("skills/a/SKILL.md")));
        Assertions.assertEquals("v2", Files.readString(root.resolve("AGENTS.md")));
        Assertions.assertEquals("keep", Files.readString(root.resolve("output.txt")));
    }

    @Test
    void rejectsReservedManifestBeforeDeletingExistingFiles() throws Exception {
        ManagedDefinitionMaterializer.materialize(root, Map.of("AGENTS.md", "keep"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                        ManagedDefinitionMaterializer.materialize(
                                root, Map.of("./.managed-definition-manifest.json", "[]")));
        Assertions.assertEquals("keep", Files.readString(root.resolve("AGENTS.md")));
    }

    @Test
    void rejectsTraversalAndSymlinks() throws Exception {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ManagedDefinitionMaterializer.materialize(root, Map.of("../escape", "bad")));
        Path external = Files.createTempDirectory(root.getParent(), "managed-external-");
        try {
            Files.createSymbolicLink(root.resolve("link"), external);
            Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            ManagedDefinitionMaterializer.materialize(
                                    root, Map.of("link/escape", "bad")));
        } finally {
            Files.deleteIfExists(root.resolve("link"));
            Files.delete(external);
        }
    }
}
