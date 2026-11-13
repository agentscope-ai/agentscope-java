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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.repository.RuntimeContextSkillRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests of the version route inside {@link DynamicSkillMiddleware}: repositories
 * own content (the fake simulates a version cache — one fetch per {@code (name, version)},
 * stamped summary returns with {@code originDir}), the middleware owns binding. Covered:
 * zero re-fetch on unchanged versions, LATEST advance across calls via null pulls plus stamp
 * comparison, PIN anchors frozen against refresh, unreachable-pin policy (default fallback
 * with re-anchor, strict error), and versionless repositories keeping per-call semantics.
 */
@Tag("unit")
@DisplayName("DynamicSkillMiddleware version route")
class DynamicSkillMiddlewareVersionRouteTest {

    /**
     * Version-capable fake with a simulated version cache: the first pull of a {@code
     * (name, version)} fetches (counted) and materializes a version directory under the
     * cache root; later pulls of the same key are cache hits (zero fetch). Every return is
     * a stamped summary: empty resource map, {@code originDir} at the materialized
     * directory.
     */
    private static class VersionedFakeRepository implements AgentSkillRepository {
        private final Map<String, String> latestVersionByName = new LinkedHashMap<>();
        private final Map<String, Map<String, AgentSkill>> contentByVersion = new LinkedHashMap<>();
        private final Set<String> unreachableVersions = new HashSet<>();
        private final Map<String, String> mismatchedServes = new LinkedHashMap<>();
        private final Set<String> servedKeys = new HashSet<>();
        private final Path cacheRoot;

        int fetchCount;
        int nullPullCount;
        int listCount;

        VersionedFakeRepository(Path cacheRoot) {
            this.cacheRoot = cacheRoot;
        }

        /** Publishes a version: moves the latest pointer and stores the content stamped with
         * the version it was stored under. */
        void publish(String name, String version, String content) {
            latestVersionByName.put(name, version);
            AgentSkill skill =
                    new AgentSkill(
                            name,
                            "Description of " + name,
                            content,
                            Map.of("assets/note.txt", "note of " + version),
                            "fake");
            contentByVersion
                    .computeIfAbsent(name, k -> new LinkedHashMap<>())
                    .put(version, skill.toBuilder().version(version).build());
        }

        /** Makes one stored version report as unreachable from {@code getSkill}. */
        void markUnreachable(String name, String version) {
            unreachableVersions.add(name + "@" + version);
        }

        /** Makes explicit pulls of {@code name@version} answer the skill stored under
         * {@code servedVersion} — stamped with a version different from the one requested. */
        void markMismatched(String name, String version, String servedVersion) {
            mismatchedServes.put(name + "@" + version, servedVersion);
        }

        /** Lists the skills at their current latest versions (the bulk read). */
        @SuppressWarnings("deprecation")
        List<AgentSkill> snapshot() {
            List<AgentSkill> skills = new ArrayList<>();
            for (Map.Entry<String, String> entry : latestVersionByName.entrySet()) {
                skills.add(contentByVersion.get(entry.getKey()).get(entry.getValue()));
            }
            return skills;
        }

        /** Lists all skills at their latest versions; counts bulk reads. */
        @SuppressWarnings("deprecation")
        @Override
        public List<AgentSkill> getAllSkills() {
            listCount++;
            return snapshot();
        }

        /** Lists the published skill names. */
        @SuppressWarnings("deprecation")
        @Override
        public List<String> getAllSkillNames() {
            return new ArrayList<>(latestVersionByName.keySet());
        }

        /**
         * Serving read: a {@code null}/blank version resolves the latest; the first pull of
         * a {@code (name, version)} counts one fetch and materializes the version directory;
         * every pull returns the stamped summary form.
         */
        @Override
        public AgentSkill getSkill(String name, String version) {
            if (name == null) {
                return null;
            }
            if (version == null || version.isBlank()) {
                nullPullCount++;
                version = latestVersionByName.get(name);
                if (version == null) {
                    return null;
                }
            }
            String mismatched = mismatchedServes.get(name + "@" + version);
            if (mismatched != null) {
                version = mismatched;
            }
            if (unreachableVersions.contains(name + "@" + version)) {
                return null;
            }
            Map<String, AgentSkill> versions = contentByVersion.get(name);
            AgentSkill stored = versions != null ? versions.get(version) : null;
            if (stored == null) {
                return null;
            }
            if (servedKeys.add(name + "@" + version)) {
                fetchCount++;
            }
            return summaryOf(stored);
        }

        /** Materializes the version directory (first pull) and builds the summary form. */
        private AgentSkill summaryOf(AgentSkill stored) {
            Path dir = cacheRoot.resolve(stored.getName()).resolve(stored.getVersion());
            if (servedKeys.contains(stored.getName() + "@" + stored.getVersion())) {
                try {
                    Files.createDirectories(dir);
                    Files.writeString(
                            dir.resolve("SKILL.md"),
                            "# " + stored.getName() + " at " + stored.getVersion(),
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return stored.toBuilder().clearResources().skillContent(null).originDir(dir).build();
        }

        /** Read-only stub. */
        @Override
        public boolean save(List<AgentSkill> skills, boolean force) {
            return false;
        }

        /** Read-only stub. */
        @Override
        public boolean delete(String skillName) {
            return false;
        }

        /** Whether the name was ever published. */
        @Override
        public boolean skillExists(String skillName) {
            return latestVersionByName.containsKey(skillName);
        }

        /** Stable repository identity. */
        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("fake", "version-route-test", false);
        }

        /** Fixed source id. */
        @Override
        public String getSource() {
            return "fake";
        }

        /** No-op stub. */
        @Override
        public void setWriteable(boolean writeable) {}

        /** Always read-only. */
        @Override
        public boolean isWriteable() {
            return false;
        }

        /** No-op stub. */
        @Override
        public void close() {}
    }

    /** Context-scoped fake of a workspace-style repository: no version capability — every
     * version-addressed pull answers nothing, and the contextual bulk read serves unstamped
     * loads. */
    private static class ContextualFakeRepository extends VersionedFakeRepository
            implements RuntimeContextSkillRepository {

        ContextualFakeRepository(Path cacheRoot) {
            super(cacheRoot);
        }

        /** No version route at all: every versioned or null pull answers nothing. */
        @Override
        public AgentSkill getSkill(String name, String version) {
            return null;
        }

        /** Unstamped contextual loads — the per-call bulk read path (counted). */
        @Override
        public List<AgentSkill> getAllSkills(RuntimeContext context) {
            List<AgentSkill> unstamped = new ArrayList<>();
            for (AgentSkill skill : super.getAllSkills()) {
                unstamped.add(skill.toBuilder().version(null).build());
            }
            return unstamped;
        }
    }

    @TempDir Path tempDir;

    private long originalTtl;

    @BeforeEach
    void setUp() {
        originalTtl = DynamicSkillMiddleware.freshnessTtlMillis;
    }

    @AfterEach
    void tearDown() {
        DynamicSkillMiddleware.freshnessTtlMillis = originalTtl;
    }

    /** Builds an agent bound to the repository and an optional policy. */
    private ReActAgent agentWith(AgentSkillRepository repository, SkillVersionPolicy policy) {
        return agentWith(repository, policy, null);
    }

    /** Builds an agent bound to the repository, an optional policy, and a filter baseline. */
    private ReActAgent agentWith(
            AgentSkillRepository repository, SkillVersionPolicy policy, SkillFilter filter) {
        ReActAgent.Builder builder =
                ReActAgent.builder()
                        .name("version-route-agent")
                        .model(org.mockito.Mockito.mock(Model.class))
                        .skillRepository(repository);
        if (policy != null) {
            builder.skillVersionPolicy(policy);
        }
        if (filter != null) {
            builder.skillFilter(filter);
        }
        return builder.build();
    }

    /** Deletes the fake repository's cache subtree so pulls miss and re-fetch. */
    private void evictCache(VersionedFakeRepository repository) throws IOException {
        deleteRecursively(tempDir.resolve("repo-cache"));
        repository.servedKeys.clear();
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Wraps the repository in the middleware under test. */
    private static DynamicSkillMiddleware middlewareOf(AgentSkillRepository repository) {
        return new DynamicSkillMiddleware(List.of(repository), false);
    }

    @Test
    @DisplayName("LATEST unchanged version re-serves with zero re-fetch")
    void testCacheHitSkipsFetch() {
        VersionedFakeRepository repository =
                new VersionedFakeRepository(tempDir.resolve("repo-cache"));
        repository.publish("demo-skill", "v1", "# one");
        ReActAgent agent = agentWith(repository, null, SkillFilter.only("demo-skill"));
        DynamicSkillMiddleware middleware = middlewareOf(repository);
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s").build();

        String first = middleware.onSystemPrompt(agent, rc, "").block();
        int fetchesAfterFirst = repository.fetchCount;

        String second = middleware.onSystemPrompt(agent, rc, "").block();

        assertTrue(first.contains("demo-skill"));
        assertTrue(second.contains("demo-skill"));
        assertEquals(
                fetchesAfterFirst,
                repository.fetchCount,
                "an unchanged version re-serves with zero re-fetch (no pull before the TTL)");
    }

    @Test
    @DisplayName("Establishment pairs versions from the load stamps alone — zero version queries")
    void testEstablishmentPerformsZeroVersionQueries() {
        VersionedFakeRepository repository =
                new VersionedFakeRepository(tempDir.resolve("repo-cache"));
        repository.publish("demo-skill", "v1", "# one");
        ReActAgent agent = agentWith(repository, null, SkillFilter.only("demo-skill"));
        DynamicSkillMiddleware middleware = middlewareOf(repository);
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s").build();

        String prompt = middleware.onSystemPrompt(agent, rc, "").block();

        assertTrue(prompt.contains("demo-skill"), "the stamped skill must serve");
        assertEquals(
                1,
                repository.nullPullCount,
                "establishment pairs versions from the null pull's stamp alone — the"
                        + " content↔version race is structurally eliminated, not reconciled");
        // The repository (not the middleware) materialized the version directory, and the
        // served skill is the summary form bound to it.
        Path versionDir = tempDir.resolve("repo-cache").resolve("demo-skill").resolve("v1");
        assertTrue(
                Files.isRegularFile(versionDir.resolve("SKILL.md")),
                "the repository must materialise the version directory it serves from");
        SkillBox box = rc.get(SkillBox.class);
        AgentSkill served = box.getSkill("demo-skill_fake");
        assertTrue(served.getOriginDir().isPresent(), "the served summary carries originDir");
        assertEquals(versionDir, served.getOriginDir().get());
        assertTrue(served.getResources().isEmpty(), "the served summary carries no resources");
    }

    @Test
    @DisplayName("LATEST advances across calls: null pull resolves the new stamp")
    void testLatestAdvancesAcrossCalls() {
        DynamicSkillMiddleware.freshnessTtlMillis = 0;
        VersionedFakeRepository repository =
                new VersionedFakeRepository(tempDir.resolve("repo-cache"));
        repository.publish("demo-skill", "v1", "# one");
        ReActAgent agent = agentWith(repository, null, SkillFilter.only("demo-skill"));
        DynamicSkillMiddleware middleware = middlewareOf(repository);
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s").build();

        middleware.onSystemPrompt(agent, rc, "").block();
        repository.publish("demo-skill", "v2", "# two");
        String advanced = middleware.onSystemPrompt(agent, rc, "").block();

        assertEquals(
                2, repository.fetchCount, "a version advance is exactly one new repository fetch");
        assertEquals(
                2,
                repository.nullPullCount,
                "establishment and the freshness probe each perform one null pull");
        assertTrue(advanced.contains("demo-skill"));
        assertEquals(
                "v2",
                rc.get(SkillBox.class).getSkill("demo-skill_fake").getVersion(),
                "the entry must serve the freshly observed stamp");
    }

    @Test
    @DisplayName("PIN anchors freeze the version against refresh advances")
    void testPinAnchorFrozenAcrossCalls() {
        DynamicSkillMiddleware.freshnessTtlMillis = 0;
        VersionedFakeRepository repository =
                new VersionedFakeRepository(tempDir.resolve("repo-cache"));
        repository.publish("demo-skill", "v1", "# one");
        ReActAgent agent =
                agentWith(repository, SkillVersionPolicy.pinned(), SkillFilter.only("demo-skill"));
        DynamicSkillMiddleware middleware = middlewareOf(repository);
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s").build();

        middleware.onSystemPrompt(agent, rc, "").block();
        repository.publish("demo-skill", "v2", "# two");
        middleware.onSystemPrompt(agent, rc, "").block();

        assertEquals(
                1,
                repository.fetchCount,
                "a pinned skill must not advance with the latest version");
        assertEquals(
                "v1",
                rc.get(SkillBox.class).getSkill("demo-skill_fake").getVersion(),
                "the pinned entry keeps its anchored version");
    }

    @Test
    @DisplayName("Unreachable PIN falls back once: one-hop null pull, re-anchored, error log")
    void testUnreachablePinFallsBackOnce() throws IOException {
        io.agentscope.core.session.InMemorySessionLogStore store =
                new io.agentscope.core.session.InMemorySessionLogStore();
        io.agentscope.core.session.SessionLog log =
                store.open(
                        new io.agentscope.core.session.SessionKey("u", "version-route-agent", "j1"),
                        RuntimeContext.empty());
        VersionedFakeRepository repository =
                new VersionedFakeRepository(tempDir.resolve("repo-cache"));
        repository.publish("demo-skill", "v1", "# one");
        ReActAgent agent =
                agentWith(repository, SkillVersionPolicy.pinned(), SkillFilter.only("demo-skill"));
        DynamicSkillMiddleware middleware = middlewareOf(repository);

        // Session one (journaled) anchors v1; the repository materializes the version dir.
        RuntimeContext rc1 = RuntimeContext.builder().userId("u").sessionId("s2a").build();
        io.agentscope.core.session.SessionRecorder anchorWriter =
                new io.agentscope.core.session.SessionRecorder(log, "t1");
        rc1.put(io.agentscope.core.session.SessionRecorder.CONTEXT_KEY, anchorWriter);
        String first = middleware.onSystemPrompt(agent, rc1, "").block();
        assertTrue(first.contains("demo-skill"));
        anchorWriter.close();

        // The backend loses v1, the cache copy is evicted, and v3 becomes current — a resumed
        // session (same journal) adopts the v1 anchor, finds it unreachable, and falls back
        // once: one null pull brings the current content + stamp, the anchor re-anchors.
        repository.markUnreachable("demo-skill", "v1");
        repository.publish("demo-skill", "v3", "# three");
        evictCache(repository);

        RuntimeContext rc2 = RuntimeContext.builder().userId("u").sessionId("s2b").build();
        io.agentscope.core.session.SessionRecorder resume =
                new io.agentscope.core.session.SessionRecorder(log, "t2");
        rc2.put(io.agentscope.core.session.SessionRecorder.CONTEXT_KEY, resume);
        String second = middleware.onSystemPrompt(agent, rc2, "").block();

        assertTrue(
                second.contains("demo-skill"),
                "the one-shot fallback keeps the skill served under pin semantics");
        assertEquals(
                "v3",
                rc2.get(SkillBox.class).getSkill("demo-skill_fake").getVersion(),
                "the fallback serves the current stamped content");
        assertEquals(
                "v3",
                io.agentscope.core.skill.mirror.SkillAnchorService.findAnchor(
                                resume, "demo-skill_fake")
                        .orElseThrow()
                        .version(),
                "the journal re-anchors to the served version");
    }

    @Test
    @DisplayName("Unreachable PIN with failOnUnreachable() fails loudly")
    void testUnreachablePinStrictFails() throws IOException {
        io.agentscope.core.session.InMemorySessionLogStore store =
                new io.agentscope.core.session.InMemorySessionLogStore();
        io.agentscope.core.session.SessionLog log =
                store.open(
                        new io.agentscope.core.session.SessionKey("u", "version-route-agent", "j2"),
                        RuntimeContext.empty());
        VersionedFakeRepository repository =
                new VersionedFakeRepository(tempDir.resolve("repo-cache"));
        repository.publish("demo-skill", "v1", "# one");
        ReActAgent agent =
                agentWith(
                        repository,
                        SkillVersionPolicy.pinned().failOnUnreachable(),
                        SkillFilter.only("demo-skill"));
        DynamicSkillMiddleware middleware = middlewareOf(repository);

        // Session one (journaled) anchors v1.
        RuntimeContext rc1 = RuntimeContext.builder().userId("u").sessionId("s3a").build();
        io.agentscope.core.session.SessionRecorder anchorWriter =
                new io.agentscope.core.session.SessionRecorder(log, "t1");
        rc1.put(io.agentscope.core.session.SessionRecorder.CONTEXT_KEY, anchorWriter);
        String first = middleware.onSystemPrompt(agent, rc1, "").block();
        assertTrue(first.contains("demo-skill"));
        anchorWriter.close();

        // v1 is gone from the backend, the repository's cache copy is evicted, and the
        // latest pointer moved to v3 — the resumed session adopts the v1 anchor, its explicit
        // pull fails, and the strict policy (not a silent swap to v3) decides.
        repository.markUnreachable("demo-skill", "v1");
        repository.publish("demo-skill", "v3", "# three");
        evictCache(repository);

        RuntimeContext rc2 = RuntimeContext.builder().userId("u").sessionId("s3b").build();
        rc2.put(
                io.agentscope.core.session.SessionRecorder.CONTEXT_KEY,
                new io.agentscope.core.session.SessionRecorder(log, "t2"));
        assertThrows(
                IllegalStateException.class,
                () -> middleware.onSystemPrompt(agent, rc2, "").block(),
                "the strict policy must surface the broken anchor");
    }

    @Test
    @DisplayName("Mismatched PIN serve (stamp ≠ requested) is refused and routed to the fallback")
    void testMismatchedServeRefusedAndFallsBack() throws IOException {
        io.agentscope.core.session.InMemorySessionLogStore store =
                new io.agentscope.core.session.InMemorySessionLogStore();
        io.agentscope.core.session.SessionLog log =
                store.open(
                        new io.agentscope.core.session.SessionKey("u", "version-route-agent", "j4"),
                        RuntimeContext.empty());
        VersionedFakeRepository repository =
                new VersionedFakeRepository(tempDir.resolve("repo-cache"));
        repository.publish("demo-skill", "v1", "# one");
        ReActAgent agent =
                agentWith(repository, SkillVersionPolicy.pinned(), SkillFilter.only("demo-skill"));
        DynamicSkillMiddleware middleware = middlewareOf(repository);

        // Session one (journaled) anchors v1.
        RuntimeContext rc1 = RuntimeContext.builder().userId("u").sessionId("s4a").build();
        io.agentscope.core.session.SessionRecorder anchorWriter =
                new io.agentscope.core.session.SessionRecorder(log, "t1");
        rc1.put(io.agentscope.core.session.SessionRecorder.CONTEXT_KEY, anchorWriter);
        String first = middleware.onSystemPrompt(agent, rc1, "").block();
        assertTrue(first.contains("demo-skill"));
        anchorWriter.close();

        // The backend answers an explicit v1 pull with v3-stamped content, and v3 is
        // current. The resumed session must not bind v3 content to the v1 reference: the
        // mismatch routes down the unreachable path, whose default decision is the audited
        // fallback to latest.
        repository.publish("demo-skill", "v3", "# three");
        repository.markMismatched("demo-skill", "v1", "v3");

        RuntimeContext rc2 = RuntimeContext.builder().userId("u").sessionId("s4b").build();
        io.agentscope.core.session.SessionRecorder resume =
                new io.agentscope.core.session.SessionRecorder(log, "t2");
        rc2.put(io.agentscope.core.session.SessionRecorder.CONTEXT_KEY, resume);
        String second = middleware.onSystemPrompt(agent, rc2, "").block();

        assertTrue(
                second.contains("demo-skill"),
                "the one-shot fallback keeps the skill served under pin semantics");
        assertEquals(
                "v3",
                rc2.get(SkillBox.class).getSkill("demo-skill_fake").getVersion(),
                "the mismatched pairing is refused; the served content is the current stamp");
        assertEquals(
                "v3",
                io.agentscope.core.skill.mirror.SkillAnchorService.findAnchor(
                                resume, "demo-skill_fake")
                        .orElseThrow()
                        .version(),
                "the journal re-anchors to the version actually served");
    }

    @Test
    @DisplayName("Contextual (workspace-style) repositories keep the per-call live read semantics")
    void testLiveTierKeepsPerCallReads() {
        // A context-scoped repository serving unstamped loads has no version capability: the
        // per-call full read keeps serving current content.
        ContextualFakeRepository live = new ContextualFakeRepository(tempDir.resolve("repo-cache"));
        live.publish("live-skill", "ignored", "# live");
        ReActAgent agent = agentWith(live, null);
        DynamicSkillMiddleware middleware = middlewareOf(live);
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s4").build();

        middleware.onSystemPrompt(agent, rc, "").block();
        middleware.onSystemPrompt(agent, rc, "").block();

        assertEquals(
                3,
                live.listCount,
                "a context-scoped repository keeps today's per-call read: establishment"
                        + " reads the name catalog and the fallback bulk — both through the"
                        + " context primitive getAllSkills(ctx), which also feeds"
                        + " getAllSkillNames(ctx) by default — plus the second pass");
        assertEquals(0, live.fetchCount, "the live tier never hits the version route");
    }

    @Test
    @DisplayName("Session-state table stays bounded; an evicted session re-establishes safely")
    void testSessionStateTableIsBounded() throws Exception {
        VersionedFakeRepository repository =
                new VersionedFakeRepository(tempDir.resolve("repo-cache"));
        repository.publish("demo-skill", "v1", "# one");
        ReActAgent agent = agentWith(repository, null, SkillFilter.only("demo-skill"));
        DynamicSkillMiddleware middleware = middlewareOf(repository);

        java.lang.reflect.Field sessionsField =
                DynamicSkillMiddleware.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        Map<?, ?> sessions = (Map<?, ?>) sessionsField.get(middleware);

        for (int i = 0; i < 300; i++) {
            RuntimeContext rc = RuntimeContext.builder().userId("u" + i).sessionId("s" + i).build();
            middleware.onSystemPrompt(agent, rc, "").block();
        }

        assertTrue(
                sessions.size() <= 256,
                "the session-state table must stay bounded, got " + sessions.size());

        // An evicted session re-establishes deterministically and still serves the skill.
        RuntimeContext first = RuntimeContext.builder().userId("u0").sessionId("s0").build();
        String prompt = middleware.onSystemPrompt(agent, first, "").block();
        assertTrue(prompt.contains("demo-skill"), "a re-established session must serve again");
    }

    /** Context-scoped repository without version capability: unstamped loads only, every
     * version-addressed pull answers nothing. */
    private static final class UnversionedFakeRepository implements RuntimeContextSkillRepository {

        @Override
        public AgentSkill getSkill(String name, String version) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public AgentSkill getSkill(String name) {
            return new AgentSkill(
                    "demo-skill", "Unversioned description", "# live", Map.of(), "unversioned");
        }

        @Override
        @SuppressWarnings("deprecation")
        public List<String> getAllSkillNames() {
            return List.of("demo-skill");
        }

        /** Context-scoped read: the versionless serving path. */
        @Override
        public List<AgentSkill> getAllSkills(RuntimeContext context) {
            return List.of(getSkill("demo-skill"));
        }

        @Override
        @SuppressWarnings("deprecation")
        public List<AgentSkill> getAllSkills() {
            return getAllSkills(RuntimeContext.empty());
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
            return true;
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("unversioned", "version-route-test", false);
        }

        @Override
        public String getSource() {
            return "unversioned";
        }

        @Override
        public void setWriteable(boolean writeable) {}

        @Override
        public boolean isWriteable() {
            return false;
        }

        @Override
        public void close() {}
    }

    /** Context-scoped version-capable fake: the request-bound catalog is the published name
     * set, served through the context-less pulls the interface defaults route to. */
    private static final class VersionedContextFakeRepository extends VersionedFakeRepository
            implements RuntimeContextSkillRepository {

        VersionedContextFakeRepository(Path cacheRoot) {
            super(cacheRoot);
        }

        @Override
        public List<AgentSkill> getAllSkills(RuntimeContext context) {
            return snapshot();
        }
    }

    /** Context-scoped repository with an empty catalog: nothing to pull, nothing to
     * classify — the establishment guard must name this shape, not claim failed pulls. */
    private static final class EmptyCatalogFakeRepository implements RuntimeContextSkillRepository {

        @Override
        public AgentSkill getSkill(String name, String version) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public List<String> getAllSkillNames() {
            return List.of();
        }

        @Override
        public List<AgentSkill> getAllSkills(RuntimeContext context) {
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
            return new AgentSkillRepositoryInfo("empty", "version-route-test", false);
        }

        @Override
        public String getSource() {
            return "empty";
        }

        @Override
        public void setWriteable(boolean writeable) {}

        @Override
        public boolean isWriteable() {
            return false;
        }
    }

    @Test
    @DisplayName("Strict pin over a no-version stack fails fast at establishment")
    void testStrictPinWithoutVersionCapabilityFails() {
        UnversionedFakeRepository repository = new UnversionedFakeRepository();
        ReActAgent agent = agentWith(repository, SkillVersionPolicy.pinned().failOnUnreachable());
        DynamicSkillMiddleware middleware = middlewareOf(repository);
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s").build();

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> middleware.onSystemPrompt(agent, rc, "").block(),
                        "A strict pin that no repository can honour must fail at establishment");
        assertTrue(
                error.getMessage().contains("PIN"),
                "The error must name the policy, got: " + error.getMessage());
        assertTrue(
                error.getMessage().contains("no registered repository loads versioned skills"),
                "The unstamped-loads shape must be named as such, got: " + error.getMessage());
    }

    @Test
    @DisplayName("Strict pin over an empty catalog fails fast with the empty-catalog diagnosis")
    void testStrictPinOverEmptyCatalogFailsWithAccurateDiagnosis() {
        EmptyCatalogFakeRepository repository = new EmptyCatalogFakeRepository();
        ReActAgent agent = agentWith(repository, SkillVersionPolicy.pinned().failOnUnreachable());
        DynamicSkillMiddleware middleware = middlewareOf(repository);
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s").build();

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> middleware.onSystemPrompt(agent, rc, "").block(),
                        "A strict pin over an empty catalog must still fail at establishment");
        assertTrue(
                error.getMessage().contains("no skill is available to pull"),
                "The empty-catalog shape must be named as such, got: " + error.getMessage());
    }

    @Test
    @DisplayName("A skill published after establishment becomes visible on the next TTL refresh")
    void testNewSkillVisibleAfterTtlRefresh() {
        VersionedContextFakeRepository repository =
                new VersionedContextFakeRepository(tempDir.resolve("repo-cache"));
        repository.publish("alpha", "v1", "# one");
        ReActAgent agent = agentWith(repository, null, null);
        DynamicSkillMiddleware middleware = middlewareOf(repository);
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s").build();

        String first = middleware.onSystemPrompt(agent, rc, "").block();
        assertTrue(first.contains("alpha"), "the established skill is served");
        assertFalse(first.contains("beta"), "the unpublished skill is absent");

        repository.publish("beta", "v1", "# two");
        DynamicSkillMiddleware.freshnessTtlMillis = 0;
        String second = middleware.onSystemPrompt(agent, rc, "").block();

        assertTrue(
                second.contains("beta"),
                "a skill published after establishment must appear once the TTL refresh"
                        + " re-lists the catalog");
        assertTrue(second.contains("alpha"), "the previously served skill stays");
    }

    @Test
    @DisplayName("Default (fallback) pin over a no-version stack degrades visibly to latest")
    void testFallbackPinWithoutVersionCapabilityServesLatest() {
        UnversionedFakeRepository repository = new UnversionedFakeRepository();
        ReActAgent agent =
                agentWith(repository, SkillVersionPolicy.pinned(), SkillFilter.only("demo-skill"));
        DynamicSkillMiddleware middleware = middlewareOf(repository);
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s").build();

        String prompt = middleware.onSystemPrompt(agent, rc, "").block();

        assertTrue(
                prompt.contains("demo-skill"),
                "The default fallback must keep serving the latest content");
    }

    @Test
    @DisplayName("Baseline skill filter bounds establishment pulls to the selection")
    void testBaselineFilterBoundsEstablishmentPulls() {
        VersionedFakeRepository repository =
                new VersionedFakeRepository(tempDir.resolve("repo-cache"));
        repository.publish("alpha", "v1", "# one");
        repository.publish("beta", "v1", "# two");
        repository.publish("gamma", "v1", "# three");
        ReActAgent agent = agentWith(repository, null, SkillFilter.only("beta"));
        DynamicSkillMiddleware middleware = middlewareOf(repository);
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s").build();

        String prompt = middleware.onSystemPrompt(agent, rc, "").block();

        assertTrue(prompt.contains("beta"), "the selected skill must serve");
        assertTrue(!prompt.contains("alpha"), "a baseline-denied skill must not surface");
        assertEquals(
                1,
                repository.nullPullCount,
                "establishment pulls exactly the baseline-allowed names");
        assertEquals(
                0,
                repository.listCount,
                "a version-capable repository is never bulk-read at establishment");
    }

    @Test
    @DisplayName("A runtime overlay enabling a baseline-denied name pulls it on demand")
    void testOverlayEnablePullsOnDemand() {
        VersionedFakeRepository repository =
                new VersionedFakeRepository(tempDir.resolve("repo-cache"));
        repository.publish("alpha", "v1", "# one");
        repository.publish("beta", "v1", "# two");
        ReActAgent agent = agentWith(repository, null, SkillFilter.only("alpha"));
        DynamicSkillMiddleware middleware = middlewareOf(repository);
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s").build();

        String first = middleware.onSystemPrompt(agent, rc, "").block();
        assertTrue(first.contains("alpha"), "the baseline-selected skill serves");
        assertEquals(
                1, repository.nullPullCount, "establishment pulled only the baseline-allowed name");

        RuntimeContext overlay = RuntimeContext.builder().userId("u").sessionId("s").build();
        overlay.put(SkillFilter.class, SkillFilter.enable("beta"));
        String second = middleware.onSystemPrompt(agent, overlay, "").block();

        assertEquals(
                2,
                repository.nullPullCount,
                "the overlay-enabled name is pulled exactly once, on demand");
        assertTrue(second.contains("beta"), "the overlay-enabled skill must serve");
        assertEquals(
                "v1",
                overlay.get(SkillBox.class).getSkill("beta_fake").getVersion(),
                "the lazily entered skill is version-bound like any other entry");
    }

    @Test
    @DisplayName("A fully filtered-out versioned repository keeps the version route, zero bulk")
    void testFullyFilteredVersionedRepoKeepsVersionRoute() {
        VersionedFakeRepository repository =
                new VersionedFakeRepository(tempDir.resolve("repo-cache"));
        repository.publish("alpha", "v1", "# one");
        ReActAgent agent = agentWith(repository, null, SkillFilter.only("missing"));
        DynamicSkillMiddleware middleware = middlewareOf(repository);
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s").build();

        String prompt = middleware.onSystemPrompt(agent, rc, "").block();

        assertEquals("", prompt, "nothing is visible under a matching-nothing baseline");
        assertEquals(
                2,
                repository.nullPullCount,
                "the unresolved selection costs one establishment pull plus one per-call"
                        + " explicit re-pull — the bulk read never runs");
        assertEquals(
                0,
                repository.listCount,
                "a stamped probe keeps the version route — the bulk read never runs");

        RuntimeContext overlay = RuntimeContext.builder().userId("u").sessionId("s").build();
        overlay.put(SkillFilter.class, SkillFilter.enable("alpha"));
        String second = middleware.onSystemPrompt(agent, overlay, "").block();
        assertTrue(
                second.contains("alpha"),
                "the session catalog still serves overlay-enabled names on demand");
    }
}
