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
package io.agentscope.harness.agent.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.SkillUtil;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only {@link AgentSkillRepository} backed by an {@link AbstractFilesystem}.
 *
 * <p>Lifts what was previously inline Layer-1 code in {@code DynamicSkillHook} into a proper
 * repository so that {@code DynamicSkillHook} can iterate any number of repositories uniformly.
 * Because {@link AbstractFilesystem} accepts a {@link RuntimeContext} on every call, this
 * repository delegates to a {@link Supplier} so each invocation observes the caller's current
 * per-user namespace. That is what enables Layer-1 (per-user) override semantics on top of
 * shared workspace skills.
 *
 * <p>Write operations are not supported — the namespaced read view is intentionally
 * unidirectional.
 */
public class FilesystemBackedSkillRepository implements AgentSkillRepository {

    private static final Logger log =
            LoggerFactory.getLogger(FilesystemBackedSkillRepository.class);

    private static final String SKILL_FILE = "SKILL.md";
    private static final String DEFAULT_SOURCE = "filesystem-namespaced";

    private final AbstractFilesystem filesystem;
    private final String skillsRelativeDir;
    private final Supplier<RuntimeContext> contextSupplier;
    private final String source;

    /**
     * @param filesystem        the namespaced filesystem to read skill files from (non-null)
     * @param skillsRelativeDir relative directory under the workspace that contains
     *                          {@code <skill-name>/SKILL.md} entries (non-null)
     * @param contextSupplier   supplies the {@link RuntimeContext} on each call so per-user
     *                          namespacing is honored (non-null)
     * @param source            source identifier attached to loaded skills; falls back to
     *                          {@code "filesystem-namespaced"} when null
     */
    public FilesystemBackedSkillRepository(
            AbstractFilesystem filesystem,
            String skillsRelativeDir,
            Supplier<RuntimeContext> contextSupplier,
            String source) {
        this.filesystem = Objects.requireNonNull(filesystem, "filesystem");
        this.skillsRelativeDir = Objects.requireNonNull(skillsRelativeDir, "skillsRelativeDir");
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier");
        this.source = source != null && !source.isBlank() ? source : DEFAULT_SOURCE;
    }

    @Override
    public AgentSkill getSkill(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (AgentSkill skill : getAllSkills()) {
            if (name.equals(skill.getName())) {
                return skill;
            }
        }
        return null;
    }

    @Override
    public List<String> getAllSkillNames() {
        List<AgentSkill> skills = getAllSkills();
        List<String> names = new ArrayList<>(skills.size());
        for (AgentSkill skill : skills) {
            names.add(skill.getName());
        }
        return names;
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        RuntimeContext ctx = currentContext();
        GlobResult glob;
        try {
            glob = filesystem.glob(ctx, SKILL_FILE, skillsRelativeDir);
        } catch (Exception e) {
            log.debug("Filesystem glob for skills failed: {}", e.getMessage());
            return Collections.emptyList();
        }
        if (!glob.isSuccess() || glob.matches() == null || glob.matches().isEmpty()) {
            return Collections.emptyList();
        }

        List<AgentSkill> skills = new ArrayList<>(glob.matches().size());
        for (FileInfo fi : glob.matches()) {
            String path = fi.path();
            if (path == null || path.isBlank()) {
                continue;
            }
            try {
                ReadResult rr = filesystem.read(ctx, path, 0, 0);
                if (!rr.isSuccess() || rr.fileData() == null || rr.fileData().content() == null) {
                    continue;
                }
                AgentSkill skill = SkillUtil.createFrom(rr.fileData().content(), null, source);
                skills.add(skill);
            } catch (Exception e) {
                log.warn("Failed to load skill from '{}': {}", path, e.getMessage());
            }
        }
        return skills;
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        log.warn("FilesystemBackedSkillRepository is read-only; save() ignored");
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        log.warn("FilesystemBackedSkillRepository is read-only; delete() ignored");
        return false;
    }

    @Override
    public boolean skillExists(String skillName) {
        return getSkill(skillName) != null;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("filesystem", skillsRelativeDir, false);
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public void setWriteable(boolean writeable) {
        // no-op — namespaced reads are intentionally one-way
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    private RuntimeContext currentContext() {
        RuntimeContext ctx = contextSupplier.get();
        return ctx != null ? ctx : RuntimeContext.empty();
    }
}
