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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
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
    void unsafeNamespaceIsMappedWithoutTouchingOutsideFiles(boolean fallback) throws IOException {
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
        StageResult.Cached cached = assertInstanceOf(StageResult.Cached.class, result.get("valid"));
        assertFalse(cached.sourceNamespace().contains("/"));
        assertTrue(
                Files.exists(
                        workspace
                                .resolve(".skills-cache/_shared")
                                .resolve(cached.sourceNamespace())
                                .resolve("valid/marker.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "../other", "..\\other", "nested/source", "C:relative"})
    void sourceNamespacesAreMappedToSingleDirectory(String namespace) throws IOException {
        Path workspace = temp.resolve("workspace");
        AgentSkillRepository repo = repository("market");

        Map<String, StageResult> result =
                new MarketplaceStager(workspace)
                        .stage(
                                List.of(new RepoBound(skill("valid"), repo)),
                                Map.of(repo, namespace));

        StageResult.Cached cached = assertInstanceOf(StageResult.Cached.class, result.get("valid"));
        assertFalse(cached.sourceNamespace().contains("/"));
        assertFalse(cached.sourceNamespace().contains("\\"));
        assertTrue(
                Files.exists(
                        workspace
                                .resolve(".skills-cache/_shared")
                                .resolve(cached.sourceNamespace())
                                .resolve("valid/marker.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"git-owner/repo", "classpath-agentscope/skills"})
    void shippedSlashSourceIsStagedUnderOneNamespaceDirectory(String source) throws IOException {
        Path workspace = temp.resolve("workspace");
        AgentSkillRepository repo = repository(source);

        Map<String, StageResult> result =
                new MarketplaceStager(workspace)
                        .stage(
                                List.of(new RepoBound(skill("valid"), repo)),
                                MarketplaceStager.resolveSourceNamespaces(List.of(repo)));

        StageResult.Cached cached = assertInstanceOf(StageResult.Cached.class, result.get("valid"));
        assertFalse(cached.sourceNamespace().contains("/"));
        assertTrue(
                Files.exists(
                        workspace
                                .resolve(".skills-cache/_shared")
                                .resolve(cached.sourceNamespace())
                                .resolve("valid/marker.txt")));
    }

    @Test
    void lossySourceNamespacesRemainInjective() {
        AgentSkillRepository first = repository("a@b");
        AgentSkillRepository second = repository("a#b");

        Map<AgentSkillRepository, String> namespaces =
                MarketplaceStager.resolveSourceNamespaces(List.of(first, second));

        assertTrue(namespaces.get(first).matches("a_b-[0-9a-f]{12}"));
        assertTrue(namespaces.get(second).matches("a_b-[0-9a-f]{12}"));
        assertFalse(namespaces.get(first).equals(namespaces.get(second)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CON",
                "con",
                "PRN",
                "AUX",
                "NUL",
                "COM1",
                "COM9",
                "LPT1",
                "LPT9",
                "con.txt",
                "NUL.tar.gz",
                "lPt1.ext",
                "COM\u00b9",
                "LPT\u00b2"
            })
    void windowsDeviceSkillNamesAreRejected(String name) {
        Path workspace = temp.resolve("workspace");
        AgentSkillRepository repo = repository("market");

        Map<String, StageResult> result =
                new MarketplaceStager(workspace)
                        .stage(List.of(new RepoBound(skill(name), repo)), Map.of(repo, "market"));

        assertEquals(StageResult.NONE, result.get(name));
        assertFalse(Files.exists(workspace.resolve(MarketplaceStager.CACHE_DIR)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "safe-source",
                ".",
                "..",
                "trailing.",
                "trailing ",
                "CON",
                "NUL.tar.gz",
                "com1",
                "LPT9",
                "COM\u00b9",
                "LPT\u00b2"
            })
    void scopeAndNamespaceUseTheSameSafeMapping(String raw) throws IOException {
        Path workspace = temp.resolve("workspace");
        AgentSkillRepository repo = repository(raw);
        Map<AgentSkillRepository, String> namespaces =
                MarketplaceStager.resolveSourceNamespaces(List.of(repo));
        MarketplaceStager stager = new MarketplaceStager(workspace);
        List<RepoBound> visible = List.of(new RepoBound(skill("valid"), repo));

        StageResult.Cached cached =
                assertInstanceOf(
                        StageResult.Cached.class,
                        stager.stage(visible, namespaces, raw).get("valid"));

        assertEquals(cached.scopeSegment(), cached.sourceNamespace());
        assertEquals(namespaces.get(repo), cached.sourceNamespace());
        if (!raw.equals("safe-source")) {
            assertFalse(raw.equals(cached.sourceNamespace()));
        }
        assertTrue(cached.sourceNamespace().matches("[A-Za-z0-9._-]{1,64}"));
        assertEquals(
                "marker",
                Files.readString(
                        workspace
                                .resolve(".skills-cache")
                                .resolve(cached.scopeSegment())
                                .resolve(cached.sourceNamespace())
                                .resolve("valid/marker.txt")));
        assertEquals(cached, stager.stage(visible, namespaces, raw).get("valid"));
    }

    @Test
    void longSegmentsRemainBoundedAndLiteralSharedScopeStaysDistinct() {
        Path workspace = temp.resolve("workspace");
        String raw = "a".repeat(65);
        AgentSkillRepository repo = repository(raw);
        MarketplaceStager stager = new MarketplaceStager(workspace);
        List<RepoBound> visible = List.of(new RepoBound(skill("valid"), repo));

        StageResult.Cached longSegments =
                assertInstanceOf(
                        StageResult.Cached.class,
                        stager.stage(visible, Map.of(), raw).get("valid"));
        assertEquals(64, longSegments.scopeSegment().length());
        assertEquals(longSegments.scopeSegment(), longSegments.sourceNamespace());

        StageResult.Cached shared =
                assertInstanceOf(
                        StageResult.Cached.class, stager.stage(visible, Map.of()).get("valid"));
        StageResult.Cached literal =
                assertInstanceOf(
                        StageResult.Cached.class,
                        stager.stage(visible, Map.of(), MarketplaceStager.SHARED_SCOPE)
                                .get("valid"));
        assertEquals(MarketplaceStager.SHARED_SCOPE, shared.scopeSegment());
        assertFalse(shared.scopeSegment().equals(literal.scopeSegment()));
    }

    @Test
    void legacyNestedCacheIsRebuiltAndCollectedAfterGrace() throws IOException {
        Path workspace = temp.resolve("workspace");
        Path legacyNamespace = workspace.resolve(".skills-cache/_shared/git-owner/repo");
        Files.createDirectories(legacyNamespace.resolve("valid"));
        Files.writeString(legacyNamespace.resolve("valid/marker.txt"), "legacy");
        Files.setLastModifiedTime(
                legacyNamespace,
                FileTime.from(
                        Instant.now()
                                .minus(MarketplaceStager.DEFAULT_ORPHAN_GRACE)
                                .minusSeconds(1)));
        AgentSkillRepository repo = repository("git-owner/repo");

        StageResult.Cached cached =
                assertInstanceOf(
                        StageResult.Cached.class,
                        new MarketplaceStager(workspace)
                                .stage(List.of(new RepoBound(skill("valid"), repo)), Map.of())
                                .get("valid"));

        assertFalse(Files.exists(legacyNamespace.getParent()));
        assertEquals(
                "marker",
                Files.readString(
                        workspace
                                .resolve(".skills-cache/_shared")
                                .resolve(cached.sourceNamespace())
                                .resolve("valid/marker.txt")));
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
    @ValueSource(
            strings = {
                "Mixed Case",
                "my_skill-1.2",
                "name..with.dots",
                "CONSOLE",
                "COM10",
                "LPT0",
                "x.NUL"
            })
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

        StageResult.Cached cached = assertInstanceOf(StageResult.Cached.class, result.get(name));
        Path staged =
                temp.resolve("workspace/.skills-cache/_shared")
                        .resolve(cached.sourceNamespace())
                        .resolve(name);
        assertEquals("_shared", cached.scopeSegment());
        assertEquals(name, cached.skillName());
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
