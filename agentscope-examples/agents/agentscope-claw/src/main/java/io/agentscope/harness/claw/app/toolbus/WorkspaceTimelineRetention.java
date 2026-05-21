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
package io.agentscope.harness.claw.app.toolbus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * Deletes per-session workspace timeline files (produced by {@link WorkspaceMutationHook}) whose
 * last-modified time is older than {@code retentionDays}. Walks
 * {@code <usersRoot>/<userId>/workspace/sessions/*.workspace.jsonl} across every tenant;
 * ignores everything else.
 */
public final class WorkspaceTimelineRetention {

    private WorkspaceTimelineRetention() {}

    /**
     * Runs a single retention pass across every user's workspace under {@code usersRoot} (the
     * path returned by {@code UserWorkspaceProvisioner.usersRoot()}). Returns the number of files
     * deleted. {@code retentionDays <= 0} disables cleanup and returns 0.
     */
    public static int run(Path usersRoot, int retentionDays) throws IOException {
        if (usersRoot == null || retentionDays <= 0) return 0;
        if (!Files.isDirectory(usersRoot)) return 0;

        FileTime cutoff = FileTime.from(Instant.now().minus(Duration.ofDays(retentionDays)));
        int[] deleted = {0};

        try (Stream<Path> users = Files.list(usersRoot)) {
            users.filter(Files::isDirectory)
                    .map(p -> p.resolve("workspace").resolve("sessions"))
                    .filter(Files::isDirectory)
                    .forEach(
                            sessionsDir -> {
                                try (Stream<Path> files = Files.list(sessionsDir)) {
                                    files.filter(Files::isRegularFile)
                                            .filter(
                                                    p ->
                                                            p.getFileName()
                                                                    .toString()
                                                                    .endsWith(".workspace.jsonl"))
                                            .forEach(
                                                    f -> {
                                                        try {
                                                            FileTime mtime =
                                                                    Files.getLastModifiedTime(f);
                                                            if (mtime.compareTo(cutoff) < 0) {
                                                                Files.deleteIfExists(f);
                                                                deleted[0]++;
                                                            }
                                                        } catch (IOException ignored) {
                                                            // best-effort
                                                        }
                                                    });
                                } catch (IOException ignored) {
                                    // best-effort
                                }
                            });
        }
        return deleted[0];
    }
}
