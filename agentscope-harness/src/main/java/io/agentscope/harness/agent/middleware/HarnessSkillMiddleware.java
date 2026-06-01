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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Harness extension of {@link io.agentscope.core.skill.DynamicSkillMiddleware} that layers a
 * harness-specific {@link SkillVisibilityFilter} on top of the core skill prompt rebuild.
 *
 * <p>The visibility filter applies environment / canary / allow-list / composite rules per
 * {@link RuntimeContext}, dropping skills the current request is not allowed to see <em>before</em>
 * they reach the {@link io.agentscope.core.skill.SkillBox}. When no filter is supplied, this class
 * behaves identically to its core parent.
 */
public class HarnessSkillMiddleware extends DynamicSkillMiddleware {

    private static final Logger log = LoggerFactory.getLogger(HarnessSkillMiddleware.class);

    private final SkillVisibilityFilter visibilityFilter;

    public HarnessSkillMiddleware(List<AgentSkillRepository> repositories, Toolkit toolkit) {
        this(repositories, toolkit, null, null);
    }

    public HarnessSkillMiddleware(
            List<AgentSkillRepository> repositories, Toolkit toolkit, SkillFilter builderFilter) {
        this(repositories, toolkit, builderFilter, null);
    }

    public HarnessSkillMiddleware(
            List<AgentSkillRepository> repositories,
            Toolkit toolkit,
            SkillFilter builderFilter,
            SkillVisibilityFilter visibilityFilter) {
        super(repositories, toolkit, builderFilter);
        this.visibilityFilter = visibilityFilter;
    }

    @Override
    protected List<AgentSkill> filterVisible(List<AgentSkill> raw, RuntimeContext ctx) {
        if (visibilityFilter == null || raw == null || raw.isEmpty()) {
            return raw;
        }
        try {
            List<AgentSkill> filtered = visibilityFilter.filter(new ArrayList<>(raw), ctx);
            return filtered != null ? filtered : raw;
        } catch (Exception e) {
            log.warn(
                    "SkillVisibilityFilter {} failed; treating as pass-through: {}",
                    visibilityFilter.getClass().getSimpleName(),
                    e.getMessage());
            return raw;
        }
    }
}
