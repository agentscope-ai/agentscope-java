/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.example;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initializes the agent workspace by copying bundled template files from the classpath into a
 * target directory on disk.
 *
 * <p>The template files live under {@code src/main/resources/workspace/} and are packaged inside
 * the JAR. When the example is run for the first time, {@link #init(Path)} extracts them into the
 * given workspace directory so the agent can read and modify them at runtime.
 *
 * Workspace structure:
 *
 * <pre>
 * &lt;workspace&gt;/
 * ├── AGENTS.md              # Agent persona and core rules (always loaded)
 * ├── MEMORY.md              # Persistent notes accumulated across sessions
 * ├── knowledge/
 * │   └── KNOWLEDGE.md       # Chinook database schema reference
 * ├── skills/
 * │   ├── schema-exploration/
 * │   │   └── SKILL.md       # How to discover database structure
 * │   └── query-writing/
 * │       └── SKILL.md       # How to write and execute SQL queries
 * └── subagents/
 *     ├── schema-analyst.md  # Specialised subagent for deep schema analysis
 *     └── query-optimizer.md # Specialised subagent for query optimisation
 * </pre>
 */
public class WorkspaceInitializer {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceInitializer.class);
    private static final String CLASSPATH_PREFIX = "workspace";

    /**
     * Copies all bundled workspace template files into {@code targetDir}.
     *
     * <p>Existing files are left unchanged so that previously accumulated notes survive restarts.
     * New template files are copied with {@link StandardCopyOption#REPLACE_EXISTING} disabled.
     *
     * @param targetDir directory to initialise; created if it does not exist
     * @throws IOException if a file cannot be read or written
     */
    public static void init(Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        URL resourceUrl = WorkspaceInitializer.class.getClassLoader().getResource(CLASSPATH_PREFIX);
        if (resourceUrl == null) {
            log.warn(
                    "Classpath resource '{}' not found — workspace will not be pre-populated.",
                    CLASSPATH_PREFIX);
            return;
        }

        URI resourceUri;
        try {
            resourceUri = resourceUrl.toURI();
        } catch (URISyntaxException e) {
            throw new IOException("Cannot convert resource URL to URI: " + resourceUrl, e);
        }

        if ("jar".equals(resourceUri.getScheme())) {
            // Running from a JAR: open the embedded filesystem
            try (FileSystem fs = FileSystems.newFileSystem(resourceUri, Collections.emptyMap())) {
                Path source = fs.getPath(CLASSPATH_PREFIX);
                copyTree(source, targetDir);
            }
        } else {
            // Running from an exploded directory (IDE / Maven test run)
            Path source = Path.of(resourceUri);
            copyTree(source, targetDir);
        }

        log.info("Workspace initialised at {}", targetDir);
    }

    private static void copyTree(Path source, Path targetDir) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path srcPath : (Iterable<Path>) walk::iterator) {
                Path relative = source.relativize(srcPath);
                Path target = targetDir.resolve(relative.toString());

                if (Files.isDirectory(srcPath)) {
                    Files.createDirectories(target);
                } else if (!Files.exists(target)) {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = Files.newInputStream(srcPath)) {
                        Files.copy(in, target);
                    }
                    log.debug("Copied workspace file: {}", relative);
                } else {
                    log.debug("Skipped (already exists): {}", relative);
                }
            }
        }
    }
}
