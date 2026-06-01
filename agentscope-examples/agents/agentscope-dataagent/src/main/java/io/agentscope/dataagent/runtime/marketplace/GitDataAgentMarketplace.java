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
package io.agentscope.dataagent.runtime.marketplace;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.GitSkillRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Git-backed per-user marketplace. Delegates clone / pull / file walking to {@link
 * GitSkillRepository} from the {@code agentscope-extensions-skill-git-repository} module.
 *
 * <p>Conventions match the underlying repository: skills live under {@code <repo>/skills/<name>/}
 * (or directly at the repo root if no {@code skills/} dir exists), each with a {@code SKILL.md}
 * and optional side-files.
 *
 * <p>Lifecycle: per-user instances are created/closed by {@link UserMarketplaceRegistry}. The
 * underlying clone is kept under the per-user cache provided to the factory at create time so two
 * users configuring the same upstream repo do not contend on a shared working copy.
 */
public class GitDataAgentMarketplace implements DataAgentMarketplace {

    private static final Logger logger = LoggerFactory.getLogger(GitDataAgentMarketplace.class);
    public static final String TYPE = "git";

    private final String id;
    private final String remoteUrl;
    private final String branch;
    private final Path localPath;
    private final GitSkillRepository repo;

    /**
     * @param id stable marketplace id chosen by the user
     * @param remoteUrl HTTPS or SSH URL of the upstream git repository
     * @param branch optional branch (null → remote default)
     * @param localPath optional local clone target; when null the underlying repository creates a
     *     temp directory and registers a JVM shutdown hook to clean it up
     */
    public GitDataAgentMarketplace(String id, String remoteUrl, String branch, Path localPath) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new IllegalArgumentException("remoteUrl must not be blank");
        }
        this.id = id;
        this.remoteUrl = remoteUrl.trim();
        this.branch = (branch == null || branch.isBlank()) ? null : branch.trim();
        this.localPath = localPath;
        this.repo =
                new GitSkillRepository(this.remoteUrl, this.branch, this.localPath, "git:" + id);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayLocation() {
        return branch != null ? remoteUrl + " @" + branch : remoteUrl;
    }

    @Override
    public List<MarketSkillSummary> list() {
        List<AgentSkill> skills = repo.getAllSkills();
        List<MarketSkillSummary> summaries = new ArrayList<>(skills.size());
        for (AgentSkill skill : skills) {
            summaries.add(new MarketSkillSummary(skill.getName(), skill.getDescription(), null));
        }
        return summaries;
    }

    @Override
    public MarketSkillContent fetch(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        AgentSkill skill = repo.getSkill(name);
        if (skill == null) {
            return null;
        }
        return new MarketSkillContent(
                skill.getName(),
                skill.getDescription(),
                skill.getSkillContent(),
                skill.getResources());
    }

    @Override
    public void close() {
        try {
            repo.close();
        } catch (RuntimeException e) {
            logger.warn("Failed to close git marketplace {} ({})", id, remoteUrl, e);
        }
    }
}
