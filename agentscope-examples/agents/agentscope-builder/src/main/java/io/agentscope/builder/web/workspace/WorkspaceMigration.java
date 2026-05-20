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
package io.agentscope.builder.web.workspace;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-shot migration that relocates per-user agent workspaces from the legacy
 * {@code .agentscope/users/{userId}/workspace/agents/{agentId}/} layout into the new
 * {@code .agentscope/workspaces/users/{userId}/agents/{agentId}/} layout introduced by the
 * multi-tenant filesystem refactor.
 *
 * <p>The migration is idempotent and safe to rerun on every boot: it iterates legacy paths and
 * moves only those whose destination does not already exist. The empty {@code workspace/}
 * directory left behind in the legacy location is removed when it becomes empty so the file
 * tree converges to the new layout over time.
 *
 * <p>Failures on individual entries are logged but do not abort the boot — the worst case is
 * that a particular agent's files remain at the legacy location and will be served by the
 * filesystem on next access (filesystem reads fall back to local disk via {@code WorkspaceManager}).
 */
public final class WorkspaceMigration {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMigration.class);

    private WorkspaceMigration() {}

    /**
     * Runs the migration against the given install root if any legacy directories are detected.
     *
     * @param cwd builder install directory (the {@code .agentscope} parent)
     */
    public static void runIfNeeded(Path cwd) {
        Path legacyUsers = cwd.resolve(".agentscope").resolve("users");
        Path newRoot = cwd.resolve(".agentscope").resolve("workspaces");
        if (!Files.isDirectory(legacyUsers)) {
            return;
        }

        int movedAgents = 0;
        int skippedAgents = 0;
        try (DirectoryStream<Path> userDirs = Files.newDirectoryStream(legacyUsers)) {
            for (Path userDir : userDirs) {
                if (!Files.isDirectory(userDir)) continue;
                String userId = userDir.getFileName().toString();
                Path legacyAgents = userDir.resolve("workspace").resolve("agents");
                if (!Files.isDirectory(legacyAgents)) continue;

                Path newUserAgents = newRoot.resolve("users").resolve(userId).resolve("agents");
                Files.createDirectories(newUserAgents);

                try (DirectoryStream<Path> agentDirs = Files.newDirectoryStream(legacyAgents)) {
                    for (Path agentDir : agentDirs) {
                        if (!Files.isDirectory(agentDir)) continue;
                        String agentId = agentDir.getFileName().toString();
                        Path target = newUserAgents.resolve(agentId);
                        if (Files.exists(target)) {
                            skippedAgents++;
                            continue;
                        }
                        try {
                            Files.move(agentDir, target, StandardCopyOption.ATOMIC_MOVE);
                        } catch (IOException atomicFail) {
                            Files.move(agentDir, target);
                        }
                        movedAgents++;
                        log.info(
                                "Migrated workspace: {} -> {}",
                                agentDir.toAbsolutePath(),
                                target.toAbsolutePath());
                    }
                }

                // best-effort cleanup of the now-empty `workspace/agents/` and `workspace/` dirs
                deleteIfEmpty(legacyAgents);
                deleteIfEmpty(legacyAgents.getParent());
            }
        } catch (IOException e) {
            log.warn(
                    "Workspace migration encountered an I/O error and was aborted: {}",
                    e.getMessage());
            return;
        }

        if (movedAgents > 0 || skippedAgents > 0) {
            log.info(
                    "Workspace migration complete: moved={} skipped(already-migrated)={}",
                    movedAgents,
                    skippedAgents);
        }
    }

    private static void deleteIfEmpty(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            if (!ds.iterator().hasNext()) {
                Files.delete(dir);
            }
        } catch (IOException ignored) {
            // non-fatal — a stale empty directory is harmless
        }
    }
}
