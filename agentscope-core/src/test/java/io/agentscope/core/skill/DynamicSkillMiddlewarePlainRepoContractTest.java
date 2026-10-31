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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Serving contract for plain (non context-scoped) repositories: the middleware never
 * enumerates them — their bulk reads are deprecated for serving — and serves them only
 * through the explicit selection the filter declares and still admits.
 */
class DynamicSkillMiddlewarePlainRepoContractTest {

    /**
     * Plain fake whose deprecated bulk reads throw: any framework call to them fails this
     * test. Serving goes through {@code getSkill(name, version)} only; skills carry the
     * constructed stamp unless built unstamped, and null-version pulls are counted.
     */
    private static final class NoEnumerationFakeRepository implements AgentSkillRepository {

        private final Map<String, AgentSkill> skills = new LinkedHashMap<>();
        private final boolean stamped;
        final AtomicInteger nullPulls = new AtomicInteger();

        NoEnumerationFakeRepository(boolean stamped) {
            this.stamped = stamped;
        }

        void publish(String name, String version, String description) {
            skills.put(name, skill(name, version, description));
        }

        void redescribe(String name, String description) {
            AgentSkill current = skills.get(name);
            skills.put(name, skill(name, current.getVersion(), description));
        }

        private AgentSkill skill(String name, String version, String description) {
            return AgentSkill.builder()
                    .name(name)
                    .description(description)
                    .skillContent("# " + name)
                    .source("fake")
                    .version(stamped ? version : null)
                    .build();
        }

        @Override
        public List<String> getAllSkillNames() {
            throw new AssertionError("a plain repository must never be enumerated for serving");
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            throw new AssertionError("a plain repository must never be enumerated for serving");
        }

        @Override
        public AgentSkill getSkill(String name, String version) {
            if (name == null) {
                return null;
            }
            if (version == null || version.isBlank()) {
                nullPulls.incrementAndGet();
            }
            return skills.get(name);
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
            return skills.containsKey(skillName);
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("fake", "plain-repo-contract", false);
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

        @Override
        public void close() {}
    }

    /** Agent bound to one plain repository and an optional filter baseline (null = all). */
    private static ReActAgent agentWith(
            NoEnumerationFakeRepository repository, SkillFilter filter) {
        ReActAgent.Builder builder =
                ReActAgent.builder()
                        .name("plain-repo-agent")
                        .model(org.mockito.Mockito.mock(Model.class))
                        .skillRepository(repository);
        if (filter != null) {
            builder.skillFilter(filter);
        }
        return builder.build();
    }

    private static DynamicSkillMiddleware middlewareOf(AgentSkillRepository repository) {
        return new DynamicSkillMiddleware(List.of(repository), false);
    }

    private static RuntimeContext context(String session) {
        return RuntimeContext.builder().userId("u").sessionId(session).build();
    }

    @Test
    void explicitSelectionServesWithoutEnumeration() {
        NoEnumerationFakeRepository repository = new NoEnumerationFakeRepository(true);
        repository.publish("alpha", "v1", "Alpha skill");
        repository.publish("beta", "v1", "Beta skill");
        repository.publish("hidden", "v1", "Never selected");
        ReActAgent agent = agentWith(repository, SkillFilter.only("alpha", "beta"));
        DynamicSkillMiddleware middleware = middlewareOf(repository);
        RuntimeContext rc = context("s1");

        String prompt = middleware.onSystemPrompt(agent, rc, "").block();

        assertTrue(prompt.contains("alpha"), "an explicitly selected skill must serve");
        assertTrue(prompt.contains("beta"), "an explicitly selected skill must serve");
        assertFalse(prompt.contains("hidden"), "an unselected skill must never surface");
        assertEquals(
                2,
                repository.nullPulls.get(),
                "exactly the selection is pulled, once at establishment");
        assertEquals(
                "v1",
                rc.get(SkillBox.class).getSkill("alpha_fake").getVersion(),
                "stamped pulls bind versions like any other repository");
    }

    @Test
    void wildcardBaselineSkipsPlainRepositoryEntirely() {
        NoEnumerationFakeRepository repository = new NoEnumerationFakeRepository(true);
        repository.publish("alpha", "v1", "Alpha skill");
        ReActAgent agent = agentWith(repository, null);
        DynamicSkillMiddleware middleware = middlewareOf(repository);

        String prompt = middleware.onSystemPrompt(agent, context("s2"), "").block();

        assertEquals("", prompt, "a plain repository without explicit selection serves nothing");
        assertEquals(
                0,
                repository.nullPulls.get(),
                "nothing is pulled — enumeration is not a serving fallback");
    }

    @Test
    void unstampedPlainRepositoryKeepsPerCallFreshnessByExplicitRepull() {
        NoEnumerationFakeRepository repository = new NoEnumerationFakeRepository(false);
        repository.publish("live", "ignored", "Live v1");
        ReActAgent agent = agentWith(repository, SkillFilter.only("live"));
        DynamicSkillMiddleware middleware = middlewareOf(repository);
        RuntimeContext rc = context("s3");

        String first = middleware.onSystemPrompt(agent, rc, "").block();
        assertTrue(first.contains("Live v1"), "the selected skill serves its current content");

        repository.redescribe("live", "Live v2");
        String second = middleware.onSystemPrompt(agent, rc, "").block();

        assertTrue(
                second.contains("Live v2"),
                "the per-call explicit re-pull keeps serving current content without"
                        + " enumeration");
        assertFalse(second.contains("Live v1"), "stale content must not linger");
    }

    @Test
    void overlayEnableLazilyBindsVersionOnUnclassifiedPlainRepository() {
        NoEnumerationFakeRepository repository = new NoEnumerationFakeRepository(true);
        repository.publish("alpha", "v1", "Alpha skill");
        ReActAgent agent = agentWith(repository, SkillFilter.only("missing"));
        DynamicSkillMiddleware middleware = middlewareOf(repository);
        RuntimeContext rc = context("s4");

        String first = middleware.onSystemPrompt(agent, rc, "").block();
        assertEquals("", first, "the unresolved selection serves nothing");

        rc.put(SkillFilter.class, SkillFilter.enable("alpha"));
        String second = middleware.onSystemPrompt(agent, rc, "").block();

        assertTrue(second.contains("alpha"), "the overlay-enabled skill serves on demand");
        assertEquals(
                "v1",
                rc.get(SkillBox.class).getSkill("alpha_fake").getVersion(),
                "the lazily classified repository binds versions like any other");
    }
}
