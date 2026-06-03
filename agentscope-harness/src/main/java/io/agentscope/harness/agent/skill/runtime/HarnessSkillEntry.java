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

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.skill.SkillResources;

/**
 * Per-call wrapper around an {@link AgentSkill} carrying harness-only metadata that does not
 * belong on the core data type.
 *
 * @param skill         the underlying skill (non-null)
 * @param lazyResources resource accessor when the source repository implements
 *                      {@link io.agentscope.harness.agent.skill.LazyResourceCapable};
 *                      {@code null} when only the in-memory {@code skill.resources} map is
 *                      available
 * @param filesRoot     absolute path root for shell execution of this skill's scripts; may be
 *                      {@code null} when no shell tool is available (in which case the prompt
 *                      omits {@code <files-root>}) or when the skill is sourced from a
 *                      repository whose contents are not present on any filesystem the shell
 *                      can reach
 */
@SuppressWarnings("deprecation")
public record HarnessSkillEntry(AgentSkill skill, SkillResources lazyResources, String filesRoot) {

    public HarnessSkillEntry {
        if (skill == null) {
            throw new IllegalArgumentException("skill must not be null");
        }
    }

    /** Convenience for runtime layers that have no shell mode wired yet. */
    public static HarnessSkillEntry of(AgentSkill skill, SkillResources lazyResources) {
        return new HarnessSkillEntry(skill, lazyResources, null);
    }
}
