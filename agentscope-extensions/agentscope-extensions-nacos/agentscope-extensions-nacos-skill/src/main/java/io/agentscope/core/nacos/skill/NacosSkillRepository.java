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
package io.agentscope.core.nacos.skill;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.skills.Skill;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.common.utils.StringUtils;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nacos-based implementation of {@link AgentSkillRepository}.
 *
 * <p>Reads skills from Nacos Config via {@code AiService.loadSkill(String)}. This implementation
 * supports read operations: {@link #getSkill(String)}, {@link #skillExists(String)}, {@link
 * #getRepositoryInfo()}, {@link #getSource()}, and {@link #isWriteable()}. List and write
 * operations ({@link #getAllSkillNames()}, {@link #getAllSkills()}, {@link #save(List, boolean)},
 * {@link #delete(String)})
 * are implemented as read-only no-ops: they return empty list or {@code false} and log a warning.
 */
public class NacosSkillRepository implements AgentSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(NacosSkillRepository.class);

    private static final String REPO_TYPE = "nacos";
    private static final String LOCATION_PREFIX = "namespace:";

    private final AiService aiService;
    private final String namespaceId;
    private final String source;
    private final String location;

    /**
     * Creates a Nacos skill repository.
     *
     * @param aiService   the Nacos AI service (must not be null)
     * @param namespaceId the Nacos namespace ID (null or blank is treated as default namespace)
     */
    public NacosSkillRepository(AiService aiService, String namespaceId) {
        if (aiService == null) {
            throw new IllegalArgumentException("AiService cannot be null");
        }
        this.aiService = aiService;
        this.namespaceId = StringUtils.isBlank(namespaceId) ? "public" : namespaceId.trim();
        this.source = REPO_TYPE + ":" + this.namespaceId;
        this.location = LOCATION_PREFIX + this.namespaceId;
        log.info("NacosSkillRepository initialized for namespace: {}", this.namespaceId);
    }

    @Override
    public AgentSkill getSkill(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }
        try {
            Skill nacosSkill = aiService.loadSkill(name.trim());
            if (nacosSkill == null) {
                throw new IllegalArgumentException("Skill not found: " + name);
            }
            return NacosSkillToAgentSkillConverter.toAgentSkill(nacosSkill, getSource());
        } catch (NacosException e) {
            if (e.getErrCode() == NacosException.NOT_FOUND) {
                throw new IllegalArgumentException("Skill not found: " + name, e);
            }
            throw new RuntimeException("Failed to load skill from Nacos: " + name, e);
        }
    }

    @Override
    public boolean skillExists(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        try {
            Skill skill = aiService.loadSkill(skillName.trim());
            return skill != null;
        } catch (NacosException e) {
            if (e.getErrCode() == NacosException.NOT_FOUND) {
                return false;
            }
            log.warn("Error checking skill existence for {}: {}", skillName, e.getMessage());
            return false;
        }
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo(REPO_TYPE, location, false);
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public void setWriteable(boolean writeable) {
        log.warn("NacosSkillRepository is read-only, set writeable operation ignored");
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    // ---------- Read-only no-op operations (list and write) ----------

    @Override
    public List<String> getAllSkillNames() {
        log.warn("NacosSkillRepository is read-only, getAllSkillNames returns empty list");
        return Collections.emptyList();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        log.warn("NacosSkillRepository is read-only, getAllSkills returns empty list");
        return Collections.emptyList();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        log.warn("NacosSkillRepository is read-only, save operation ignored");
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        log.warn("NacosSkillRepository is read-only, delete operation ignored");
        return false;
    }

    @Override
    public void close() {
        // AiService lifecycle is managed by the caller; nothing to release here
    }
}
