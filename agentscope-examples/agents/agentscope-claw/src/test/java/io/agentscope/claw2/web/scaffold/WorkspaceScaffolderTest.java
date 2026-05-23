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
package io.agentscope.claw2.web.scaffold;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceScaffolderTest {

    @TempDir Path tmp;

    @Test
    void writesExpectedLayout() throws Exception {
        WorkspaceScaffolder.scaffold(tmp, "Helper", "You answer questions about cats.");

        assertThat(tmp.resolve("AGENTS.md")).exists();
        assertThat(tmp.resolve("tools.json")).exists();
        assertThat(tmp.resolve("skills/example-skill/SKILL.md")).exists();
        assertThat(tmp.resolve("subagents/README.md")).exists();
        assertThat(tmp.resolve("memory")).isDirectory();

        String agentsMd = Files.readString(tmp.resolve("AGENTS.md"));
        assertThat(agentsMd).contains("# Helper");
        assertThat(agentsMd).contains("You answer questions about cats.");
        assertThat(agentsMd).contains("`tools.json`");
        assertThat(agentsMd).contains("`skills/`");

        String toolsJson = Files.readString(tmp.resolve("tools.json"));
        assertThat(toolsJson).contains("\"allow\"");
        assertThat(toolsJson).contains("read_file");
    }

    @Test
    void doesNotOverwriteExistingFiles() throws Exception {
        Files.createDirectories(tmp);
        Files.writeString(tmp.resolve("AGENTS.md"), "# Custom\nedited by user\n");

        WorkspaceScaffolder.scaffold(tmp, "Helper", "default");

        String agentsMd = Files.readString(tmp.resolve("AGENTS.md"));
        assertThat(agentsMd).isEqualTo("# Custom\nedited by user\n");
        // tools.json should still get scaffolded since it didn't exist
        assertThat(tmp.resolve("tools.json")).exists();
    }
}
