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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceMigrationTest {

    @TempDir Path cwd;

    @Test
    void relocatesLegacyPerUserWorkspaces() throws Exception {
        Path legacy = cwd.resolve(".agentscope/users/alice/workspace/agents/research-bot");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("AGENTS.md"), "legacy-content");

        WorkspaceMigration.runIfNeeded(cwd);

        Path moved =
                cwd.resolve(".agentscope/workspaces/users/alice/agents/research-bot/AGENTS.md");
        assertThat(Files.exists(moved)).isTrue();
        assertThat(Files.readString(moved)).isEqualTo("legacy-content");
        // Old location is removed (or at least the file is gone)
        assertThat(Files.exists(legacy.resolve("AGENTS.md"))).isFalse();
    }

    @Test
    void isIdempotentOnRerun() throws Exception {
        Path legacy = cwd.resolve(".agentscope/users/bob/workspace/agents/bot");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("AGENTS.md"), "bob-1");

        WorkspaceMigration.runIfNeeded(cwd);
        WorkspaceMigration.runIfNeeded(cwd); // rerun should be a no-op

        Path moved = cwd.resolve(".agentscope/workspaces/users/bob/agents/bot/AGENTS.md");
        assertThat(Files.readString(moved)).isEqualTo("bob-1");
    }

    @Test
    void doesNothingWhenNoLegacyPathExists() {
        // No .agentscope dir at all
        WorkspaceMigration.runIfNeeded(cwd);
        assertThat(Files.exists(cwd.resolve(".agentscope/workspaces"))).isFalse();
    }

    @Test
    void preExistingTargetIsNotOverwritten() throws Exception {
        Path legacy = cwd.resolve(".agentscope/users/alice/workspace/agents/bot");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("AGENTS.md"), "legacy");

        Path existingTarget = cwd.resolve(".agentscope/workspaces/users/alice/agents/bot");
        Files.createDirectories(existingTarget);
        Files.writeString(existingTarget.resolve("AGENTS.md"), "already-here");

        WorkspaceMigration.runIfNeeded(cwd);

        // Target file untouched; legacy file untouched as well (skipped)
        assertThat(Files.readString(existingTarget.resolve("AGENTS.md"))).isEqualTo("already-here");
        assertThat(Files.readString(legacy.resolve("AGENTS.md"))).isEqualTo("legacy");
    }
}
