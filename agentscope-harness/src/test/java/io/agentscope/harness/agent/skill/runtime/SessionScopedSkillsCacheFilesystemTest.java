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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression for #3426: with {@code LocalFilesystemSpec().isolationScope(SESSION)} and a
 * non-workspace skill repository, the file tools must reach the files staged under
 * {@code <workspace>/.skills-cache/<session>/...} through the paths they hand back, instead of
 * resolving them to {@code <workspace>/<session>/.skills-cache/<session>/...}.
 */
class SessionScopedSkillsCacheFilesystemTest {

    private static final Pattern FILES_ROOT = Pattern.compile("<files-root>([^<]*)</files-root>");

    @Test
    void stagedSkillFilesAreReachableThroughSessionScopedFilesystem(
            @TempDir Path workspace, @TempDir Path project) {
        IsolationScope scope = IsolationScope.SESSION;
        HarnessSkillMiddleware middleware =
                new HarnessSkillMiddleware(
                        List.of(new DatabaseLikeRepo()),
                        new Toolkit(),
                        null,
                        null,
                        new MarketplaceStager(workspace),
                        ShellPathPolicy.localWithShell(workspace));
        middleware.isolationScope(scope);
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .isolationScope(scope)
                        .project(project)
                        .toFilesystem(workspace, scope.toNamespaceFactory());
        RuntimeContext rc = RuntimeContext.builder().sessionId("sess-1").userId("tnt").build();

        String prompt = middleware.onSystemPrompt(null, rc, "").block();
        assertNotNull(prompt);
        Matcher m = FILES_ROOT.matcher(prompt);
        assertTrue(m.find(), "the staged skill should advertise a files-root");
        String filesRoot = m.group(1).trim().replace("\\ ", " ");

        LsResult root = fs.ls(rc, filesRoot);
        assertTrue(root.isSuccess(), () -> "absolute files-root should be listable: " + root);
        String scriptsDir =
                root.entries().stream()
                        .map(FileInfo::path)
                        .filter(p -> p.contains("scripts"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(".skills-cache/sess-1/database/sales-order-manager/scripts/", scriptsDir);

        // The exact call from the report: list_files on the relative path ls just returned.
        LsResult scripts = fs.ls(rc, scriptsDir);
        assertTrue(scripts.isSuccess(), () -> "relative cache path must resolve: " + scripts);
        assertEquals(1, scripts.entries().size());

        ReadResult script = fs.read(rc, scripts.entries().get(0).path(), 0, 0);
        assertTrue(script.isSuccess(), () -> "staged script must be readable: " + script.error());
        assertTrue(script.fileData().content().contains("echo order"));
    }

    /** Stands in for the reporter's Postgres-backed repository: resources live in memory. */
    private static final class DatabaseLikeRepo implements AgentSkillRepository {

        private final AgentSkill skill;

        DatabaseLikeRepo() {
            Map<String, String> resources = new LinkedHashMap<>();
            resources.put("SKILL.md", "# sales-order-manager\n");
            resources.put("scripts/query.sh", "#!/bin/sh\necho order\n");
            skill =
                    new AgentSkill(
                            "sales-order-manager",
                            "desc",
                            "# sales-order-manager\n",
                            resources,
                            "database");
        }

        @Override
        public AgentSkill getSkill(String name) {
            return skill.getName().equals(name) ? skill : null;
        }

        @Override
        public List<String> getAllSkillNames() {
            return List.of(skill.getName());
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return List.of(skill);
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
            return skill.getName().equals(skillName);
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("database", "", false);
        }

        @Override
        public String getSource() {
            return "database";
        }

        @Override
        public boolean isWriteable() {
            return false;
        }

        @Override
        public void setWriteable(boolean writeable) {}
    }
}
