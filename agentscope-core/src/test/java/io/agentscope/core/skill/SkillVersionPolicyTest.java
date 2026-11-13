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
package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the {@link SkillVersionPolicy} factories and the agent-wide declaration on
 * {@link ReActAgent.Builder}, including that building never probes the registered
 * repositories: whether a PIN can be honored is decided when skills are loaded.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
@Tag("unit")
@DisplayName("SkillVersionPolicy DSL and agent-wide declaration")
class SkillVersionPolicyTest {

    @TempDir Path tempDir;

    /** Creates a file-system repository over a temp dir holding one demo skill. */
    private FileSystemSkillRepository newFileSystemRepository() throws IOException {
        Path base = tempDir.resolve("skills");
        Files.createDirectories(base.resolve("demo-skill"));
        Files.writeString(
                base.resolve("demo-skill").resolve("SKILL.md"),
                "---\nname: demo-skill\ndescription: demo\n---\nbody",
                java.nio.charset.StandardCharsets.UTF_8);
        return new FileSystemSkillRepository(base);
    }

    @Test
    @DisplayName("Builder defaults to LATEST mode with FALLBACK_TO_LATEST on unreachable")
    void testBuilderDefaults() {
        SkillVersionPolicy policy = SkillVersionPolicy.builder().build();

        assertEquals(SkillVersionPolicy.Mode.LATEST, policy.mode(), "mode default is LATEST");
        assertEquals(
                SkillVersionPolicy.OnUnreachable.FALLBACK_TO_LATEST,
                policy.onUnreachable(),
                "onUnreachable default is FALLBACK_TO_LATEST");
    }

    @Test
    @DisplayName("Factories and fluent mutations cover the declared two-field combinations")
    void testFactoryCombinations() {
        assertEquals(SkillVersionPolicy.builder().build(), SkillVersionPolicy.latest());
        assertEquals(SkillVersionPolicy.Mode.LATEST, SkillVersionPolicy.latest().mode());
        assertEquals(
                SkillVersionPolicy.OnUnreachable.FALLBACK_TO_LATEST,
                SkillVersionPolicy.latest().onUnreachable(),
                "latest() carries the default unreachable decision (inert for LATEST)");

        assertEquals(SkillVersionPolicy.Mode.PIN, SkillVersionPolicy.pinned().mode());
        assertEquals(
                SkillVersionPolicy.OnUnreachable.FALLBACK_TO_LATEST,
                SkillVersionPolicy.pinned().onUnreachable(),
                "pinned() degrades visibly by default");

        assertEquals(
                SkillVersionPolicy.pinned(),
                SkillVersionPolicy.pinned().fallbackToLatest(),
                "fallbackToLatest() is an explicit restatement of the pin default");

        SkillVersionPolicy strict = SkillVersionPolicy.pinned().failOnUnreachable();
        assertEquals(SkillVersionPolicy.Mode.PIN, strict.mode(), "strict keeps the pin");
        assertEquals(
                SkillVersionPolicy.OnUnreachable.ERROR,
                strict.onUnreachable(),
                "failOnUnreachable flips only the unreachable decision");
    }

    @Test
    @DisplayName("PIN on a detection-only repository still builds — no backend probe at build time")
    void testPinOnNonAddressableRepositoryBuilds() throws IOException {
        FileSystemSkillRepository repository = newFileSystemRepository();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("pin-on-fs")
                        .model(org.mockito.Mockito.mock(Model.class))
                        .skillRepository(repository)
                        .skillVersionPolicy(SkillVersionPolicy.pinned())
                        .build();
        try {
            assertEquals(
                    SkillVersionPolicy.pinned(),
                    agent.getSkillVersionPolicy(),
                    "Build must not probe backends; the runtime gate owns unreachable-pin"
                            + " handling");
        } finally {
            agent.close();
        }
    }

    @Test
    @DisplayName("PIN on an addressable repository (Classpath) builds and is exposed")
    void testPinOnClasspathRepositoryBuilds() throws IOException {
        try (ClasspathSkillRepository repository = new ClasspathSkillRepository("test-skills")) {
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("pin-on-classpath")
                            .model(org.mockito.Mockito.mock(Model.class))
                            .skillRepository(repository)
                            .skillVersionPolicy(SkillVersionPolicy.pinned())
                            .build();
            try {
                assertEquals(
                        SkillVersionPolicy.pinned(),
                        agent.getSkillVersionPolicy(),
                        "The built agent must expose the declared policy");
            } finally {
                agent.close();
            }
        }
    }

    @Test
    @DisplayName("An unset policy defaults to latest() on the built agent")
    void testUnsetPolicyDefaultsToLatest() throws IOException {
        try (ClasspathSkillRepository repository = new ClasspathSkillRepository("test-skills")) {
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("implicit-latest")
                            .model(org.mockito.Mockito.mock(Model.class))
                            .skillRepository(repository)
                            .build();
            try {
                assertEquals(
                        SkillVersionPolicy.latest(),
                        agent.getSkillVersionPolicy(),
                        "No declaration must normalise to latest()");
            } finally {
                agent.close();
            }
        }
    }

    @Test
    @DisplayName("Builder copy carries the declared policy into the built agent")
    void testBuilderCopyCarriesPolicy() throws IOException {
        try (ClasspathSkillRepository repository = new ClasspathSkillRepository("test-skills")) {
            ReActAgent.Builder source =
                    ReActAgent.builder()
                            .name("copy-policy")
                            .model(org.mockito.Mockito.mock(Model.class))
                            .skillRepository(repository)
                            .skillVersionPolicy(SkillVersionPolicy.pinned());

            ReActAgent agent = source.copy().build();
            try {
                assertEquals(
                        SkillVersionPolicy.pinned(),
                        agent.getSkillVersionPolicy(),
                        "copy() must carry the declared policy");
            } finally {
                agent.close();
            }
        }
    }

    @Test
    @DisplayName("Declaring a policy rejects null")
    void testDeclarationInputValidation() {
        ReActAgent.Builder builder = ReActAgent.builder();

        assertThrows(
                NullPointerException.class,
                () -> builder.skillVersionPolicy(null),
                "A null policy must be rejected");
    }
}
