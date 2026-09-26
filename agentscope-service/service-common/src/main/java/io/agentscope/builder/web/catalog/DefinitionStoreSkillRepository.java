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
package io.agentscope.builder.web.catalog;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.SkillUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads workspace skills from the control-plane {@link DefinitionStore} (paths under {@code
 * skills/{name}/…}), independent of the session Hands filesystem.
 */
public final class DefinitionStoreSkillRepository implements AgentSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(DefinitionStoreSkillRepository.class);

    public static final String SKILLS_PREFIX = "skills";
    public static final String SKILL_MD = "SKILL.md";
    private static final String SOURCE = "definition-store";

    private final DefinitionStore store;
    private final String ownerId;
    private final String agentId;
    private boolean writeable;

    public DefinitionStoreSkillRepository(DefinitionStore store, String ownerId, String agentId) {
        this.store = Objects.requireNonNull(store, "store");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.writeable = true;
    }

    @Override
    public String getSource() {
        return SOURCE;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo(
                "definition-store",
                "definitions/" + ownerId + "/" + agentId + "/skills",
                writeable);
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        List<AgentSkill> out = new ArrayList<>();
        for (String skillName : listSkillNames()) {
            try {
                loadSkill(skillName).ifPresent(out::add);
            } catch (Exception ex) {
                log.warn(
                        "Failed to load definition skill {}/{}/{}: {}",
                        ownerId,
                        agentId,
                        skillName,
                        ex.getMessage());
            }
        }
        return out;
    }

    @Override
    public List<String> getAllSkillNames() {
        return listSkillNames();
    }

    @Override
    public AgentSkill getSkill(String name) {
        return loadSkill(name)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Skill not found in definition store: " + name));
    }

    @Override
    public boolean skillExists(String skillName) {
        return loadSkill(skillName).isPresent();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        if (!writeable || skills == null || skills.isEmpty()) {
            return false;
        }
        for (AgentSkill skill : skills) {
            if (skill == null || skill.getName() == null || skill.getName().isBlank()) {
                continue;
            }
            String name = skill.getName();
            if (!force && skillExists(name)) {
                continue;
            }
            String markdown = skill.getSkillContent() != null ? skill.getSkillContent() : "";
            // Prefer full markdown if frontmatter was preserved in content; SkillUtil round-trip
            // stores body in skillContent — callers installing via controller write SKILL.md
            // directly.
            store.putText(ownerId, agentId, skillMdPath(name), markdown);
            if (skill.getResources() != null) {
                for (Map.Entry<String, String> e : skill.getResources().entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank()) {
                        continue;
                    }
                    store.putText(ownerId, agentId, skillFilePath(name, e.getKey()), e.getValue());
                }
            }
        }
        return true;
    }

    @Override
    public boolean delete(String skillName) {
        if (!writeable || skillName == null || skillName.isBlank()) {
            return false;
        }
        if (!skillExists(skillName)) {
            return false;
        }
        store.deletePrefix(ownerId, agentId, SKILLS_PREFIX + "/" + skillName);
        return true;
    }

    @Override
    public void setWriteable(boolean writeable) {
        this.writeable = writeable;
    }

    @Override
    public boolean isWriteable() {
        return writeable;
    }

    /** Skill directory names that contain a {@code SKILL.md}. */
    public List<String> listSkillNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String path : store.list(ownerId, agentId, SKILLS_PREFIX)) {
            String rest =
                    path.startsWith(SKILLS_PREFIX + "/")
                            ? path.substring(SKILLS_PREFIX.length() + 1)
                            : "";
            if (rest.isEmpty()) {
                continue;
            }
            int slash = rest.indexOf('/');
            String name = slash < 0 ? rest : rest.substring(0, slash);
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        List<String> withSkillMd = new ArrayList<>();
        for (String name : names) {
            if (store.getText(ownerId, agentId, skillMdPath(name)).isPresent()) {
                withSkillMd.add(name);
            }
        }
        return withSkillMd;
    }

    public Optional<AgentSkill> loadSkill(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return Optional.empty();
        }
        Optional<String> markdown = store.getText(ownerId, agentId, skillMdPath(skillName));
        if (markdown.isEmpty() || markdown.get().isBlank()) {
            return Optional.empty();
        }
        Map<String, String> resources = new LinkedHashMap<>();
        String prefix = SKILLS_PREFIX + "/" + skillName;
        for (String path : store.list(ownerId, agentId, prefix)) {
            if (path.equals(skillMdPath(skillName))) {
                continue;
            }
            String relative =
                    path.startsWith(prefix + "/") ? path.substring(prefix.length() + 1) : path;
            if (relative.isBlank() || relative.equals(SKILL_MD)) {
                continue;
            }
            store.getText(ownerId, agentId, path).ifPresent(body -> resources.put(relative, body));
        }
        return Optional.of(SkillUtil.createFrom(markdown.get(), resources, SOURCE));
    }

    public static String skillMdPath(String skillName) {
        return SKILLS_PREFIX + "/" + skillName + "/" + SKILL_MD;
    }

    public static String skillFilePath(String skillName, String relativeInsideSkill) {
        String rel = relativeInsideSkill == null ? "" : relativeInsideSkill.replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        if (rel.isEmpty()) {
            return SKILLS_PREFIX + "/" + skillName;
        }
        return SKILLS_PREFIX + "/" + skillName + "/" + rel;
    }
}
