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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Materializes a CP definition into a session-private workspace, including deletions. */
public final class ManagedDefinitionMaterializer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MANIFEST = ".managed-definition-manifest.json";

    private ManagedDefinitionMaterializer() {}

    public static synchronized void materialize(Path workspace, Map<String, String> files) {
        if (files == null) return;
        Path root = workspace.toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(root))
                throw new IllegalArgumentException("Workspace root is a symlink");
            Files.createDirectories(root);
            root = root.toRealPath();
            // Validate the entire manifest before modifying any file.
            for (String key : files.keySet()) {
                Path path = safePath(root, key);
                if (path.equals(root.resolve(MANIFEST)))
                    throw new IllegalArgumentException("Reserved definition path");
            }
            Path manifest = safePath(root, MANIFEST);
            List<String> previous =
                    Files.exists(manifest)
                            ? JSON.readValue(
                                    Files.readString(manifest),
                                    new TypeReference<List<String>>() {})
                            : List.of();
            for (String key : previous) safePath(root, key);
            for (String key : previous) {
                if (!files.containsKey(key)) Files.deleteIfExists(safePath(root, key));
            }
            for (var entry : files.entrySet()) {
                Path path = safePath(root, entry.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, entry.getValue() == null ? "" : entry.getValue());
            }
            Files.writeString(manifest, JSON.writeValueAsString(files.keySet()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot materialize session definition", e);
        }
    }

    private static Path safePath(Path root, String key) {
        if (key == null || key.isBlank() || Path.of(key).isAbsolute())
            throw new IllegalArgumentException("Invalid definition path");
        Path result = root.resolve(key).normalize();
        if (!result.startsWith(root) || result.equals(root))
            throw new IllegalArgumentException("Definition path escapes workspace");
        for (Path path = result; path != null; path = path.getParent()) {
            if (Files.isSymbolicLink(path))
                throw new IllegalArgumentException("Definition path contains a symlink");
        }
        return result;
    }
}
