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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DynamicSkillMiddlewareTest {

    @TempDir Path workDir;

    /** Minimal repository stub: only {@code getAllSkills()} is meaningful for these tests. */
    private static final class StubRepo implements AgentSkillRepository {
        List<AgentSkill> skills;

        StubRepo(List<AgentSkill> skills) {
            this.skills = skills;
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
            return skills;
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
            return null;
        }

        @Override
        public String getSource() {
            return "stub";
        }

        @Override
        public void setWriteable(boolean writeable) {}

        @Override
        public boolean isWriteable() {
            return false;
        }
    }

    private DynamicSkillMiddleware middleware(List<AgentSkill> skills) {
        return new DynamicSkillMiddleware(
                List.of(new StubRepo(skills)), new Toolkit(), null, false, workDir);
    }

    @Test
    void emptyRepositoryProducesNoBox() {
        DynamicSkillMiddleware mw = middleware(List.of());
        RuntimeContext rc = RuntimeContext.empty();

        String prompt = mw.onSystemPrompt(null, rc, "base").block();

        assertEquals("base", prompt);
        assertNull(rc.get(SkillBox.class), "no box should be exposed for an empty repository");
    }

    @Test
    void repositorySkillIsExposedViaRuntimeContext() {
        AgentSkill skill = new AgentSkill("my_skill", "My Skill", "# Content", null);
        DynamicSkillMiddleware mw = middleware(List.of(skill));
        RuntimeContext rc = RuntimeContext.empty();

        String prompt = mw.onSystemPrompt(null, rc, "base").block();

        assertNotNull(
                rc.get(SkillBox.class), "the per-call SkillBox must be exposed via RuntimeContext");
        assertTrue(prompt.contains("my_skill"), "the skill prompt must contain the skill name");
    }

    @Test
    void reusedRuntimeContextClearsStaleBox() {
        AgentSkill skill = new AgentSkill("my_skill", "My Skill", "# Content", null);
        StubRepo repo = new StubRepo(List.of(skill));
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(List.of(repo), new Toolkit(), null, false, workDir);
        RuntimeContext rc = RuntimeContext.empty();

        mw.onSystemPrompt(null, rc, "base").block();
        assertNotNull(rc.get(SkillBox.class));

        // Repository becomes empty → a reused RuntimeContext must no longer serve the stale box.
        repo.skills = List.of();
        mw.onSystemPrompt(null, rc, "base").block();
        assertNull(
                rc.get(SkillBox.class), "a reused RuntimeContext must have its stale box cleared");
    }
}
