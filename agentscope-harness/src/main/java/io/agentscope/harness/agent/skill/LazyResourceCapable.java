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

/**
 * Optional marker for {@link io.agentscope.core.skill.repository.AgentSkillRepository}
 * implementations that can supply a {@link SkillResources} accessor in addition to the
 * in-memory {@code AgentSkill.resources} map.
 *
 * <p>Detected via {@code instanceof} by the harness skill runtime: if a repository implements
 * this interface, the runtime will request a {@link SkillResources} per skill and consult it
 * as a fallback when {@code load_skill_through_path} misses the in-memory map. This is what
 * lets {@link WorkspaceSkillRepository} keep skills lazy on disk (or in sandbox) without
 * preloading every byte at registration time.
 *
 * <p>Repositories that preload all resources into {@code AgentSkill.resources} (e.g. core's
 * {@code FileSystemSkillRepository}, {@code ClasspathSkillRepository}, and most third-party
 * marketplace extensions) do not need to implement this — the in-memory map already covers
 * every path.
 */
public interface LazyResourceCapable {

    /**
     * Returns a lazy resource accessor for the named skill.
     *
     * <p>The returned accessor MUST capture or honor the given {@code ctx} so per-user
     * namespacing remains correct across calls.
     *
     * @param skillName the skill's {@code name} (not {@code skillId})
     * @param ctx       current runtime context; never {@code null} (callers pass
     *                  {@link RuntimeContext#empty()} when no context is available)
     * @return accessor for that skill's resource tree, never {@code null}
     *         (return {@link SkillResources#empty()} if the skill does not belong to this
     *         repository)
     */
    SkillResources resourcesFor(String skillName, RuntimeContext ctx);
}
