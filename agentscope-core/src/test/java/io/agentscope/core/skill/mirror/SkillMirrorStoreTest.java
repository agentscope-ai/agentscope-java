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
package io.agentscope.core.skill.mirror;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the version-keyed cache: materialization, version directory naming,
 * immutability and reuse (including a complete directory installed by "another process"),
 * per-root and per-identity isolation, per-call store acquisition, name validation,
 * concurrent same-key deduplication, and the refuse-and-clean-up guarantees around bad
 * fetches and leftover targets.
 */
@Tag("unit")
@DisplayName("SkillMirrorStore version-keyed mirror cache")
class SkillMirrorStoreTest {

    @TempDir Path tempDir;

    /** Builds a fully-populated test skill (content plus one resource) under the given name. */
    private static AgentSkill skill(String name, String content) {
        return new AgentSkill(
                name,
                "Description of " + name,
                content,
                Map.of("assets/note.txt", "resource of " + name),
                "test-source");
    }

    /** A fetcher answer that successfully returns the given skill. */
    private static Optional<AgentSkill> fetched(AgentSkill skill) {
        return Optional.of(skill);
    }

    /**
     * Parses an installed {@code SKILL.md} back to its frontmatter-stripped body — installed
     * manifests carry regenerated frontmatter (the standard package form), so assertions
     * round-trip through the parser instead of comparing raw file text.
     */
    private static String manifestBody(Path skillMd) throws Exception {
        return MarkdownSkillParser.parse(Files.readString(skillMd, StandardCharsets.UTF_8))
                .getContent();
    }

    @Test
    @DisplayName("Materialize installs a standard skill package under the version directory")
    void testMaterializeInstallsCompleteSkill() throws Exception {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));
        Path dir =
                store.materialize(
                                "repo_a",
                                "demo-skill",
                                "1.0.0",
                                () -> fetched(skill("demo-skill", "# body")))
                        .orElseThrow();

        assertEquals("1.0.0", dir.getFileName().toString(), "the version names the directory");
        var manifest =
                MarkdownSkillParser.parse(
                        Files.readString(dir.resolve("SKILL.md"), StandardCharsets.UTF_8));
        assertEquals("# body", manifest.getContent(), "the body is preserved verbatim");
        assertEquals(
                "demo-skill",
                manifest.getMetadata().get("name"),
                "the manifest regenerates the frontmatter");
        assertEquals(
                "resource of demo-skill",
                Files.readString(dir.resolve("assets/note.txt"), StandardCharsets.UTF_8));
        assertTrue(store.lookup("repo_a", "demo-skill", "1.0.0").isPresent(), "lookup must hit");
    }

    @Test
    @DisplayName("of returns a fresh instance per call, normalized to the same root")
    void testOfReturnsFreshInstancePerCall() {
        SkillMirrorStore first = SkillMirrorStore.of(tempDir.resolve("nested/../root"));

        assertNotSame(
                first,
                SkillMirrorStore.of(tempDir.resolve("root")),
                "every call yields a fresh instance — no process-level registry");
        assertEquals(
                first.root(),
                SkillMirrorStore.of(tempDir.resolve("root")).root(),
                "path spellings normalize to the same bound root (stable key-lock identity)");
    }

    @Test
    @DisplayName("An existing version directory is reused, never rewritten")
    void testExistingVersionDirIsReusedNotRewritten() throws Exception {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));
        Path first =
                store.materialize(
                                "repo_a",
                                "demo-skill",
                                "v1",
                                () -> fetched(skill("demo-skill", "# old")))
                        .orElseThrow();
        long marker = Files.getLastModifiedTime(first.resolve("SKILL.md")).toMillis();
        Thread.sleep(10);

        Path second =
                store.materialize(
                                "repo_a",
                                "demo-skill",
                                "v1",
                                () -> fetched(skill("demo-skill", "# new")))
                        .orElseThrow();

        assertEquals(first, second, "the same version must reuse the same directory");
        assertEquals(
                "# old", manifestBody(first.resolve("SKILL.md")), "content must not be rewritten");
        assertEquals(
                marker,
                Files.getLastModifiedTime(first.resolve("SKILL.md")).toMillis(),
                "the reused directory must not be touched");
    }

    @Test
    @DisplayName("Concurrent material versions coexist as separate directories")
    void testDifferentVersionsCoexist() {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));
        Path v1 =
                store.materialize(
                                "repo_a",
                                "demo-skill",
                                "v1",
                                () -> fetched(skill("demo-skill", "# v1")))
                        .orElseThrow();
        Path v2 =
                store.materialize(
                                "repo_a",
                                "demo-skill",
                                "v2",
                                () -> fetched(skill("demo-skill", "# v2")))
                        .orElseThrow();

        assertNotEquals(v1, v2, "two versions are two directories — no overwrite, no pointer");
        assertTrue(store.lookup("repo_a", "demo-skill", "v1").isPresent());
        assertTrue(store.lookup("repo_a", "demo-skill", "v2").isPresent());
    }

    @Test
    @DisplayName("Version directory names: verbatim when safe, sanitized with hash otherwise")
    void testVersionDirNaming() {
        assertEquals("1.0.0", SkillMirrorStore.versionDirName("1.0.0"), "safe names are verbatim");
        assertEquals("v1.2.3-rc1", SkillMirrorStore.versionDirName("v1.2.3-rc1"));

        String unsafe = SkillMirrorStore.versionDirName("refs/tags/v1");
        assertTrue(
                unsafe.startsWith("refs_tags_v1-"), "unsafe characters are sanitized: " + unsafe);
        assertEquals(unsafe, SkillMirrorStore.versionDirName("refs/tags/v1"), "deterministic");

        // Information-losing sanitization must not collide distinct versions.
        assertNotEquals(
                SkillMirrorStore.versionDirName("a b"),
                SkillMirrorStore.versionDirName("a_b"),
                "distinct versions never share a directory");

        // Case-insensitive filesystems must not collide "V1" with "v1".
        assertNotEquals(
                SkillMirrorStore.versionDirName("V1"),
                SkillMirrorStore.versionDirName("v1"),
                "case-distinct versions never share a directory");

        assertTrue(
                SkillMirrorStore.versionDirName("nul").startsWith("nul-"),
                "Windows-reserved names are never used verbatim");
    }

    @Test
    @DisplayName("Unsafe version strings materialize and look up at the same derived path")
    void testUnsafeVersionRoundtrips() {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));
        Path dir =
                store.materialize(
                                "repo_a",
                                "demo-skill",
                                "refs/tags/v1",
                                () -> fetched(skill("demo-skill", "# tagged")))
                        .orElseThrow();

        assertEquals(
                SkillMirrorStore.versionDirName("refs/tags/v1"),
                dir.getFileName().toString(),
                "the directory is the derived version name");
        assertEquals(
                dir,
                store.lookup("repo_a", "demo-skill", "refs/tags/v1").orElseThrow(),
                "lookup derives the same path");
    }

    @Test
    @DisplayName("A fetcher miss materializes nothing and reports empty")
    void testFetcherMissInstallsNothing() {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));

        assertTrue(
                store.materialize("repo_a", "demo-skill", "v1", Optional::empty).isEmpty(),
                "an unreachable version must not install anything");
        assertTrue(store.lookup("repo_a", "demo-skill", "v1").isEmpty());
    }

    @Test
    @DisplayName("Out-of-band deletion turns into a miss and re-materializes deterministically")
    void testOutOfBandDeletionThenRematerializeSucceeds() throws Exception {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));
        Path dir =
                store.materialize(
                                "repo_a",
                                "demo-skill",
                                "1.0.0",
                                () -> fetched(skill("demo-skill", "# body")))
                        .orElseThrow();
        deleteTree(dir);

        assertTrue(store.lookup("repo_a", "demo-skill", "1.0.0").isEmpty(), "deleted = miss");

        Path again =
                store.materialize(
                                "repo_a",
                                "demo-skill",
                                "1.0.0",
                                () -> fetched(skill("demo-skill", "# body")))
                        .orElseThrow();
        assertEquals(dir, again, "the same version re-materializes at the same path");
    }

    /** Deletes a tree children-first. */
    private static void deleteTree(Path dir) throws Exception {
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    @DisplayName("A complete directory from another process is reused without re-fetching")
    void testCompleteDirFromAnotherProcessIsReusedWithoutFetch() throws Exception {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));
        // Simulate another process having won the atomic move: a complete directory exists.
        store.materialize(
                "repo_a", "demo-skill", "1.0.0", () -> fetched(skill("demo-skill", "# cached")));

        Path reused =
                store.materialize(
                                "repo_a",
                                "demo-skill",
                                "1.0.0",
                                () -> {
                                    throw new AssertionError(
                                            "a complete cache is never re-fetched");
                                })
                        .orElseThrow();

        assertEquals(
                "# cached",
                manifestBody(reused.resolve("SKILL.md")),
                "the existing directory wins, Maven-cache style");
    }

    @Test
    @DisplayName("Per-root isolation: same key, two roots, independent trees")
    void testPerRootIsolation() throws Exception {
        SkillMirrorStore first = new SkillMirrorStore(tempDir.resolve("root1"));
        SkillMirrorStore second = new SkillMirrorStore(tempDir.resolve("root2"));
        Path dirA =
                first.materialize(
                                "repo_a",
                                "demo-skill",
                                "v1",
                                () -> fetched(skill("demo-skill", "# a")))
                        .orElseThrow();
        Path dirB =
                second.materialize(
                                "repo_a",
                                "demo-skill",
                                "v1",
                                () -> fetched(skill("demo-skill", "# b")))
                        .orElseThrow();

        assertNotEquals(dirA, dirB, "different roots never share directories");
        assertEquals("# a", manifestBody(dirA.resolve("SKILL.md")));
        assertEquals("# b", manifestBody(dirB.resolve("SKILL.md")));
    }

    @Test
    @DisplayName("Forked repository identities get distinct trees")
    void testForkedRepositoriesGetDistinctTrees() throws Exception {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));
        Path dirA =
                store.materialize(
                                "repo_a",
                                "demo-skill",
                                "v1",
                                () -> fetched(skill("demo-skill", "# a")))
                        .orElseThrow();
        Path dirB =
                store.materialize(
                                "repo_b",
                                "demo-skill",
                                "v1",
                                () -> fetched(skill("demo-skill", "# b")))
                        .orElseThrow();

        assertNotEquals(dirA, dirB, "different identities never cross-read");
        assertTrue(store.lookup("repo_a", "demo-skill", "v1").isPresent());
        assertTrue(store.lookup("repo_b", "demo-skill", "v1").isPresent());
    }

    @Test
    @DisplayName("The 0a name gate blocks materialization of invalid names")
    void testNameGateBlocksMaterialization() {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.materialize(
                                "repo_a", "../escape", "v1", () -> fetched(skill("x", "# x"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.materialize(
                                "repo_a", "Bad_Name", "v1", () -> fetched(skill("x", "# x"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.materialize("repo_a", "con", "v1", () -> fetched(skill("x", "# x"))),
                "a Windows-reserved device name cannot become a mirror directory segment");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.materialize(
                                "repo_a", "demo-skill", "  ", () -> fetched(skill("x", "# x"))),
                "a blank version is refused like an invalid name");
    }

    @Test
    @DisplayName("lookup treats unusable inputs as a miss, never escaping the tree")
    void testLookupGatesUnusableInputs() {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));

        assertTrue(store.lookup("repo_a", "../escape", "v1").isEmpty());
        assertTrue(store.lookup("repo_a", "Bad_Name", "v1").isEmpty());
        assertTrue(store.lookup("repo_a", "con", "v1").isEmpty());
        assertTrue(store.lookup("repo_a", "demo-skill", null).isEmpty());
        assertTrue(store.lookup("repo_a", "demo-skill", "  ").isEmpty());
    }

    @Test
    @DisplayName("Concurrent materialization of the same version dedupes to one fetch")
    void testConcurrentSameVersionDedupes() throws Exception {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<Path>> first =
                    pool.submit(
                            () -> {
                                start.await();
                                return store.materialize(
                                        "repo_a",
                                        "demo-skill",
                                        "v1",
                                        () -> {
                                            fetches.incrementAndGet();
                                            return fetched(skill("demo-skill", "# body"));
                                        });
                            });
            Future<Optional<Path>> second =
                    pool.submit(
                            () -> {
                                start.await();
                                return store.materialize(
                                        "repo_a",
                                        "demo-skill",
                                        "v1",
                                        () -> {
                                            fetches.incrementAndGet();
                                            return fetched(skill("demo-skill", "# other"));
                                        });
                            });
            start.countDown();
            Path dirA = first.get().orElseThrow();
            Path dirB = second.get().orElseThrow();

            assertEquals(dirA, dirB, "both callers receive the same materialized directory");
            assertEquals(1, fetches.get(), "the key lock deduplicates the fetch");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("repoIdentity sanitizes unsafe characters while staying deterministic")
    void testRepoIdentitySanitization() throws Exception {
        String identity = SkillMirrorStore.repoIdentity("git", "https://x/y.git");
        assertTrue(identity.startsWith("git_https___x_y.git"));
        assertEquals(identity, SkillMirrorStore.repoIdentity("git", "https://x/y.git"));

        // Case-insensitive filesystems must not merge two identities into one tree, and a
        // trailing dot must not collapse onto the dotless name (Win32 strips it).
        assertNotEquals(
                SkillMirrorStore.repoIdentity("git", "Repo"),
                SkillMirrorStore.repoIdentity("git", "repo"));
        assertNotEquals(
                SkillMirrorStore.repoIdentity("git", "repo."),
                SkillMirrorStore.repoIdentity("git", "repo"));
    }

    // ------------------------------------------------------------------
    //  Cache root resolution and serving
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolveBuiltinRoot: explicit cacheDir wins, then the property, then home")
    void testResolveBuiltinRootPriority() {
        Path explicit = tempDir.resolve("explicit");
        assertEquals(
                explicit,
                SkillMirrorStore.resolveBuiltinRoot(explicit, "repo_a"),
                "the explicit cacheDir always wins");

        String key = SkillMirrorStore.CACHE_DIR_PROPERTY;
        String previous = System.getProperty(key);
        try {
            Path override = tempDir.resolve("override");
            System.setProperty(key, override.toString());
            assertEquals(
                    override,
                    SkillMirrorStore.resolveBuiltinRoot(null, "repo_a"),
                    "the system property overrides the default home root");
            assertEquals(
                    explicit,
                    SkillMirrorStore.resolveBuiltinRoot(explicit, "repo_a"),
                    "the explicit cacheDir wins over the system property");
            System.setProperty(key, "relative-cache");
            assertEquals(
                    Path.of("relative-cache").toAbsolutePath(),
                    SkillMirrorStore.resolveBuiltinRoot(null, "repo_a"),
                    "a relative override is pinned absolute, not CWD-dependent");
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }

        String home = System.getProperty("user.home");
        String homePrevious = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            assertEquals(
                    tempDir.resolve(".agentscope").resolve("skill-cache").resolve("repo_a"),
                    SkillMirrorStore.resolveBuiltinRoot(null, "repo_a"),
                    "the default root lives under ~/.agentscope/skill-cache/<identity>");
        } finally {
            System.setProperty("user.home", homePrevious == null ? "" : home);
            if (home == null) {
                System.clearProperty("user.home");
            }
        }
    }

    @Test
    @DisplayName("resolveBuiltinRoot fails fast when no root can be resolved")
    void testResolveBuiltinRootFailsWithoutHome() {
        String key = SkillMirrorStore.CACHE_DIR_PROPERTY;
        String propertyPrevious = System.getProperty(key);
        String homePrevious = System.getProperty("user.home");
        try {
            System.clearProperty(key);
            System.clearProperty("user.home");
            IllegalArgumentException e =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> SkillMirrorStore.resolveBuiltinRoot(null, "repo_a"));
            assertTrue(e.getMessage().contains("user.home"));
        } finally {
            if (propertyPrevious == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, propertyPrevious);
            }
            if (homePrevious != null) {
                System.setProperty("user.home", homePrevious);
            }
        }
    }

    @Test
    @DisplayName("serve returns the summary form: version stamped, originDir set, no resources")
    void testServeReturnsSummaryForm() throws Exception {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));
        AtomicInteger fetches = new AtomicInteger();

        AgentSkill served =
                store.serve(
                                "repo_a",
                                "demo-skill",
                                "v1",
                                "src-x",
                                () -> {
                                    fetches.incrementAndGet();
                                    return fetched(skill("demo-skill", "# body"));
                                })
                        .orElseThrow();

        assertEquals("demo-skill", served.getName());
        assertEquals("Description of demo-skill", served.getDescription());
        assertEquals("v1", served.getVersion());
        assertEquals("src-x", served.getSource());
        assertTrue(served.getResources().isEmpty(), "the summary carries no resource map");
        assertTrue(served.getOriginDir().isPresent());
        assertEquals("# body", manifestBody(served.getOriginDir().get().resolve("SKILL.md")));
        assertEquals(
                "resource of demo-skill",
                served.getResource("assets/note.txt"),
                "resources resolve from originDir on demand");

        // A second serve is a pure cache hit — the fetcher never runs again.
        AgentSkill again =
                store.serve(
                                "repo_a",
                                "demo-skill",
                                "v1",
                                "src-x",
                                () -> {
                                    fetches.incrementAndGet();
                                    return fetched(skill("demo-skill", "# other"));
                                })
                        .orElseThrow();
        assertEquals(1, fetches.get(), "the cache hit must not invoke the fetcher");
        assertEquals("# body", manifestBody(again.getOriginDir().get().resolve("SKILL.md")));
    }

    @Test
    @DisplayName("serve self-heals a complete but unparseable cache entry")
    void testServeEvictsAndRebuildsCorruptEntry() throws Exception {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));
        AtomicInteger fetches = new AtomicInteger();

        AgentSkill first =
                store.serve(
                                "repo_a",
                                "demo-skill",
                                "v1",
                                "src-x",
                                () -> {
                                    fetches.incrementAndGet();
                                    return fetched(skill("demo-skill", "# body"));
                                })
                        .orElseThrow();
        Path dir = first.getOriginDir().get();
        assertEquals(1, fetches.get());

        // Corrupt the installed manifest: a complete directory (SKILL.md present) whose
        // frontmatter no longer matches its key — unparseable forever unless evicted.
        Files.writeString(
                dir.resolve("SKILL.md"),
                "---\nname: other-skill\ndescription: Foreign entry\n---\n# foreign\n",
                StandardCharsets.UTF_8);

        AgentSkill healed =
                store.serve(
                                "repo_a",
                                "demo-skill",
                                "v1",
                                "src-x",
                                () -> {
                                    fetches.incrementAndGet();
                                    return fetched(skill("demo-skill", "# fresh body"));
                                })
                        .orElseThrow();

        assertEquals("demo-skill", healed.getName());
        assertEquals("# fresh body", manifestBody(healed.getOriginDir().get().resolve("SKILL.md")));
        assertEquals(2, fetches.get(), "the corrupt entry must be evicted and re-fetched once");
    }

    @Test
    @DisplayName("serve refuses unusable inputs without contacting the fetcher")
    void testServeGatesUnusableInputs() {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));
        AtomicInteger fetches = new AtomicInteger();
        java.util.function.Supplier<Optional<AgentSkill>> fetcher =
                () -> {
                    fetches.incrementAndGet();
                    return fetched(skill("demo-skill", "# body"));
                };

        assertTrue(store.serve("repo_a", null, "v1", "src", fetcher).isEmpty());
        assertTrue(store.serve("repo_a", "  ", "v1", "src", fetcher).isEmpty());
        assertTrue(store.serve("repo_a", "Bad_Name", "v1", "src", fetcher).isEmpty());
        assertTrue(store.serve("repo_a", "nul", "v1", "src", fetcher).isEmpty());
        assertTrue(store.serve("repo_a", "demo-skill", null, "src", fetcher).isEmpty());
        assertTrue(store.serve("repo_a", "demo-skill", "  ", "src", fetcher).isEmpty());
        assertEquals(0, fetches.get(), "no fetcher may run for refused inputs");
    }

    @Test
    @DisplayName("serve decodes base64 transport resources into binary files")
    void testServeDecodesBase64Resources() throws Exception {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));
        AgentSkill binary =
                new AgentSkill(
                        "demo-skill",
                        "Description of demo-skill",
                        "# body",
                        Map.of("assets/logo.bin", "base64:AQID"),
                        "src-x");

        Path dir =
                store.serve("repo_a", "demo-skill", "v1", "src-x", () -> Optional.of(binary))
                        .orElseThrow()
                        .getOriginDir()
                        .orElseThrow();

        assertArrayEquals(
                new byte[] {1, 2, 3},
                Files.readAllBytes(dir.resolve("assets").resolve("logo.bin")),
                "the base64 transport form is decoded on disk write");

        AgentSkill broken =
                new AgentSkill(
                        "demo-skill",
                        "Description of demo-skill",
                        "# body",
                        Map.of("assets/bad.bin", "base64:###not-base64###"),
                        "src-x");
        IllegalArgumentException rejected =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                store.serve(
                                        "repo_a",
                                        "demo-skill",
                                        "v2",
                                        "src-x",
                                        () -> Optional.of(broken)));
        assertTrue(
                rejected.getMessage().contains("assets/bad.bin"),
                "the failure names the offending resource");
    }

    @Test
    @DisplayName("A fetched skill whose metadata name mismatches the key is refused")
    void testMaterializeRefusesNameMismatch() {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                store.materialize(
                                        "repo_a",
                                        "demo-skill",
                                        "v1",
                                        () -> fetched(skill("other-skill", "# body"))));
        assertTrue(e.getMessage().contains("other-skill"), "the failure names the declared name");
        assertTrue(
                store.lookup("repo_a", "demo-skill", "v1").isEmpty(),
                "a mismatched fetch installs nothing");
    }

    @Test
    @DisplayName("A resource named SKILL.md is refused and leaves no temporary sibling")
    void testManifestResourceIsRefused() throws Exception {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));
        AgentSkill carrying =
                new AgentSkill(
                        "demo-skill",
                        "Description of demo-skill",
                        "# body",
                        Map.of(SkillMirrorStore.SKILL_FILE, "# stowaway"),
                        "src-x");

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                store.materialize(
                                        "repo_a", "demo-skill", "v1", () -> fetched(carrying)));
        assertTrue(e.getMessage().contains(SkillMirrorStore.SKILL_FILE));
        assertTrue(store.lookup("repo_a", "demo-skill", "v1").isEmpty());

        Path skillParent = tempDir.resolve("root").resolve("repo_a").resolve("demo-skill");
        if (Files.exists(skillParent)) {
            try (var siblings = Files.list(skillParent)) {
                assertTrue(
                        siblings.noneMatch(p -> p.getFileName().toString().startsWith(".tmp-")),
                        "no temporary sibling survives a refused install");
            }
        }
    }

    @Test
    @DisplayName("An incomplete leftover target directory is cleared and replaced")
    void testIncompleteLeftoverTargetIsCleared() throws Exception {
        SkillMirrorStore store = new SkillMirrorStore(tempDir.resolve("root"));
        Path target =
                tempDir.resolve("root").resolve("repo_a").resolve("demo-skill").resolve("1.0.0");
        Files.createDirectories(target);
        Files.writeString(target.resolve("stray.txt"), "crash mid-delete leftover");

        Path dir =
                store.materialize(
                                "repo_a",
                                "demo-skill",
                                "1.0.0",
                                () -> fetched(skill("demo-skill", "# body")))
                        .orElseThrow();

        assertEquals(target, dir, "the leftover directory is replaced in place");
        assertEquals("# body", manifestBody(dir.resolve("SKILL.md")));
        assertTrue(Files.notExists(dir.resolve("stray.txt")), "the leftover content is gone");
        assertTrue(store.lookup("repo_a", "demo-skill", "1.0.0").isPresent());
    }
}
