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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager;
import io.agentscope.harness.agent.skill.runtime.ShellPathPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for orphan reclamation of {@code .skills-cache} when the skill repositories
 * stop publishing skills.
 *
 * <p>Orphan GC lives inside {@link MarketplaceStager#stage}, so any early return that skips
 * staging also skips reclamation: previously staged directories then stay on the host workspace
 * and keep being projected into the sandbox even though no skill exists any more.
 */
class HarnessSkillMiddlewareCacheGcTest {

    @TempDir Path workspace;

    @Test
    @DisplayName("onSystemPrompt reclaims staged skills once repositories return empty")
    void onSystemPromptReclaimsOrphansWhenRepositoryBecomesEmpty() throws IOException {
        Path staged = stalePreviouslyStagedSkill();

        HarnessSkillMiddleware middleware = middlewareWithEmptyRepository();
        middleware.onSystemPrompt(null, RuntimeContext.empty(), "").block();

        assertFalse(Files.exists(staged), "stale staged skill must be reclaimed: " + staged);
    }

    @Test
    @DisplayName("prestageMarketplaceSkills reclaims staged skills once repositories return empty")
    void prestageReclaimsOrphansWhenRepositoryBecomesEmpty() throws IOException {
        Path staged = stalePreviouslyStagedSkill();

        HarnessSkillMiddleware middleware = middlewareWithEmptyRepository();
        middleware.prestageMarketplaceSkills(RuntimeContext.empty());

        assertFalse(Files.exists(staged), "stale staged skill must be reclaimed: " + staged);
    }

    private HarnessSkillMiddleware middlewareWithEmptyRepository() {
        return new HarnessSkillMiddleware(
                List.of(new EmptySkillRepository()),
                new Toolkit(),
                null,
                null,
                new MarketplaceStager(workspace),
                ShellPathPolicy.noShell());
    }

    /** Simulates content left behind by an earlier call, before the skill was deleted. */
    private Path stalePreviouslyStagedSkill() throws IOException {
        Path staged =
                workspace
                        .resolve(MarketplaceStager.CACHE_DIR)
                        .resolve("mysql")
                        .resolve("demo-skill");
        Files.createDirectories(staged);
        Files.writeString(staged.resolve("SKILL.md"), "---\nname: demo-skill\n---\n# demo\n");
        Files.writeString(staged.resolve("run.sh"), "#!/bin/bash\necho hello\n");
        assertTrue(Files.exists(staged), "precondition: staged skill exists");
        return staged;
    }

    /** A repository that no longer publishes any skill (e.g. rows deleted from the database). */
    private static final class EmptySkillRepository implements AgentSkillRepository {

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
            return new AgentSkillRepositoryInfo("mysql", "test", false);
        }

        @Override
        public String getSource() {
            return "mysql";
        }

        @Override
        public void setWriteable(boolean writeable) {}

        @Override
        public boolean isWriteable() {
            return false;
        }
    }
}
