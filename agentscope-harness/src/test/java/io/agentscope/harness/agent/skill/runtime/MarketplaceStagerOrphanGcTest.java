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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.RepoBound;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Orphan-GC behaviour of {@link MarketplaceStager} under a <em>shared</em> cache root.
 *
 * <p>{@code stage()} rebuilds its retain-list from one call's visible skills, but the
 * {@code .skills-cache} tree is shared by every concurrent call against the workspace (and by
 * other replicas on a shared volume). With per-user skill visibility, call B's "orphan" is call
 * A's live {@code filesRoot}, so GC must (a) leave recently staged directories alone and (b)
 * never abort a call when the tree changes underneath a traversal.
 */
class MarketplaceStagerOrphanGcTest {

    private static final String NS = "market";

    @Test
    @DisplayName("A skill dropped from the visible set survives while it is still fresh")
    void freshOrphanIsKept(@TempDir Path workspace) {
        StubRepo repo = new StubRepo(NS);
        MarketplaceStager stager = new MarketplaceStager(workspace);

        stager.stage(List.of(bound("alpha", repo), bound("beta", repo)), namespaces(repo));
        // Second call sees only "alpha" — e.g. a different user's visibility filter.
        stager.stage(List.of(bound("alpha", repo)), namespaces(repo));

        assertTrue(
                Files.isDirectory(skillDir(workspace, "beta")),
                "a directory staged seconds ago must not be GC'd on another call's behalf");
    }

    @Test
    @DisplayName("An orphan untouched past the grace window is reclaimed")
    void staleOrphanIsReclaimed(@TempDir Path workspace) throws IOException {
        StubRepo repo = new StubRepo(NS);
        MarketplaceStager stager = new MarketplaceStager(workspace, Duration.ofHours(6));

        stager.stage(List.of(bound("alpha", repo), bound("beta", repo)), namespaces(repo));
        Files.setLastModifiedTime(
                skillDir(workspace, "beta"),
                FileTime.from(Instant.now().minus(Duration.ofDays(7))));

        stager.stage(List.of(bound("alpha", repo)), namespaces(repo));

        assertFalse(Files.exists(skillDir(workspace, "beta")), "stale orphan should be reclaimed");
        assertTrue(
                Files.isDirectory(skillDir(workspace, "alpha")), "retained skill must survive GC");
    }

    @Test
    @DisplayName("Concurrent calls with different visible sets never fail staging")
    void concurrentCallsWithDifferentVisibleSetsDoNotThrow(@TempDir Path workspace)
            throws InterruptedException {
        StubRepo repo = new StubRepo(NS);
        // Zero grace maximises the pressure: every pass deletes the other caller's tree while
        // that caller is still walking it.
        MarketplaceStager stager = new MarketplaceStager(workspace, Duration.ZERO);
        Map<AgentSkillRepository, String> ns = namespaces(repo);

        List<RepoBound> both = List.of(bound("alpha", repo), bound("beta", repo));
        List<RepoBound> alphaOnly = List.of(bound("alpha", repo));

        AtomicInteger failures = new AtomicInteger();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);

        for (List<RepoBound> visible : List.of(both, alphaOnly)) {
            Thread t =
                    new Thread(
                            () -> {
                                try {
                                    start.await();
                                    for (int i = 0; i < 150; i++) {
                                        stager.stage(visible, ns);
                                    }
                                } catch (Throwable e) {
                                    failures.incrementAndGet();
                                    firstFailure.compareAndSet(null, e);
                                } finally {
                                    finished.countDown();
                                }
                            });
            t.setDaemon(true);
            t.start();
        }
        start.countDown();
        assertTrue(finished.await(60, TimeUnit.SECONDS), "staging threads should finish");

        assertEquals(
                0,
                failures.get(),
                () ->
                        "stage() must tolerate a concurrent call mutating the shared cache, but"
                                + " threw "
                                + firstFailure.get());
    }

    @Test
    @DisplayName("An unreadable entry under the cache degrades GC, not the call")
    void unreadableEntryDoesNotFailStage(@TempDir Path workspace) throws IOException {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "requires POSIX permissions");
        StubRepo repo = new StubRepo(NS);
        MarketplaceStager stager = new MarketplaceStager(workspace, Duration.ZERO);

        stager.stage(List.of(bound("alpha", repo)), namespaces(repo));
        Path sub = skillDir(workspace, "alpha").resolve("scripts");
        assertTrue(Files.isDirectory(sub), "fixture should have staged a subdirectory");
        Files.setPosixFilePermissions(sub, Set.of());
        try {
            // "alpha" is now an orphan, and walking it fails part-way through.
            stager.stage(List.of(bound("beta", repo)), namespaces(repo));
        } finally {
            Files.setPosixFilePermissions(sub, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    @DisplayName("invalidateAll clears the cache and does not create one that was absent")
    void invalidateAllPurgesWithoutCreating(@TempDir Path workspace) {
        StubRepo repo = new StubRepo(NS);
        MarketplaceStager stager = new MarketplaceStager(workspace);

        // Absent cache: an explicit purge must stay a no-op rather than create the tree.
        stager.invalidateAll();
        assertFalse(
                Files.exists(workspace.resolve(MarketplaceStager.CACHE_DIR)),
                "invalidateAll must not create the cache it was asked to clear");

        // Populated cache: the purge must actually remove it. This is the documented escape
        // hatch for mounts where automatic GC is disabled, so it must not share that gate.
        stager.stage(List.of(bound("alpha", repo)), namespaces(repo));
        assertTrue(Files.isDirectory(skillDir(workspace, "alpha")), "fixture should have staged");

        stager.invalidateAll();
        assertFalse(
                Files.exists(workspace.resolve(MarketplaceStager.CACHE_DIR)),
                "invalidateAll must clear the whole cache");
    }

    @Test
    @DisplayName("A sweep in one scope cannot reach another scope's tree, even when aged")
    void anotherScopeIsUnreachableBySweep(@TempDir Path workspace) throws IOException {
        StubRepo repo = new StubRepo(NS);
        MarketplaceStager stager = new MarketplaceStager(workspace);
        Map<AgentSkillRepository, String> ns = namespaces(repo);

        stager.stage(List.of(bound("alpha", repo), bound("beta", repo)), ns, "bob");
        Path bobBeta = skillDir(workspace, "bob", "beta");
        assertTrue(Files.isDirectory(bobBeta), "fixture should have staged beta for bob");

        // Age it well past the grace window: staleness is the one thing that would make a
        // sweep delete it, so if scoping works this still survives.
        age(bobBeta);

        // Alice never sees "beta". Under the old flat layout her sweep deleted it; her sweep
        // now runs against .skills-cache/alice and cannot address bob's tree at all.
        for (int i = 0; i < 5; i++) {
            stager.stage(List.of(bound("alpha", repo)), ns, "alice");
        }

        assertTrue(Files.isDirectory(bobBeta), "another scope's sweep must not delete this tree");
        assertEquals(
                9, fileCount(bobBeta), "bob's staged files must be untouched by alice's sweeps");
    }

    @Test
    @DisplayName("Identities that sanitise alike still get separate subtrees")
    void collidingScopeNamesDoNotShareASubtree(@TempDir Path workspace) {
        StubRepo repo = new StubRepo(NS);
        MarketplaceStager stager = new MarketplaceStager(workspace);
        Map<AgentSkillRepository, String> ns = namespaces(repo);

        // Both flatten to "alice_corp.com" under a plain character substitution.
        stager.stage(List.of(bound("alpha", repo)), ns, "alice@corp.com");
        stager.stage(List.of(bound("beta", repo)), ns, "alice#corp.com");

        Path cacheRoot = workspace.resolve(MarketplaceStager.CACHE_DIR);
        try (var scopes = Files.list(cacheRoot)) {
            assertEquals(
                    2,
                    scopes.count(),
                    "two identities must never be mapped onto one scope subtree");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // =========================================================================
    //  Fixtures
    // =========================================================================

    private static Path skillDir(Path workspace, String name) {
        return skillDir(workspace, MarketplaceStager.SHARED_SCOPE, name);
    }

    private static Path skillDir(Path workspace, String scope, String name) {
        return workspace
                .resolve(MarketplaceStager.CACHE_DIR)
                .resolve(scope)
                .resolve(NS)
                .resolve(name);
    }

    private static Map<AgentSkillRepository, String> namespaces(AgentSkillRepository repo) {
        return MarketplaceStager.resolveSourceNamespaces(List.of(repo));
    }

    /** A skill with a subdirectory — the shape that made the original traversal abort. */
    private static RepoBound bound(String name, AgentSkillRepository repo) {
        return bound(name, repo, 8);
    }

    private static RepoBound bound(String name, AgentSkillRepository repo, int scriptCount) {
        Map<String, String> resources = new LinkedHashMap<>();
        resources.put("SKILL.md", "# " + name + "\n");
        for (int i = 0; i < scriptCount; i++) {
            resources.put("scripts/run" + i + ".sh", "#!/bin/sh\necho " + name + "\n");
        }
        return new RepoBound(new AgentSkill(name, "desc", "# " + name + "\n", resources, NS), repo);
    }

    private static long fileCount(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).count();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static void age(Path dir) throws IOException {
        Files.setLastModifiedTime(dir, FileTime.from(Instant.now().minus(Duration.ofDays(7))));
    }

    /** Minimal repository stub: the stager only reads {@link #getSource()}. */
    private static final class StubRepo implements AgentSkillRepository {

        private final String source;

        StubRepo(String source) {
            this.source = source;
        }

        @Override
        public AgentSkill getSkill(String name) {
            return null;
        }

        @Override
        public List<String> getAllSkillNames() {
            return List.of();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return List.of();
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean force) {
            return false;
        }

        @Override
        public boolean delete(String skillName) {
            return false;
        }

        @Override
        public boolean skillExists(String skillName) {
            return false;
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo(source, "", false);
        }

        @Override
        public String getSource() {
            return source;
        }

        @Override
        public void setWriteable(boolean writeable) {}

        @Override
        public boolean isWriteable() {
            return false;
        }
    }
}
