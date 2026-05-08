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

import io.agentscope.harness.agent.sandbox.layout.DirEntry;
import io.agentscope.harness.agent.sandbox.layout.FileEntry;
import io.agentscope.harness.agent.sandbox.layout.GitRepoEntry;
import io.agentscope.harness.agent.sandbox.layout.LocalDirEntry;
import io.agentscope.harness.agent.sandbox.layout.LocalFileEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies a {@link WorkspaceSpec} to a target directory by materializing declared entries
 * (files, directories, local copies).
 *
 * <p>Supports two materialization modes:
 * <ul>
 *   <li>{@code onlyEphemeral=false}: apply all entries (Branch C / D of sandbox start)</li>
 *   <li>{@code onlyEphemeral=true}: apply only entries where
 *       {@link WorkspaceEntry#isEphemeral()} is {@code true} (Branch A / B of sandbox start)
 *   </li>
 * </ul>
 */
public class WorkspaceSpecApplier {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSpecApplier.class);

    private final Path workspaceRoot;

    public WorkspaceSpecApplier(String workspaceRoot) {
        this.workspaceRoot = Path.of(workspaceRoot);
    }

    public WorkspaceSpecApplier(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public void applyWorkspaceSpec(WorkspaceSpec spec, boolean onlyEphemeral) throws Exception {
        if (onlyEphemeral) {
            applyEphemeralEntries(spec.getEntries(), workspaceRoot);
        } else {
            applyAllEntries(spec.getEntries(), workspaceRoot);
        }
    }

    private void applyAllEntries(Map<String, WorkspaceEntry> entries, Path destDir)
            throws Exception {
        for (Map.Entry<String, WorkspaceEntry> e : entries.entrySet()) {
            Path dest = destDir.resolve(e.getKey());
            applyEntry(e.getValue(), dest);
        }
    }

    private void applyEphemeralEntries(Map<String, WorkspaceEntry> entries, Path destDir)
            throws Exception {
        for (Map.Entry<String, WorkspaceEntry> e : entries.entrySet()) {
            WorkspaceEntry entry = e.getValue();
            Path dest = destDir.resolve(e.getKey());
            if (entry.isEphemeral()) {
                applyEntry(entry, dest);
            } else if (entry instanceof DirEntry de && !de.getChildren().isEmpty()) {
                applyEphemeralEntries(de.getChildren(), dest);
            }
        }
    }

    private void applyEntry(WorkspaceEntry entry, Path dest) throws Exception {
        if (entry instanceof FileEntry fe) {
            Files.createDirectories(dest.getParent());
            Charset charset =
                    Charset.forName(fe.getEncoding() != null ? fe.getEncoding() : "UTF-8");
            Files.writeString(
                    dest,
                    fe.getContent() != null ? fe.getContent() : "",
                    charset,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

        } else if (entry instanceof DirEntry de) {
            Files.createDirectories(dest);
            for (Map.Entry<String, WorkspaceEntry> child : de.getChildren().entrySet()) {
                applyEntry(child.getValue(), dest.resolve(child.getKey()));
            }

        } else if (entry instanceof LocalFileEntry lf) {
            if (lf.getSourcePath() == null || lf.getSourcePath().isBlank()) {
                log.warn("LocalFileEntry has null/blank sourcePath, skipping: {}", dest);
                return;
            }
            Path source = Path.of(lf.getSourcePath()).toAbsolutePath().normalize();
            Files.createDirectories(dest.getParent());
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);

        } else if (entry instanceof LocalDirEntry ld) {
            if (ld.getSourcePath() == null || ld.getSourcePath().isBlank()) {
                log.warn("LocalDirEntry has null/blank sourcePath, skipping: {}", dest);
                return;
            }
            Path source = Path.of(ld.getSourcePath()).toAbsolutePath().normalize();
            copyDirectory(source, dest);

        } else if (entry instanceof GitRepoEntry) {
            log.warn("GitRepoEntry is not yet implemented, skipping entry at: {}", dest);
        } else if (entry instanceof WorkspaceProjectionEntry) {
            log.debug(
                    "WorkspaceProjectionEntry is applied by sandbox lifecycle, skipping: {}", dest);
        }
    }

    private void copyDirectory(Path source, Path dest) throws Exception {
        if (!Files.isDirectory(source)) {
            throw new IOException("LocalDirEntry sourcePath is not a directory: " + source);
        }
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(
                    src -> {
                        Path rel = source.relativize(src);
                        Path target = dest.resolve(rel);
                        try {
                            if (Files.isDirectory(src)) {
                                Files.createDirectories(target);
                            } else {
                                Files.createDirectories(target.getParent());
                                Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
