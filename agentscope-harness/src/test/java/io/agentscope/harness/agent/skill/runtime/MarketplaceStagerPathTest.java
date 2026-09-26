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
package io.agentscope.harness.agent.skill.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.RepoBound;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.StageResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies containment before staging writes or stale-resource cleanup can run. */
class MarketplaceStagerPathTest {

    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absoluteSkillNameCannotWriteOrCleanOutsideCache(boolean existing) throws IOException {
        Path outside = temp.resolve("outside");
        if (existing) {
            Files.createDirectories(outside);
            Files.writeString(outside.resolve("keep.txt"), "keep");
            Files.writeString(outside.resolve("marker.txt"), "original");
        }
        AgentSkillRepository repo = repository("market");
        AgentSkill unsafe = skill(outside.toString());
        Path workspace = temp.resolve("workspace");

        Map<String, StageResult> result =
                new MarketplaceStager(workspace)
                        .stage(List.of(new RepoBound(unsafe, repo)), Map.of(repo, "market"));

        if (existing) {
            assertEquals("keep", Files.readString(outside.resolve("keep.txt")));
            assertEquals("original", Files.readString(outside.resolve("marker.txt")));
        } else {
            assertFalse(Files.exists(outside));
        }
        assertEquals(StageResult.NONE, result.get(unsafe.getName()));
        assertFalse(Files.exists(workspace.resolve(MarketplaceStager.CACHE_DIR)));
    }

    @Test
    void relativeSkillNameCannotDeleteOutsideFiles() throws IOException {
        Path workspace = temp.resolve("workspace");
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "keep");
        AgentSkillRepository repo = repository("market");
        AgentSkill unsafe = skill("../../../../outside");

        Map<String, StageResult> result =
                new MarketplaceStager(workspace)
                        .stage(List.of(new RepoBound(unsafe, repo)), Map.of(repo, "market"));

        assertEquals("keep", Files.readString(outside.resolve("keep.txt")));
        assertFalse(Files.exists(outside.resolve("marker.txt")));
        assertEquals(StageResult.NONE, result.get(unsafe.getName()));
        assertFalse(Files.exists(workspace.resolve(MarketplaceStager.CACHE_DIR)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                ".",
                "..",
                "../sibling",
                "..\\sibling",
                "nested/name",
                "nested\\name",
                "nested/../alias",
                "nested\\..\\alias",
                "C:relative",
                "name:stream",
                "trailing.",
                ".. ",
                "invalid\0name"
            })
    void invalidSkillDoesNotPreventValidSkillsFromStaging(String name) throws IOException {
        Path workspace = temp.resolve("workspace");
        AgentSkillRepository repo = repository("market");
        Map<String, StageResult> result =
                new MarketplaceStager(workspace)
                        .stage(
                                List.of(
                                        new RepoBound(skill(name), repo),
                                        new RepoBound(skill("valid"), repo)),
                                Map.of(repo, "market"));

        assertEquals(StageResult.NONE, result.get(name));
        assertInstanceOf(StageResult.Cached.class, result.get("valid"));
        assertEquals(
                "marker",
                Files.readString(
                        workspace.resolve(".skills-cache/_shared/market/valid/marker.txt")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unsafeNamespaceCannotWriteOrCleanOutsideCache(boolean fallback) throws IOException {
        Path workspace = temp.resolve("workspace");
        Path outside = Files.createDirectories(temp.resolve("outside/valid"));
        Files.writeString(outside.resolve("keep.txt"), "keep");
        String source = outside.getParent().toString();
        AgentSkillRepository repo = repository(source);

        Map<String, StageResult> result =
                new MarketplaceStager(workspace)
                        .stage(
                                List.of(new RepoBound(skill("valid"), repo)),
                                fallback
                                        ? Map.of()
                                        : MarketplaceStager.resolveSourceNamespaces(List.of(repo)));

        assertEquals("keep", Files.readString(outside.resolve("keep.txt")));
        assertFalse(Files.exists(outside.resolve("marker.txt")));
        assertEquals(StageResult.NONE, result.get("valid"));
        assertFalse(Files.exists(workspace.resolve(MarketplaceStager.CACHE_DIR)));
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "../other", "..\\other", "nested/source", "C:relative"})
    void invalidNamespacesAreRejected(String namespace) {
        Path workspace = temp.resolve("workspace");
        AgentSkillRepository repo = repository("market");

        Map<String, StageResult> result =
                new MarketplaceStager(workspace)
                        .stage(
                                List.of(new RepoBound(skill("valid"), repo)),
                                Map.of(repo, namespace));

        assertEquals(StageResult.NONE, result.get("valid"));
        assertFalse(Files.exists(workspace.resolve(MarketplaceStager.CACHE_DIR)));
    }

    @Test
    void frontmatterNameIsValidatedIndependentlyOfSourceDirectory() throws IOException {
        Path workspace = temp.resolve("workspace");
        Path outside = temp.resolve("outside");
        Path source = Files.createDirectories(temp.resolve("skills/ordinary-folder"));
        Files.writeString(
                source.resolve("SKILL.md"),
                "---\nname: '" + outside + "'\ndescription: test\n---\nBody\n");
        Files.writeString(source.resolve("marker.txt"), "marker");
        AgentSkillRepository repo = new FileSystemSkillRepository(source.getParent());
        AgentSkill loaded = repo.getAllSkills().get(0);
        assertEquals(outside.toString(), loaded.getName());

        Map<String, StageResult> result =
                new MarketplaceStager(workspace)
                        .stage(
                                List.of(new RepoBound(loaded, repo)),
                                MarketplaceStager.resolveSourceNamespaces(List.of(repo)));

        assertEquals(StageResult.NONE, result.get(loaded.getName()));
        assertFalse(Files.exists(outside));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mixed Case", "my_skill-1.2", "name..with.dots"})
    void safeNamesPreserveResourcesAndCleanup(String name) throws IOException {
        // Normalizing the workspace must not make resource containment checks reject valid files.
        Path workspace = temp.resolve("unused/../workspace");
        AgentSkillRepository repo = repository("nacos@public");
        MarketplaceStager stager = new MarketplaceStager(workspace);
        Map<AgentSkillRepository, String> namespaces = Map.of(repo, "nacos@public");
        AgentSkill first = new AgentSkill(name, "test", "Body", Map.of("old.txt", "stale"));
        stager.stage(List.of(new RepoBound(first, repo)), namespaces);
        AgentSkill replacement =
                new AgentSkill(
                        name,
                        "test",
                        "Body",
                        Map.of("references/guide.txt", "guide", "assets/data.bin", "base64:AAEC"));

        Map<String, StageResult> result =
                stager.stage(List.of(new RepoBound(replacement, repo)), namespaces);

        Path staged = temp.resolve("workspace/.skills-cache/_shared/nacos@public").resolve(name);
        assertEquals(new StageResult.Cached("_shared", "nacos@public", name), result.get(name));
        assertEquals("guide", Files.readString(staged.resolve("references/guide.txt")));
        assertArrayEquals(
                new byte[] {0, 1, 2}, Files.readAllBytes(staged.resolve("assets/data.bin")));
        assertFalse(Files.exists(staged.resolve("old.txt")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void absentSourceKeepsGlobalNamespaceFallback(String source) {
        Path workspace = temp.resolve("workspace");
        AgentSkillRepository repo = repository(source);

        Map<String, StageResult> result =
                new MarketplaceStager(workspace)
                        .stage(List.of(new RepoBound(skill("valid"), repo)), Map.of());

        assertEquals(new StageResult.Cached("_shared", "_global", "valid"), result.get("valid"));
        assertTrue(
                Files.exists(workspace.resolve(".skills-cache/_shared/_global/valid/marker.txt")));
    }

    private static AgentSkill skill(String name) {
        return new AgentSkill(name, "test", "Body", Map.of("marker.txt", "marker"));
    }

    private static AgentSkillRepository repository(String source) {
        AgentSkillRepository repo = mock(AgentSkillRepository.class);
        when(repo.getSource()).thenReturn(source);
        return repo;
    }
}
