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
package io.agentscope.core.skill.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the null semantics of {@link AgentSkillRepository#getSkill(String,
 * String)} and the latest-pull {@link AgentSkillRepository#getSkill(String)} default over it.
 *
 * <p>A no-version implementation answers {@code null} from {@code getSkill} (every
 * pull resolves nothing), while the default {@code getSkill} delegates to a null-version
 * (latest) pull and returns {@code null} when that pull resolves nothing.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
@Tag("unit")
@DisplayName("AgentSkillRepository version contract null semantics")
class AgentSkillRepositoryVersionContractTest {

    /** In-memory single-scope repository answering an explicit "no version" everywhere. */
    private static class MinimalRepository implements AgentSkillRepository {

        private final Map<String, AgentSkill> skillsByName = new LinkedHashMap<>();

        /** Creates the fake preloaded with the given skills. */
        private MinimalRepository(AgentSkill... skills) {
            for (AgentSkill skill : skills) {
                skillsByName.put(skill.getName(), skill);
            }
        }

        /** Explicit no-version answer — every version-addressed read resolves nothing. */
        @Override
        public AgentSkill getSkill(String name, String version) {
            return null;
        }

        /**
         * Invokes the interface default {@code getSkill} directly — this class's own
         * override would shadow the route under test, and {@code X.super.m()} is only legal
         * from a direct implementation, hence the helper lives here.
         */
        AgentSkill skillViaDefaultRoute(String name) {
            return AgentSkillRepository.super.getSkill(name);
        }

        /** Returns the loaded skill, or {@code null} when missing. */
        @Override
        public AgentSkill getSkill(String name) {
            return skillsByName.get(name);
        }

        /** Lists the loaded skill names. */
        @Override
        public List<String> getAllSkillNames() {
            return new ArrayList<>(skillsByName.keySet());
        }

        /** Lists all loaded skills. */
        @Override
        public List<AgentSkill> getAllSkills() {
            return new ArrayList<>(skillsByName.values());
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

        /** Whether the name is loaded. */
        @Override
        public boolean skillExists(String skillName) {
            return skillsByName.containsKey(skillName);
        }

        /** Minimal in-memory identity. */
        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("minimal", "memory", false);
        }

        /** Fixed source id. */
        @Override
        public String getSource() {
            return "minimal";
        }

        /** No-op stub. */
        @Override
        public void setWriteable(boolean writeable) {
            // no-op
        }

        /** Always read-only. */
        @Override
        public boolean isWriteable() {
            return false;
        }
    }

    /** MinimalRepository plus synthetic version answers, for observing the route behavior. */
    private static class VersionedRepository extends MinimalRepository {

        /** Creates the fake preloaded with the given skills. */
        private VersionedRepository(AgentSkill... skills) {
            super(skills);
        }

        /**
         * A {@code null}/blank version serves the latest (stamped with a synthetic
         * version); any other value serves exactly that version, or {@code null} when the
         * name or version is unknown.
         */
        @Override
        public AgentSkill getSkill(String name, String version) {
            if (!skillExists(name)) {
                return null;
            }
            if (version == null || version.isBlank()) {
                return getSkill(name).toBuilder().version("v-" + name).build();
            }
            if (!("v-" + name).equals(version)) {
                return null;
            }
            return getSkill(name);
        }
    }

    /** Builds a fully-populated test skill (content plus one resource) under the given name. */
    private static AgentSkill skill(String name) {
        return new AgentSkill(
                name,
                "Description of " + name,
                "# " + name + "\ncontent",
                Map.of("assets/note.txt", "resource of " + name),
                "test-source");
    }

    @Test
    @DisplayName("A no-version implementation explicitly returns null from the serving method")
    void testNoVersionImplementationReturnsNull() {
        MinimalRepository repository = new MinimalRepository(skill("a"));

        assertNull(
                repository.getSkill("a", "any-version"),
                "A no-version implementation must answer null for getSkill");
        assertNull(
                repository.getSkill("a", null),
                "A no-version implementation must answer null even for the latest pull");
    }

    @Test
    @DisplayName("The default getSkill serves via the null-version pull")
    void testDefaultGetSkillComposesVersionRoute() {
        AgentSkill alpha = skill("alpha");
        VersionedRepository repository = new VersionedRepository(alpha, skill("beta"));

        AgentSkill served = repository.skillViaDefaultRoute("alpha");
        assertEquals("alpha", served.getName());
        assertEquals(
                alpha.getSkillContent(),
                served.getSkillContent(),
                "The default getSkill must serve the current version's content");
        assertEquals(
                "v-alpha",
                served.getVersion(),
                "The null-version pull carries the current version's stamp");
        assertNull(
                repository.skillViaDefaultRoute("missing"),
                "A missing name resolves no skill through the serving method");
        assertNull(
                repository.getSkill("alpha", "v-wrong"),
                "A foreign version must not resolve (no-mismatch)");

        MinimalRepository noVersion = new MinimalRepository(skill("gamma"));
        assertNull(
                noVersion.skillViaDefaultRoute("gamma"),
                "A no-version backend resolves nothing through the serving method");
    }

    @Test
    @DisplayName(
            "The context getSkill default routes through the serving primitive, not a bulk scan")
    void testContextGetSkillDefaultRoutesThroughServingPrimitive() {
        List<String> servedRoutes = new ArrayList<>();
        RuntimeContext callerCtx = RuntimeContext.empty();
        RuntimeContextSkillRepository repository =
                new RuntimeContextSkillRepository() {

                    @Override
                    public AgentSkill getSkill(String name, String version) {
                        return null;
                    }

                    @Override
                    public AgentSkill getSkill(
                            String name, String version, RuntimeContext context) {
                        servedRoutes.add(name + "@" + version + "|" + (context == callerCtx));
                        return null;
                    }

                    @Override
                    public List<AgentSkill> getAllSkills(RuntimeContext context) {
                        throw new AssertionError(
                                "The latest-pull default must not bulk-scan to find one skill");
                    }

                    @Override
                    public List<String> getAllSkillNames() {
                        return List.of("alpha");
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
                        return new AgentSkillRepositoryInfo("fake", "ctx-default-test", false);
                    }

                    @Override
                    public String getSource() {
                        return "fake";
                    }

                    @Override
                    public void setWriteable(boolean writeable) {}

                    @Override
                    public boolean isWriteable() {
                        return false;
                    }
                };

        assertNull(repository.getSkill("alpha", callerCtx));
        assertEquals(
                List.of("alpha@null|true"),
                servedRoutes,
                "The context default delegates to getSkill(name, version, ctx) with a null"
                        + " version and the caller's context");
    }
}
