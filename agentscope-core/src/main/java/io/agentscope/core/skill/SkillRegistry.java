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

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure storage layer for {@link AgentSkill}s, keyed by skill id.
 *
 * <p>The skill activation state is not stored here: it is the per-session {@code activatedGroups}
 * on {@link io.agentscope.core.state.ToolContextState}.
 *
 * <p>This is a pure storage layer: parameters are assumed non-null and validation is performed at
 * the {@code Toolkit} layer.
 */
class SkillRegistry {
    private static final Logger logger = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, AgentSkill> skills = new ConcurrentHashMap<>();

    // ==================== Registration ====================

    /**
     * Registers a skill, replacing any existing skill with the same id. Re-registering the same
     * {@code (name, source)} pair (a content update) is the normal path. With the standard name
     * charset (no underscores) distinct pairs cannot share an id, so the collision warning below
     * is unreachable for valid skills and acts only as a tripwire against future rule relaxations
     * or caller-supplied mismatched ids.
     *
     * @param skillId The unique skill identifier (must not be null)
     * @param skill The skill implementation (must not be null)
     */
    void registerSkill(String skillId, AgentSkill skill) {
        AgentSkill existing = skills.get(skillId);
        if (isAmbiguousIdCollision(existing, skill)) {
            logger.warn(
                    "Skill id '{}' was registered for name='{}', source='{}' but is being"
                            + " overwritten by a different skill name='{}', source='{}'. With the"
                            + " standard name rule this cannot happen; it indicates a naming-rule"
                            + " regression or a caller-supplied mismatched id.",
                    skillId,
                    existing.getName(),
                    existing.getSource(),
                    skill.getName(),
                    skill.getSource());
        }
        skills.put(skillId, skill);
    }

    /**
     * Returns whether registering {@code incoming} under an id already held by {@code existing}
     * is an ambiguous id collision, i.e. the two skills are different {@code (name, source)}
     * pairs that map to the same concatenated id. Re-registration of the same pair (a content
     * update) is not a collision.
     *
     * @param existing the currently registered skill under the id ({@code null} when absent)
     * @param incoming the skill about to be registered under the same id
     * @return {@code true} when the overwrite is ambiguous, {@code false} otherwise
     */
    static boolean isAmbiguousIdCollision(AgentSkill existing, AgentSkill incoming) {
        return existing != null
                && (!existing.getName().equals(incoming.getName())
                        || !existing.getSource().equals(incoming.getSource()));
    }

    // ==================== Query Operations ====================

    /**
     * Gets a skill by ID.
     *
     * @param skillId The skill ID (must not be null)
     * @return The skill instance, or null if not found
     */
    AgentSkill getSkill(String skillId) {
        return skills.get(skillId);
    }

    /**
     * Gets all skill IDs.
     *
     * @return Set of skill IDs (never null, may be empty)
     */
    Set<String> getSkillIds() {
        return new TreeSet<>(skills.keySet());
    }

    /**
     * Checks if a skill exists.
     *
     * @param skillId The skill ID (must not be null)
     * @return true if the skill exists, false otherwise
     */
    boolean exists(String skillId) {
        return skills.containsKey(skillId);
    }

    // ==================== Removal Operations ====================

    /**
     * Removes a skill completely.
     *
     * @param skillId The skill ID (must not be null)
     */
    void removeSkill(String skillId) {
        skills.remove(skillId);
    }
}
